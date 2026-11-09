package com.cloudfusion.app

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class DriveClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: DriveClient

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        val origin = server.url("/")
        api = DriveClient(origin, { it.host == origin.host && it.port == origin.port },
            OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).readTimeout(5, TimeUnit.SECONDS).build())
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun uploadUsesSessionProbeAndCorrectRanges() {
        val location = server.url("/upload/drive/v3/files?upload_id=test").toString()
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Location", location))
        server.enqueue(MockResponse().setResponseCode(308))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"done","size":"5","md5Checksum":"abc"}"""))
        val session = api.startUpload("test-token", "hello.txt", "text/plain", 5)
        assertEquals(location, session)
        assertEquals(0, (api.probeUpload("test-token", session, 5) as UploadReply.Incomplete).offset)
        val result = api.sendChunk("test-token", session, 0, 5, "text/plain", "hello".toByteArray()) as UploadReply.Complete
        assertEquals("done", result.file.getString("id"))
        val initiate = server.takeRequest()
        assertEquals("POST", initiate.method)
        assertEquals("resumable", initiate.requestUrl!!.queryParameter("uploadType"))
        assertEquals("Bearer test-token", initiate.getHeader("Authorization"))
        assertEquals("5", initiate.getHeader("X-Upload-Content-Length"))
        val probe = server.takeRequest(); assertEquals("bytes */5", probe.getHeader("Content-Range"))
        val chunk = server.takeRequest(); assertEquals("bytes 0-4/5", chunk.getHeader("Content-Range"))
        assertEquals("hello", chunk.body.readUtf8())
    }

    @Test fun serverAcknowledgementAndExpiredSessionAreNotGuessed() {
        val location = server.url("/session").toString()
        server.enqueue(MockResponse().setResponseCode(308).addHeader("Range", "bytes=0-9"))
        assertEquals(10, (api.probeUpload("t", location, 100) as UploadReply.Incomplete).offset)
        server.enqueue(MockResponse().setResponseCode(404))
        val e = assertThrows(DriveException::class.java) { api.probeUpload("t", location, 100) }
        assertEquals(404, e.code)
        assertFalse(e.message!!.contains(location))
    }

    @Test fun neverSendTokenToUnexpectedUploadHost() {
        assertTrue(DriveClient.isTrustedGoogleSession("https://www.googleapis.com/upload/drive/v3/files?upload_id=x".toHttpUrl()))
        assertFalse(DriveClient.isTrustedGoogleSession("http://www.googleapis.com/upload/drive/v3/files".toHttpUrl()))
        assertFalse(DriveClient.isTrustedGoogleSession("https://evil.example/upload/drive/v3/files".toHttpUrl()))
        assertFalse(DriveClient.isTrustedGoogleSession("https://www.googleapis.com/drive/v3/files".toHttpUrl()))
        val strict = DriveClient(server.url("/"))
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Location", "https://evil.example/upload/drive/v3/files?upload_id=secret"))
        assertThrows(IllegalArgumentException::class.java) { strict.startUpload("secret-token", "a", "text/plain", 1) }
        assertEquals(1, server.requestCount)
    }

    @Test fun copyAcceptsOnlyExactBoundedRange() {
        server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 2-4/8").setBody("cde"))
        assertEquals("cde", String(api.downloadRange("t", "file-id", 2, 4, 8)))
        val request = server.takeRequest()
        assertEquals("bytes=2-4", request.getHeader("Range"))
        assertEquals("identity", request.getHeader("Accept-Encoding"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("abcdefgh"))
        assertThrows(IllegalStateException::class.java) { api.downloadRange("t", "file-id", 2, 4, 8) }
    }

    @Test fun listSendsEscapedQueryAndRealPageToken() {
        server.enqueue(MockResponse().setBody("""{"files":[],"nextPageToken":"next"}"""))
        val page = api.listFiles("t", "O'Brien", "folders", "modified", "desc", "page + 1")
        assertEquals("next", page.getString("nextPageToken"))
        val req = server.takeRequest().requestUrl!!
        assertEquals("page + 1", req.queryParameter("pageToken"))
        assertTrue(req.queryParameter("q")!!.contains("O\\'Brien"))
        assertEquals("modifiedTime desc", req.queryParameter("orderBy"))
    }
}
