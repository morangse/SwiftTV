package app.swifttv.player

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Desktop integration harness: real HTTP receiver/parser + in-memory persistence, never Android performance evidence. */
object PairingPreviewMain {
    @JvmStatic fun main(args: Array<String>) {
        val network = Network(null)
        var pending: ParsedPlaylist? = null
        var previewId = ""
        val server = ImportServer("127.0.0.1", File("app/src/main/assets/import.html").readText(), { data ->
            val body = if (data.optString("url").isNotBlank()) network.download(data.getString("url")).text else data.getString("content")
            val parsed = M3uParser.parse(body)
            pending = parsed; previewId = java.util.UUID.randomUUID().toString()
            JSONObject().put("previewId", previewId).put("channelCount", parsed.entries.map { it.channelId }.distinct().size)
                .put("sourceCount", parsed.entries.size).put("skipped", parsed.skipped)
                .put("channels", JSONArray(parsed.entries.map { it.name }.distinct().take(10)))
        }, { data ->
            require(data.getString("previewId") == previewId && pending != null)
            JSONObject().put("channelCount", pending!!.entries.map { it.channelId }.distinct().size)
        })
        println("PAIRING_URL=" + server.pairingUrl)
        Runtime.getRuntime().addShutdownHook(Thread { server.close(); network.close() })
        Thread.sleep(300000)
        server.close(); network.close()
    }
}
