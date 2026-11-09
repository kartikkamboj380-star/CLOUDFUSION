package com.cloudfusion.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.TimeUnit

class UrlTransferEngineTest {
    private lateinit var server: MockWebServer
    private lateinit var source: UrlSourceClient
    private var data = ByteArray(800000) { (it % 251).toByte() }
    private var ranges = true
    private var etag = "\"stable\""
    private var badRange = false
    private var status = 200
    private val seen = Collections.synchronizedList(mutableListOf<RecordedRequest>())

    @Before fun setup() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen.add(request)
                if (request.path == "/redirect") return MockResponse().setResponseCode(302).addHeader("Location", "/file.bin")
                if (request.path == "/loop") return MockResponse().setResponseCode(302).addHeader("Location", "/loop")
                if (status != 200) return MockResponse().setResponseCode(status)
                val match = request.getHeader("Range")?.let { Regex("bytes=([0-9]+)-([0-9]+)").matchEntire(it) }
                val response = MockResponse().addHeader("ETag", etag).addHeader("Content-Type", "application/octet-stream")
                if (ranges && match != null) {
                    val from = match.groupValues[1].toInt(); val to = match.groupValues[2].toInt()
                    return response.setResponseCode(206).addHeader("Content-Range", if (badRange) "bytes 0-9/99" else "bytes $from-$to/${data.size}")
                        .setBody(Buffer().write(data, from, to - from + 1))
                }
                return response.setBody(Buffer().write(data))
            }
        }
        server.start()
        source = UrlSourceClient(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .readTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build(),
            { it.host == server.hostName && it.port == server.port })
    }
    @After fun teardown() { server.shutdown() }
    private fun info(path: String = "/file.bin") = source.inspect(server.url(path).toString())

    private inner class Destination : UrlUploadTarget {
        val bytes = ByteArrayOutputStream()
        var creates = 0
        var sends = 0
        var failAfterAccepted = false
        var checkpointBeforeCreateSend = false
        var lastCheckpoint: UrlCheckpoint? = null
        override suspend fun create(source: UrlSource): String { creates++; return "session-private" }
        override suspend fun probe(session: String, size: Long): UploadReply =
            if (bytes.size().toLong() == size) UploadReply.Complete(file()) else UploadReply.Incomplete(bytes.size().toLong())
        private fun file() = JSONObject().put("id", "drive-file").put("size", bytes.size())
            .put("md5Checksum", MessageDigest.getInstance("MD5").digest(bytes.toByteArray()).hex())
            .put("sha256Checksum", MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).hex())
        override suspend fun send(session: String, offset: Long, source: UrlSource, bytes: ByteArray): UploadReply {
            assertNotNull("Session checkpoint must be durable before sending", lastCheckpoint)
            assertEquals(this.bytes.size().toLong(), offset)
            assertTrue(bytes.size <= 1024 * 1024)
            sends++; this.bytes.write(bytes)
            if (failAfterAccepted) { failAfterAccepted = false; throw IOException("simulated lost acknowledgement") }
            return if (this.bytes.size().toLong() == source.size) {
                if (offset == 0L) assertNotNull("Digest persisted before final request", lastCheckpoint?.sha256)
                UploadReply.Complete(file())
            } else UploadReply.Incomplete(this.bytes.size().toLong())
        }
        suspend fun checkpoint(value: UrlCheckpoint) { lastCheckpoint = value }
    }

    @Test fun smallFileStreamsAndPersistsHashesBeforeFinalUpload() = runBlocking {
        data = "small real payload".toByteArray()
        val destination = Destination()
        val result = UrlTransferEngine(source).run(info(), null, 1, 262144, destination, destination::checkpoint)
        assertEquals("drive-file", result.getString("id"))
        assertArrayEquals(data, destination.bytes.toByteArray())
        assertEquals(MessageDigest.getInstance("SHA-256").digest(data).hex(), destination.lastCheckpoint?.sha256)
        assertTrue(seen.all { it.getHeader("Authorization") == null && it.getHeader("Cookie") == null })
    }

    @Test fun largeFileHasBoundedBlocksAndRealBackpressure() = runBlocking {
        data = ByteArray(5 * 1024 * 1024 + 37) { (it % 127).toByte() }
        val destination = Destination()
        val result = UrlTransferEngine(source).run(info(), null, 4, 1024 * 1024, destination, destination::checkpoint)
        assertEquals(6, destination.sends)
        assertArrayEquals(data, destination.bytes.toByteArray())
        assertEquals(MessageDigest.getInstance("SHA-256").digest(data).hex(), result.getString("sha256Checksum"))
        assertEquals(destination.lastCheckpoint?.sha256, result.getString("sha256Checksum"))
        assertEquals(1, destination.creates)
    }

    @Test fun controlledFiftyRangeConnectionsAssembleOneDriveFile() = runBlocking {
        data = ByteArray(1024 * 1024) { (it % 239).toByte() }
        val destination = Destination()
        UrlTransferEngine(source).run(info(), null, 50, 1024 * 1024, destination, destination::checkpoint)
        assertEquals(51, seen.size) // preview + exactly 50 disjoint ranges
        assertEquals(1, destination.sends)
        assertEquals(1, destination.creates)
        assertArrayEquals(data, destination.bytes.toByteArray())
        assertTrue(seen.drop(1).all { it.getHeader("If-Match") == "\"stable\"" })
    }

    @Test fun nonRangeSourceFallsBackToOneStreamWithoutLocalFile() = runBlocking {
        ranges = false
        val destination = Destination()
        var effective = 0
        val metadata = info()
        assertFalse(metadata.resumable)
        UrlTransferEngine(source).run(metadata, null, 50, 262144, destination, destination::checkpoint,
            rates = { down, up, count -> assertTrue(down >= 0 && up >= 0); effective = count })
        assertArrayEquals(data, destination.bytes.toByteArray())
        assertEquals(2, seen.size) // one preview, one streaming GET, no parallel ranges
        assertEquals(1, effective)
    }

    @Test fun restartAndRetryProbeDriveRatherThanTrustingSavedOffset() = runBlocking {
        val destination = Destination(); destination.failAfterAccepted = true
        val metadata = info()
        try {
            UrlTransferEngine(source).run(metadata, null, 4, 262144, destination, destination::checkpoint)
            fail("must report lost response")
        } catch (_: IOException) { }
        assertEquals(0L, destination.lastCheckpoint!!.offset)
        assertEquals(262144, destination.bytes.size())
        // Simulate process restart: reconstruct metadata from encrypted-state payload format.
        val restored = UrlSource.fromJson(JSONObject(metadata.json().toString()))
        UrlTransferEngine(source).run(restored, destination.lastCheckpoint!!.session, 4, 262144, destination, destination::checkpoint)
        assertEquals(1, destination.creates)
        assertArrayEquals(data, destination.bytes.toByteArray())
    }

    @Test fun pauseAfterChunkThenResumeAndCancelBeforeNextChunk() = runBlocking {
        val destination = Destination()
        val metadata = info()
        var checkpoints = 0
        try {
            UrlTransferEngine(source).run(metadata, null, 1, 262144, destination, checkpoint = {
                destination.checkpoint(it); checkpoints++
                if (it.offset > 0) throw CancellationException("pause")
            })
            fail("must pause")
        } catch (_: CancellationException) { }
        assertEquals(262144, destination.bytes.size())
        val requestsAtPause = seen.size
        // No source requests proceed after cancellation has unwound.
        assertEquals(requestsAtPause, seen.size)
        UrlTransferEngine(source).run(metadata, "session-private", 1, 262144, destination, destination::checkpoint)
        assertArrayEquals(data, destination.bytes.toByteArray())
    }

    @Test fun nonRangeResumeRefusesToReplayAcceptedBytes() = runBlocking {
        ranges = false
        val destination = Destination()
        destination.bytes.write(data, 0, 262144)
        val metadata = info()
        try {
            UrlTransferEngine(source).run(metadata, "session-private", 1, 262144, destination, destination::checkpoint)
            fail("unsafe resume must fail")
        } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("safe resume")) }
        assertEquals(1, seen.size)
        assertEquals(0, destination.sends)
    }

    @Test fun changedSourceAndInvalidRangeNeverReachDestination() = runBlocking {
        val metadata = info()
        etag = "\"changed\""
        val destination = Destination()
        try {
            UrlTransferEngine(source).run(metadata, null, 1, 262144, destination, destination::checkpoint)
            fail("changed ETag must stop")
        } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("ETag")) }
        assertEquals(0, destination.sends)
        etag = "\"stable\""; badRange = true
        assertThrows(UrlSourceException::class.java) { info() }
    }

    @Test fun redirectsErrorsAndUnsafeProtocolsAreClear() {
        assertEquals(data.size.toLong(), info("/redirect").size)
        assertThrows(UrlSourceException::class.java) { info("/loop") }
        for (code in listOf(403, 404, 429, 503)) {
            status = code
            val error = assertThrows(UrlSourceException::class.java) { info() }
            assertTrue(error.message!!.contains(if (code == 429) "rate limiting" else if (code == 503) "503" else code.toString()))
        }
        val strict = UrlSourceClient()
        for (value in listOf("file:///etc/passwd", "ftp://host/file", "http://example.com/file", "not a url", "https://user:pass@example.com/file")) {
            assertThrows(Exception::class.java) { strict.inspect(value) }
        }
    }

    @Test fun specialSourceAddressesAndBadBufferLimitsAreRejected() = runBlocking {
        for (ip in listOf("127.0.0.1", "10.1.2.3", "169.254.169.254", "100.100.100.200", "192.168.1.1", "::1", "fc00::1", "fe80::1")) {
            assertFalse(ip, publicSourceAddress(InetAddress.getByName(ip)))
        }
        assertTrue(publicSourceAddress(InetAddress.getByName("8.8.8.8")))
        val destination = Destination()
        try {
            UrlTransferEngine(source).run(info(), null, 51, 262144, destination, destination::checkpoint)
            fail("connections must be capped")
        } catch (_: IllegalArgumentException) { }
        try {
            UrlTransferEngine(source).run(info(), null, 1, Int.MAX_VALUE, destination, destination::checkpoint)
            fail("buffer must be bounded")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, destination.creates)
    }
}
