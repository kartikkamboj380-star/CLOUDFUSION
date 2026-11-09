package com.cloudfusion.app

import kotlinx.coroutines.*
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

interface UrlUploadTarget {
    suspend fun create(source: UrlSource): String
    suspend fun probe(session: String, size: Long): UploadReply
    suspend fun send(session: String, offset: Long, source: UrlSource, bytes: ByteArray): UploadReply
}

data class UrlCheckpoint(val session: String, val offset: Long, val md5: String? = null, val sha256: String? = null)

/** No filesystem access. Source read and Drive send alternate, imposing bounded backpressure. */
class UrlTransferEngine(private val sources: UrlSourceClient) {
    suspend fun run(source: UrlSource, savedSession: String?, connections: Int, bufferBytes: Int,
                    destination: UrlUploadTarget, checkpoint: suspend (UrlCheckpoint) -> Unit,
                    rates: (Long, Long, Int) -> Unit = { _, _, _ -> }): JSONObject {
        require(connections in 1..50)
        require(bufferBytes in listOf(256 * 1024, 1024 * 1024, 4 * 1024 * 1024))
        val session = savedSession ?: destination.create(source).also { checkpoint(UrlCheckpoint(it, 0)) }
        val probe = destination.probe(session, source.size)
        if (probe is UploadReply.Complete) return probe.file
        var offset = (probe as UploadReply.Incomplete).offset
        check(offset in 0 until source.size) { "Google has all bytes without a confirmed final file. Check Drive, then resume later." }
        checkpoint(UrlCheckpoint(session, offset))
        require(offset == 0L || source.resumable) { "Source does not support safe resume (strong ETag + ranges required). Cancel this task and inspect Drive before starting a new transfer from byte zero." }
        val md5 = if (offset == 0L) MessageDigest.getInstance("MD5") else null
        val sha = if (offset == 0L) MessageDigest.getInstance("SHA-256") else null
        var finalMd5: String? = null; var finalSha: String? = null
        val parallel = if (source.resumable) connections else 1
        val whole = if (!source.resumable) withContext(Dispatchers.IO) { sources.openWhole(source) } else null
        try {
            while (offset < source.size) {
                coroutineContext.ensureActive()
                val count = minOf(bufferBytes.toLong(), source.size - offset).toInt()
                val readStart = System.nanoTime()
                val bytes = if (whole == null) sources.rangeBlock(source, offset, count, parallel) else withContext(Dispatchers.IO) {
                    ByteArray(count).also { readExactly(whole.input, it) }
                }
                val downloadNanos = (System.nanoTime() - readStart).coerceAtLeast(1)
                coroutineContext.ensureActive()
                md5?.update(bytes); sha?.update(bytes)
                if (offset + count == source.size && md5 != null) {
                    finalMd5 = md5.digest().hex(); finalSha = sha!!.digest().hex()
                    checkpoint(UrlCheckpoint(session, offset, finalMd5, finalSha)) // Before final HTTP request.
                }
                val uploadStart = System.nanoTime()
                val reply = destination.send(session, offset, source, bytes)
                val uploadNanos = (System.nanoTime() - uploadStart).coerceAtLeast(1)
                coroutineContext.ensureActive()
                rates(count * 1_000_000_000L / downloadNanos, count * 1_000_000_000L / uploadNanos, parallel)
                when (reply) {
                    is UploadReply.Complete -> {
                        check(offset + count == source.size && reply.file.text("id") != null) { "Drive returned an invalid or premature completion. Inspect the destination." }
                        return reply.file
                    }
                    is UploadReply.Incomplete -> {
                        checkpoint(UrlCheckpoint(session, reply.offset, finalMd5, finalSha))
                        check(reply.offset == offset + count && reply.offset < source.size) { "Drive acknowledged an unexpected offset. Resume to probe; no bytes were blindly replayed." }
                        offset = reply.offset
                    }
                }
            }
            error("Drive did not confirm completion. Check destination before replacing the task.")
        } finally { withContext(NonCancellable + Dispatchers.IO) { whole?.close() } }
    }
}

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
