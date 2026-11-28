package app.swifttv.player

import okhttp3.OkHttpClient
import okhttp3.Request
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
            client.newCall(Request.Builder().url(server.address).build()).execute().use {
                assertEquals(200, OkHttpCompat.code(it))
                assertFalse(OkHttpCompat.body(it)!!.string().contains(server.token))
            }
            val body = OkHttpCompat.jsonBody("{}")
            client.newCall(Request.Builder().url(server.address + "/api/import").post(body).build()).execute().use { assertEquals(403, OkHttpCompat.code(it)) }
            client.newCall(Request.Builder().url(server.address + "/api/import").header("X-SwiftTV-Token", server.token).post(body).build())
                .execute().use {
                    assertEquals(200, OkHttpCompat.code(it))
                    assertEquals("ready", JSONObject(OkHttpCompat.body(it)!!.string()).getString("previewId"))
                }
            OkHttpCompat.shutdown(client)
        }
    }
    @Test fun rejectsCrossOriginCommitAndInvalidPreview() {
        fixture().use { server ->
            val client = OkHttpClient()
            val body = OkHttpCompat.jsonBody("""{"previewId":"ready"}""")
            client.newCall(Request.Builder().url(server.address + "/api/commit").header("X-SwiftTV-Token", server.token)
                .header("Origin", "https://untrusted.example").post(body).build()).execute().use { assertEquals(403, OkHttpCompat.code(it)) }
            client.newCall(Request.Builder().url(server.address + "/api/commit").header("X-SwiftTV-Token", server.token)
                .post(OkHttpCompat.jsonBody("""{"previewId":"wrong"}""")).build()).execute().use { assertEquals(400, OkHttpCompat.code(it)) }
            OkHttpCompat.shutdown(client)
        }
    }
    @Test fun validPreviewCanBeCommitted() {
        fixture().use { server ->
            val client = OkHttpClient()
            val body = OkHttpCompat.jsonBody("""{"previewId":"ready"}""")
            client.newCall(Request.Builder().url(server.address + "/api/commit").header("X-SwiftTV-Token", server.token)
                .header("Origin", server.address).post(body).build()).execute().use {
                assertEquals(200, OkHttpCompat.code(it))
                assertTrue(JSONObject(OkHttpCompat.body(it)!!.string()).getBoolean("saved"))
            }
            OkHttpCompat.shutdown(client)
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
            client.newCall(Request.Builder().url(server.address + "/api/diagnostics").build()).execute().use { assertEquals(403, OkHttpCompat.code(it)) }
            client.newCall(Request.Builder().url(server.address + "/api/diagnostics").header("X-SwiftTV-Token", server.token).build()).execute().use { assertEquals(200, OkHttpCompat.code(it)) }
            client.newCall(Request.Builder().url(server.address + "/api/import").header("X-SwiftTV-Token", server.token)
                .post(OkHttpCompat.jsonBody("{}")).build()).execute().use { assertEquals(404, OkHttpCompat.code(it)) }
            OkHttpCompat.shutdown(client)
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
            client.newCall(Request.Builder().url(server.address + "/api/state").build()).execute().use { assertEquals(403, OkHttpCompat.code(it)) }
            client.newCall(Request.Builder().url(server.address + "/api/state").header("X-SwiftTV-Token", server.token).build()).execute().use {
                assertEquals(200, OkHttpCompat.code(it))
                assertTrue(OkHttpCompat.body(it)!!.string().contains("epg.example"))
            }
            val payload = OkHttpCompat.jsonBody("""{"action":"refresh"}""")
            client.newCall(Request.Builder().url(server.address + "/api/manage").header("X-SwiftTV-Token", server.token)
                .header("Origin", "https://evil.example").post(payload).build()).execute().use { assertEquals(403, OkHttpCompat.code(it)) }
            client.newCall(Request.Builder().url(server.address + "/api/manage").header("X-SwiftTV-Token", server.token)
                .header("Origin", server.address).post(payload).build()).execute().use {
                assertEquals(200, OkHttpCompat.code(it))
                assertEquals("refresh", JSONObject(OkHttpCompat.body(it)!!.string()).getString("handled"))
            }
            OkHttpCompat.shutdown(client)
        }
    }
    @Test fun closedSessionStopsAcceptingConnections() {
        val server = fixture(); val port = server.port
        server.close()
        assertTrue(runCatching { Socket("127.0.0.1", port).close() }.isFailure)
    }

    @Test fun importConsoleReturnsFriendlyTlsErrorWithRelayEligibleFlagAndAllowsHttpsConnectSrc() {
        ImportServer(
            "127.0.0.1",
            "<!doctype html>SwiftTV",
            {
                throw javax.net.ssl.SSLHandshakeException("Chain validation failed").apply {
                    initCause(java.security.cert.CertPathValidatorException("Trust anchor for certification path not found."))
                }
            },
            { JSONObject().put("saved", true) }
        ).use { server ->
            val client = OkHttpClient()
            client.newCall(Request.Builder().url(server.address).build()).execute().use { resp ->
                assertEquals(200, OkHttpCompat.code(resp))
                val csp = resp.header("Content-Security-Policy").orEmpty()
                assertTrue(csp.contains("connect-src 'self' https: http:"))
            }
            val body = OkHttpCompat.jsonBody("""{"url":"https://example.com/list.m3u"}""")
            client.newCall(
                Request.Builder().url(server.address + "/api/import")
                    .header("X-SwiftTV-Token", server.token)
                    .post(body)
                    .build()
            ).execute().use { resp ->
                assertEquals(400, OkHttpCompat.code(resp))
                val json = JSONObject(OkHttpCompat.body(resp)!!.string())
                assertTrue(json.getBoolean("relayEligible"))
                assertTrue(json.getString("error").contains("证书校验失败"))
                assertFalse(json.getString("error").contains("CertPathValidatorException"))
            }
            OkHttpCompat.shutdown(client)
        }

        ImportServer("127.0.0.1", "Export", { JSONObject() }, { JSONObject() }, """{"redacted":true}""").use { server ->
            val client = OkHttpClient()
            client.newCall(Request.Builder().url(server.address).build()).execute().use { resp ->
                assertEquals(200, OkHttpCompat.code(resp))
                val csp = resp.header("Content-Security-Policy").orEmpty()
                assertTrue(csp.contains("connect-src 'self';"))
                assertFalse(csp.contains("https: http:"))
            }
            OkHttpCompat.shutdown(client)
        }
    }
}
