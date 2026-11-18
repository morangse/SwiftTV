package app.swifttv.player

/** UI-only navigation state. Selection never submits a playback request. */
class SidebarSelection(initialGroup: String = "全部") {
    var group: String = initialGroup
        private set
    var channels: List<Channel> = emptyList()
        private set
    var favorites: Set<String> = emptySet()
        private set
    private val remembered = mutableMapOf<String, String>()
    val groups: List<String> get() = listOf("全部", "收藏") + channels.map { it.group }.filter { it.isNotBlank() && it !in listOf("全部", "收藏") }.distinct()
    val shown: List<Channel> get() = channels.filter { group == "全部" || (group == "收藏" && it.id in favorites) || (group != "收藏" && it.group == group) }
    val selectedId: String? get() = remembered[group]?.takeIf { id -> shown.any { it.id == id } } ?: shown.firstOrNull()?.id
    fun update(channels: List<Channel>, favorites: Set<String>) {
        this.channels = channels; this.favorites = favorites
        if (group !in groups) group = "全部"
    }
    fun open(playingId: String?) {
        if (playingId != null && shown.none { it.id == playingId }) group = "全部"
        if (playingId != null && shown.any { it.id == playingId }) remembered[group] = playingId
    }
    fun selectGroup(value: String) { if (value in groups) group = value }
    fun focus(id: String) { if (shown.any { it.id == id }) remembered[group] = id }
    fun selected(): Channel? = shown.firstOrNull { it.id == selectedId }
}

enum class ChannelPlaybackMark { NONE, CONNECTING, PLAYING, BUFFERING, FAILED }
