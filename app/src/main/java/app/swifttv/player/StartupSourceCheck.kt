package app.swifttv.player

enum class StartupSourceIssue { OFFLINE, REGIONAL_NO_PICTURE, NO_PICTURE }

/** A single check per automatic startup tune, independent of individual failover attempts. */
internal class StartupSourceCheck(private val startedAt: Long) {
    private var attempted = false
    private var regional = false
    private var reportedOffline = false
    private var complete = false

    fun attempting(isRegional: Boolean) {
        attempted = true
        regional = regional || isRegional
    }

    fun check(now: Long, exhausted: Boolean, online: Boolean): StartupSourceIssue? {
        if (complete || !attempted || (!exhausted && now - startedAt < 15_000)) return null
        if (!online) {
            if (reportedOffline) return null
            reportedOffline = true
            return StartupSourceIssue.OFFLINE
        }
        complete = true
        return if (regional) StartupSourceIssue.REGIONAL_NO_PICTURE else StartupSourceIssue.NO_PICTURE
    }
}
