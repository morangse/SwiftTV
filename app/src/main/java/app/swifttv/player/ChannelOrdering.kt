package app.swifttv.player

/** Sort numbered CCTV stations while preserving every satellite/custom channel's position. */
object ChannelOrdering {
    val numberedCctvIds: List<String> = (1..17).flatMap { number ->
        if (number == 5) listOf("cctv-5", "cctv-5+") else listOf("cctv-$number")
    }
    private val numberedRank = numberedCctvIds.withIndex().associate { it.value to it.index }

    fun order(channels: List<Channel>): List<Channel> {
        val numbered = channels.filter { it.id in numberedRank }
        if (numbered.size < 2) return channels
        val sorted = numbered.sortedBy { numberedRank.getValue(it.id) }.iterator()
        return channels.map { if (it.id in numberedRank) sorted.next() else it }
    }
}
