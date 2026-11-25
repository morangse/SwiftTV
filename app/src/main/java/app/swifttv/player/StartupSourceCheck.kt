package app.swifttv.player

enum class StartupSourceIssue { OFFLINE, NO_PICTURE }

/** A single check per automatic startup tune, independent of individual failover attempts. */
internal class StartupSourceCheck(private val startedAt: Long) {
    private var attempted = false
    private var reportedOffline = false
    private var complete = false

    fun attempting() {
        attempted = true
    }

    fun check(now: Long, exhausted: Boolean, online: Boolean): StartupSourceIssue? {
        if (complete || !attempted || (!exhausted && now - startedAt < 12_000)) return null
        if (!online) {
            if (reportedOffline) return null
            reportedOffline = true
            return StartupSourceIssue.OFFLINE
        }
        complete = true
        return StartupSourceIssue.NO_PICTURE
    }
}
