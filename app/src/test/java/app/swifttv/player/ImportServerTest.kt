package app.swifttv.player

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

class ImportServerTest {
    private fun fixture() = ImportServer("127.0.0.1", "<!doctype html>SwiftTV", { JSONObject().put("previewId", "ready") },
        { require(it.getString("previewId") == "ready"); JSONObject().put("saved", true) })
    @Test fun pairingTokenLivesOnlyInFragmentAndIsRequiredForApi() {
        fixture().use { server ->
            val client = OkHttpClient()
            assertTrue(server.pairingUrl.contains("/#token="))
            client.newCall(Request.Builder().url(server.address).build()).execute().use { assertEquals(200, it.code); assertFalse(it.body!!.string().contains(server.token)) }
            val body = "{}".toRequestBody("application/json".toMediaType())
            client.newCall(Request.Builder().url(server.address + "/api/import").post(body).build()).execute().use { assertEquals(403, it.code) }
            client.newCall(Request.Builder().url(server.address + "/api/import").header("X-SwiftTV-Token", server.token).post(body).build())
                .execute().use { assertEquals(200, it.code); assertEquals("ready", JSONObject(it.body!!.string()).getString("previewId")) }
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
    @Test fun rejectsCrossOriginCommitAndInvalidPreview() {
        fixture().use { server ->
            val client = OkHttpClient()
            val body = """{"previewId":"ready"}""".toRequestBody("application/json".toMediaType())
            client.newCall(Request.Builder().url(server.address + "/api/commit").header("X-SwiftTV-Token", server.token)
                .header("Origin", "https://untrusted.example").post(body).build()).execute().use { assertEquals(403, it.code) }
            client.newCall(Request.Builder().url(server.address + "/api/commit").header("X-SwiftTV-Token", server.token)
                .post("""{"previewId":"wrong"}""".toRequestBody("application/json".toMediaType())).build()).execute().use { assertEquals(400, it.code) }
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
    @Test fun validPreviewCanBeCommitted() {
        fixture().use { server ->
            val client = OkHttpClient()
            val body = """{"previewId":"ready"}""".toRequestBody("application/json".toMediaType())
            client.newCall(Request.Builder().url(server.address + "/api/commit").header("X-SwiftTV-Token", server.token)
                .header("Origin", server.address).post(body).build()).execute().use {
                assertEquals(200, it.code); assertTrue(JSONObject(it.body!!.string()).getBoolean("saved"))
            }
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
    @Test fun oversizedHeadersAreBounded() {
        fixture().use { server ->
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: 127.0.0.1:" + server.port + "\r\nX-Large: " + "a".repeat(18000) + "\r\n\r\n").toByteArray())
                assertTrue(socket.getInputStream().bufferedReader().readLine().contains("400"))
            }
        }
    }
    @Test fun diagnosticSessionRequiresTokenAndDisablesImports() {
        ImportServer("127.0.0.1", "Export", { JSONObject() }, { JSONObject() }, """{"redacted":true}""").use { server ->
            val client = OkHttpClient()
            client.newCall(Request.Builder().url(server.address + "/api/diagnostics").build()).execute().use { assertEquals(403, it.code) }
            client.newCall(Request.Builder().url(server.address + "/api/diagnostics").header("X-SwiftTV-Token", server.token).build()).execute().use { assertEquals(200, it.code) }
            client.newCall(Request.Builder().url(server.address + "/api/import").header("X-SwiftTV-Token", server.token)
                .post("{}".toRequestBody("application/json".toMediaType())).build()).execute().use { assertEquals(404, it.code) }
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
    @Test fun companionConsoleStateAndManageEndpointsRequireTokenAndOrigin() {
        ImportServer(
            "127.0.0.1",
            "Console",
            { JSONObject() },
            { JSONObject() },
            diagnostics = null,
            stateRequest = { JSONObject().put("customEpgOrigin", "https://epg.example/…#123") },
            manageRequest = { data -> JSONObject().put("handled", data.getString("action")) }
        ).use { server ->
            val client = OkHttpClient()
            client.newCall(Request.Builder().url(server.address + "/api/state").build()).execute().use { assertEquals(403, it.code) }
            client.newCall(Request.Builder().url(server.address + "/api/state").header("X-SwiftTV-Token", server.token).build()).execute().use {
                assertEquals(200, it.code)
                assertTrue(it.body!!.string().contains("epg.example"))
            }
            val payload = """{"action":"refresh"}""".toRequestBody("application/json".toMediaType())
            client.newCall(Request.Builder().url(server.address + "/api/manage").header("X-SwiftTV-Token", server.token)
                .header("Origin", "https://evil.example").post(payload).build()).execute().use { assertEquals(403, it.code) }
            client.newCall(Request.Builder().url(server.address + "/api/manage").header("X-SwiftTV-Token", server.token)
                .header("Origin", server.address).post(payload).build()).execute().use {
                assertEquals(200, it.code)
                assertEquals("refresh", JSONObject(it.body!!.string()).getString("handled"))
            }
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
    @Test fun closedSessionStopsAcceptingConnections() {
        val server = fixture(); val port = server.port
        server.close()
        assertTrue(runCatching { Socket("127.0.0.1", port).close() }.isFailure)
    }
}
