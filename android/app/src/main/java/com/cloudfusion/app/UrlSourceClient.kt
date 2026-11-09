package com.cloudfusion.app

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** No bearer tokens, cookies, disk cache or authorization headers on source requests. */
data class UrlSource(val url: String, val name: String, val size: Long, val mime: String,
                     val etag: String?, val modified: String?, val ranges: Boolean) {
    val resumable get() = ranges && etag != null
    fun json() = JSONObject().put("url", url).put("name", name).put("size", size).put("mime", mime)
        .put("etag", etag ?: JSONObject.NULL).put("modified", modified ?: JSONObject.NULL).put("ranges", ranges)
    companion object {
        fun fromJson(o: JSONObject) = UrlSource(o.getString("url"), o.getString("name"), o.getLong("size"),
            o.getString("mime"), o.text("etag"), o.text("modified"), o.optBoolean("ranges"))
    }
}

internal fun publicSourceAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return false
    val b = address.address.map { it.toInt() and 255 }
    if (b.size == 4) return !(b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 ||
        (b[0] == 100 && b[1] in 64..127) || (b[0] == 169 && b[1] == 254) ||
        (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] in listOf(0, 168)) ||
        (b[0] == 198 && b[1] in 18..19))
    // Only global IPv6 unicast, excluding documentation and transition mechanisms.
    return b.size == 16 && b[0] in 0x20..0x3f && !(b[0] == 0x20 && b[1] == 0x02) &&
        !(b[0] == 0x20 && b[1] == 0x01 && (b[2] == 0 || (b[2] == 0x0d && b[3] == 0xb8)))
}

class UrlSourceException(message: String) : Exception(message)

class SourceStream(val response: Response) : Closeable {
    val input: InputStream = response.body?.byteStream() ?: throw UrlSourceException("Source returned no file body.")
    override fun close() = response.close()
}

