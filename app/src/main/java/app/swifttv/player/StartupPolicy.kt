package app.swifttv.player

/** Bounded launch windows: no wall clock timers, Home interception, or endless revival loops. */
data class StartupState(
    val bootCount: Int = -1, val bootEpoch: Long = 0, val eventAt: Long = 0,
    val requestId: String = "", val reason: String = "", val until: Long = 0,
    val attempts: Int = 0, val lastAttemptAt: Long = -30000,
    val watching: Boolean = false, val suppressed: Boolean = false, val screenOff: Boolean = false,
    val reviveAt: Long = 0, val reviveCount: Int = 0
)
object StartupPolicy {
    const val DEFAULT_ENABLED = true
    const val WINDOW_MS = 90000L
    const val RETRY_MS = 15000L
    const val MAX_ATTEMPTS = 3
    const val REVIVE_WINDOW_MS = 600000L
    const val MAX_REVIVES = 3
    fun sameBoot(s: StartupState, count: Int, epoch: Long, now: Long): Boolean =
        s.eventAt > 0 && now >= s.eventAt && if (count >= 0 && s.bootCount >= 0) count == s.bootCount
        else kotlin.math.abs(epoch - s.bootEpoch) <= 120000
    fun provenNewBoot(s: StartupState, count: Int, now: Long): Boolean = s.eventAt == 0L ||
        (count >= 0 && s.bootCount >= 0 && count != s.bootCount) || now < s.eventAt
    fun acceptsAutomaticEntry(s: StartupState, enabled: Boolean, token: String, now: Long): Boolean =
        enabled && token.isNotEmpty() && token == s.requestId && !s.suppressed && !s.screenOff &&
        now >= s.eventAt && now <= s.until
    fun begin(s: StartupState, reason: String, now: Long, token: String): StartupState {
        if (reason == "revive" && (!s.watching || s.suppressed || s.screenOff)) return s
        if (s.requestId.isNotEmpty() && now <= s.until) return s
        val recent = s.reviveCount > 0 && now >= s.reviveAt && now - s.reviveAt < REVIVE_WINDOW_MS
        if (reason == "revive" && recent && s.reviveCount >= MAX_REVIVES) return s
        return s.copy(requestId = token, reason = reason, until = now + WINDOW_MS, attempts = 0,
            lastAttemptAt = -30000, suppressed = false, screenOff = false, eventAt = now,
            reviveAt = if (reason == "revive" && recent) s.reviveAt else now,
            reviveCount = if (reason == "revive") (if (recent) s.reviveCount else 0) + 1 else 0)
    }
    fun canAttempt(s: StartupState, enabled: Boolean, interactive: Boolean, locked: Boolean,
        visible: Boolean, now: Long, token: String): Boolean = enabled && token.isNotEmpty() &&
        token == s.requestId && !s.suppressed && !s.screenOff && interactive && !locked && !visible &&
        now >= s.eventAt && now <= s.until && s.attempts < MAX_ATTEMPTS &&
        now - s.lastAttemptAt >= RETRY_MS
    fun attempted(s: StartupState, now: Long) = s.copy(attempts = s.attempts + 1, lastAttemptAt = now, eventAt = now)
    fun left(s: StartupState, now: Long) = s.copy(watching = false, suppressed = true, requestId = "", until = 0, eventAt = now)
    fun visible(s: StartupState, now: Long) = s.copy(watching = true, suppressed = false, screenOff = false, eventAt = now)
    fun screenOff(s: StartupState, now: Long) = s.copy(screenOff = true, requestId = "", until = 0, eventAt = now)
    fun wake(s: StartupState, now: Long, token: String) = if (s.screenOff) begin(s, "wake", now, token) else s
}
