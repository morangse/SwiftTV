package app.swifttv.player

import org.json.JSONArray
import org.json.JSONObject

/** Builds an export without Android state or clocks so provenance and redaction stay testable. */
object CatalogDiagnostics {
    fun create(state: CatalogState, device: String, appVersion: String, routingPreference: String, now: Long): String {
        val subscriptions = state.subscriptions.associateBy { it.id }
        val sources = state.entries.groupBy { it.url }.map { (_, rows) ->
            val entry = rows.first()
            val stat = state.stats[sourceId(entry.url)] ?: SourceStat()
            val providers = rows.map { row ->
                DiagnosticRedaction.message(subscriptions[row.subscriptionId]?.name ?: "本地频道表")
            }.distinct()
            JSONObject().put("channel", DiagnosticRedaction.message(entry.name))
                .put("origin", DiagnosticRedaction.origin(entry.url))
                .put("routeGroup", SourceRoutes.group(entry)).put("providers", JSONArray(providers))
                .put("attempts", stat.attempts).put("successes", stat.successes).put("firstFrameMs", stat.firstFrameMs)
                .put("rebufferMs", stat.rebufferMs).put("playedMs", stat.playedMs).put("checkedAt", stat.checkedAt)
                .put("width", stat.width).put("height", stat.height).put("codec", stat.codec)
                .put("audioStarted", stat.audioStarted).put("audioCodec", stat.audioCodec)
                .put("failures", stat.failures).put("cancelled", stat.cancelled).put("lastOutcome", stat.lastOutcome)
                .put("error", DiagnosticRedaction.message(stat.error)).put("health", SourceHealth.describe(stat))
        }
        return JSONObject().put("schemaVersion", 4).put("appVersion", appVersion)
            .put("device", device).put("exportedAt", now)
            .put("routingPreference", routingPreference).put("sources", JSONArray(sources)).toString(2)
    }
}
