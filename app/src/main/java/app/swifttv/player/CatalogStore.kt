package app.swifttv.player

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class CatalogStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "catalog-v1.json"))
    @Volatile private var state = read()
    @Synchronized fun snapshot(): CatalogState = state
    @Synchronized fun replaceSubscription(subscription: Subscription, parsed: ParsedPlaylist) {
        val resolvedSub = if (subscription.epgUrl.isNotBlank() || parsed.epgUrls.isEmpty()) subscription
            else subscription.copy(epgUrl = parsed.epgUrls.first())
        val fresh = parsed.entries.map { it.copy(subscriptionId = resolvedSub.id) }
        val next = state.copy(
            entries = state.entries.filterNot { it.subscriptionId == resolvedSub.id } + fresh,
            subscriptions = state.subscriptions.filterNot { it.id == resolvedSub.id } + resolvedSub
        )
        persist(next)
    }
    @Synchronized fun installBuiltins(subscription: Subscription, parsed: ParsedPlaylist, revision: Int) {
        persist(BuiltinPolicy.migrate(state, subscription, parsed, revision))
    }
    @Synchronized fun removeSubscription(id: String) {
        persist(state.copy(entries = state.entries.filterNot { it.subscriptionId == id },
            subscriptions = state.subscriptions.filterNot { it.id == id }))
    }
    @Synchronized fun touchSubscription(id: String, at: Long) {
        persist(state.copy(subscriptions = state.subscriptions.map { if (it.id == id) it.copy(updatedAt = at) else it }))
    }
    @Synchronized fun setCustomEpgUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isNotEmpty()) httpUri(trimmed)
        if (state.customEpgUrl != trimmed) persist(state.copy(customEpgUrl = trimmed))
    }
    @Synchronized fun favorite(id: String): Boolean {
        val next = state.favorites.toMutableSet()
        val added = if (id in next) { next.remove(id); false } else { next.add(id); true }
        persist(state.copy(favorites = next))
        return added
    }
    @Synchronized fun remember(id: String) { if (state.lastChannel != id) persist(state.copy(lastChannel = id)) }
    @Synchronized fun record(url: String, transform: (SourceStat) -> SourceStat) {
        val key = sourceId(url)
        persist(state.copy(stats = state.stats + (key to transform(state.stats[key] ?: SourceStat()))))
    }
    @Synchronized fun clearCooldown(url: String) { record(url) { it.copy(cooldownUntil = 0) } }
    @Synchronized fun diagnostics(device: String, routingPreference: String = "unknown"): String =
        CatalogDiagnostics.create(state, device, BuildConfig.VERSION_NAME, routingPreference, System.currentTimeMillis())
    private fun read(): CatalogState = try {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) CatalogState()
        else decode(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }).let { loaded ->
            loaded.copy(stats = loaded.stats.mapValues { (_, s) -> if (s.lastOutcome == "connecting") s.copy(lastOutcome = "interrupted") else s })
        }
    } catch (_: Exception) { CatalogState() }

    private fun persist(next: CatalogState) {
        val stream = file.startWrite()
        try {
            stream.write(encode(next).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            state = next
        } catch (error: Exception) { file.failWrite(stream); throw error }
    }
    companion object {
        fun encode(s: CatalogState): String = JSONObject().put("version", 1).put("builtinRevision", s.builtinRevision)
            .put("customEpgUrl", s.customEpgUrl)
            .put("entries", JSONArray(s.entries.map { e -> JSONObject()
                .put("id", e.channelId).put("name", e.name).put("group", e.group).put("url", e.url)
                .put("subscriptionId", e.subscriptionId).put("profile", e.profile).put("logo", e.logoUrl)
                .put("headers", JSONObject(e.headers)) }))
            .put("subscriptions", JSONArray(s.subscriptions.map { sub -> JSONObject().put("id", sub.id)
                .put("name", sub.name).put("url", sub.url).put("updatedAt", sub.updatedAt)
                .put("etag", sub.etag).put("lastModified", sub.lastModified).put("epgUrl", sub.epgUrl) }))
            .put("favorites", JSONArray(s.favorites.toList())).put("lastChannel", s.lastChannel)
            .put("stats", JSONObject().apply { s.stats.forEach { (key, x) -> put(key, JSONObject()
                .put("attempts", x.attempts).put("successes", x.successes).put("firstFrameMs", x.firstFrameMs)
                .put("rebufferMs", x.rebufferMs).put("playedMs", x.playedMs).put("checkedAt", x.checkedAt)
                .put("error", x.error).put("cooldownUntil", x.cooldownUntil)
                .put("width", x.width).put("height", x.height).put("codec", x.codec).put("audioStarted", x.audioStarted)
                .put("failures", x.failures).put("cancelled", x.cancelled).put("lastOutcome", x.lastOutcome)) } }).toString()

        fun decode(text: String): CatalogState {
            val root = JSONObject(text)
            require(root.getInt("version") == 1) { "不支持的数据版本" }
            val entries = root.getJSONArray("entries").objects().map { e ->
                val headers = e.optJSONObject("headers") ?: JSONObject()
                Entry(e.getString("id"), e.getString("name"), e.getString("group"), e.getString("url"),
                    headers.keys().asSequence().associateWith { headers.getString(it) }, e.getString("subscriptionId"),
                    e.optString("profile"), e.optString("logo"))
            }
            val subs = root.getJSONArray("subscriptions").objects().map { e ->
                Subscription(e.getString("id"), e.getString("name"), e.getString("url"), e.getLong("updatedAt"),
                    e.optString("etag"), e.optString("lastModified"), e.optString("epgUrl"))
            }
            val stats = root.optJSONObject("stats") ?: JSONObject()
            return CatalogState(entries, subs,
                root.getJSONArray("favorites").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },
                root.optString("lastChannel"), stats.keys().asSequence().associateWith { key ->
                    val x = stats.getJSONObject(key)
                    SourceStat(x.optInt("attempts"), x.optInt("successes"), x.optLong("firstFrameMs"),
                        x.optLong("rebufferMs"), x.optLong("playedMs"), x.optLong("checkedAt"),
                        x.optString("error"), x.optLong("cooldownUntil"), x.optInt("width"), x.optInt("height"), x.optString("codec"), x.optBoolean("audioStarted"),
                        x.optInt("failures", if (x.optString("error").isNotEmpty()) maxOf(1, x.optInt("attempts") - x.optInt("successes")) else 0),
                        x.optInt("cancelled", if (x.optString("error").isEmpty()) maxOf(0, x.optInt("attempts") - x.optInt("successes")) else 0),
                        x.optString("lastOutcome"))
                }, root.optInt("builtinRevision"), root.optString("customEpgUrl"))
        }
        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    }
}