class UrlSourceClient internal constructor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .proxy(Proxy.NO_PROXY).dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
                if (addresses.isEmpty() || addresses.any { !publicSourceAddress(it) })
                    throw java.net.UnknownHostException("Private or special source address blocked")
            }
        }).connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).build(),
    private val allowed: (HttpUrl) -> Boolean = { it.scheme == "https" && it.port == 443 }
) {
    private fun target(value: String): HttpUrl {
        require(value.length in 1..8192 && value.none { it.code <= 32 || it.code == 127 }) { "Enter a valid direct HTTPS file URL." }
        val url = value.toHttpUrlOrNull() ?: throw UrlSourceException("Invalid download URL.")
        require(allowed(url) && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
            "Use a public HTTPS URL on port 443 without credentials or fragments. HTTP is not supported."
        }
        return url
    }
    private fun request(value: String, method: String = "GET", headers: Map<String, String> = emptyMap()): Response {
        var url = target(value)
        repeat(6) {
            val builder = Request.Builder().url(url).header("Accept-Encoding", "identity").header("User-Agent", "CloudFusion/0.4")
            headers.forEach { (key, value) -> builder.header(key, value) }
            if (method == "HEAD") builder.head()
            val response = client.newCall(builder.build()).execute()
            if (response.code in listOf(301, 302, 303, 307, 308)) {
                val location = response.header("Location")
                response.close()
                url = target(url.resolve(location ?: throw UrlSourceException("Source redirect has no destination."))?.toString()
                    ?: throw UrlSourceException("Invalid source redirect."))
            } else return response
        }
        throw UrlSourceException("Too many source redirects.")
    }
    private fun failure(code: Int): Nothing = throw UrlSourceException(when (code) {
        401, 403 -> "Source denied access (HTTP $code). It may need login or the link may have expired; use a public direct link."
        404, 410 -> "Source file was not found or the link expired (HTTP $code)."
        412 -> "Source file changed. Do not resume; inspect Drive before starting a replacement."
        429 -> "Source is rate limiting requests. Pause, reduce connections and retry later."
        in 500..599 -> "Source server failed (HTTP $code). Retry later."
        else -> "Unsupported source response (HTTP $code)."
    })
    private fun encoding(response: Response) {
        check(response.header("Content-Encoding", "identity").equals("identity", true)) { "Encoded source streams are not supported." }
    }
    /** Probe one byte. A 200 response is closed immediately, never buffered as a full file. */
    fun inspect(value: String): UrlSource {
        target(value)
        request(value, headers = mapOf("Range" to "bytes=0-0")).use { r ->
            if (r.code !in listOf(200, 206)) failure(r.code)
            encoding(r)
            val mime = r.header("Content-Type", "application/octet-stream")!!.substringBefore(';').trim().lowercase()
            require(mime !in listOf("text/html", "application/xhtml+xml") && !isWorkspaceNative(mime)) { "This is a page or unsupported resource, not a binary direct file." }
            require(Regex("[A-Za-z0-9!#$&^_.+\\-]+/[A-Za-z0-9!#$&^_.+\\-]+").matches(mime)) { "Source returned an invalid file type." }
            val range = r.header("Content-Range")
            val size = if (r.code == 206) {
                val match = Regex("bytes 0-0/([0-9]+)").matchEntire(range.orEmpty())
                    ?: throw UrlSourceException("Source returned an invalid preview byte range.")
                require(r.body?.contentLength() == 1L) { "Invalid preview response length." }
                require(r.body?.byteStream()?.read() != -1) { "Empty source preview." }
                match.groupValues[1].toLongOrNull()
            } else r.body?.contentLength()
            require(size != null && size in 1..MAX_TRANSFER_SIZE) { "A known nonzero file size up to 5 TiB is required. Unknown-size streams are not supported." }
            val etag = r.header("ETag")?.takeIf { it.length in 2..1024 && it.startsWith('"') && it.endsWith('"') && !it.startsWith("W/") }
            val modified = r.header("Last-Modified")?.takeIf { it.length <= 128 }
            val rawName = r.request.url.pathSegments.lastOrNull().orEmpty()
            val name = rawName.map { if (it.code < 32 || it.code == 127 || it == '/' || it == '\\') '_' else it }.joinToString("").trim('.', ' ').take(240).ifBlank { "download" }
            return UrlSource(value, name, size, mime, etag, modified, r.code == 206)
        }
    }
    private fun conditions(source: UrlSource): Map<String, String> = when {
        source.etag != null -> mapOf("If-Match" to source.etag)
        source.modified != null -> mapOf("If-Unmodified-Since" to source.modified)
        else -> emptyMap()
    }
    private fun validate(r: Response, s: UrlSource) {
        encoding(r)
        require(s.etag == null || r.header("ETag") == s.etag) { "Source ETag changed or disappeared. Transfer stopped." }
        require(s.modified == null || r.header("Last-Modified") == s.modified) { "Source modification time changed. Transfer stopped." }
        require(r.header("Content-Type", "application/octet-stream")!!.substringBefore(';').trim().equals(s.mime, true)) { "Source file type changed." }
    }
    fun openWhole(source: UrlSource): SourceStream {
        val response = request(source.url, headers = conditions(source))
        try {
            if (response.code != 200) failure(response.code)
            validate(response, source)
            require(response.body?.contentLength() == source.size) { "Source size changed." }
            return SourceStream(response)
        } catch (e: Exception) { response.close(); throw e }
    }
    private fun fillRange(source: UrlSource, start: Long, target: ByteArray, from: Int, count: Int) {
        val end = start + count - 1
        request(source.url, headers = conditions(source) + mapOf("Range" to "bytes=$start-$end", "If-Range" to requireNotNull(source.etag))).use { r ->
            if (r.code == 200) throw UrlSourceException("Source ignored Range. Pause and inspect Drive; safe resume is unavailable. Start a new single-connection transfer only after reviewing the old destination.")
            if (r.code != 206) failure(r.code)
            validate(r, source)
            require(r.header("Content-Range") == "bytes $start-$end/${source.size}" && r.body?.contentLength() == count.toLong()) { "Source returned an incorrect byte range or size." }
            readExactly(r.body!!.byteStream(), target, from, count)
        }
    }
    /** One bounded destination block; no prefetch beyond it. Disjoint slices share one buffer. */
    suspend fun rangeBlock(source: UrlSource, start: Long, count: Int, connections: Int): ByteArray = coroutineScope {
        require(source.resumable && count in 1..4 * 1024 * 1024 && start >= 0 && start + count <= source.size)
        require(connections in 1..50)
        val buffer = ByteArray(count)
        val n = minOf(connections, count)
        (0 until n).map { index ->
            async(Dispatchers.IO) {
                ensureActive()
                val from = (index.toLong() * count / n).toInt()
                val to = ((index + 1L) * count / n).toInt()
                fillRange(source, start + from, buffer, from, to - from)
                ensureActive()
            }
        }.awaitAll()
        buffer
    }
}

internal fun readExactly(stream: InputStream, buffer: ByteArray, from: Int = 0, count: Int = buffer.size) {
    var n = 0
    while (n < count) {
        val read = stream.read(buffer, from + n, count - n)
        check(read > 0) { "Source stream ended early. Resume to reconcile Google Drive before retrying." }
        n += read
    }
}
