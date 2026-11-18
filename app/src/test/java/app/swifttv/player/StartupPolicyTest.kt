package app.swifttv.player

import org.junit.Assert.*
import org.junit.Test

/** Pure state-machine regressions; these do not launch Android components or contact a TV. */
class StartupPolicyTest {
    private val time = 100_000L
    private fun boot(token: String = "boot-1", at: Long = time) = StartupPolicy.begin(
        StartupState(bootCount = 7, bootEpoch = 1_800_000_000_000L), "boot", at, token)
    private fun allowed(s: StartupState, at: Long = time, token: String = s.requestId,
        enabled: Boolean = true, interactive: Boolean = true, locked: Boolean = false,
        visible: Boolean = false) = StartupPolicy.canAttempt(s, enabled, interactive, locked, visible, at, token)

    @Test fun enabledStartupCanLaunchAndDisabledStartupCannot() {
        val pending = boot()
        assertTrue(StartupPolicy.DEFAULT_ENABLED)
        assertTrue(allowed(pending, enabled = StartupPolicy.DEFAULT_ENABLED))
        assertFalse(allowed(pending, enabled = false))
        // Runtime owns the default-enabled preference; policy still requires its explicit value.
        assertFalse(allowed(pending, enabled = false, at = pending.until))
    }

    @Test fun emptyOrForeignTokensNeverLaunch() {
        val pending = boot()
        assertFalse(allowed(pending, token = ""))
        assertFalse(allowed(pending, token = "previous-process-token"))
        assertTrue(allowed(pending, token = "boot-1"))
    }

    @Test fun duplicateBootstrapDoesNotReplaceTokenExtendWindowOrResetAttempts() {
        val once = StartupPolicy.attempted(boot(), time)
        val duplicate = StartupPolicy.begin(once, "boot", time + 20_000, "duplicate-token")
        assertEquals(once, duplicate)
        assertEquals(time + StartupPolicy.WINDOW_MS, duplicate.until)
        assertEquals(1, duplicate.attempts)
    }

    @Test fun reconnectDuringActiveWindowDoesNotAllocateAnotherRevival() {
        val first = StartupPolicy.begin(StartupState(watching = true), "revive", time, "revive-1")
        val duplicate = StartupPolicy.begin(first, "revive", time + 20_000, "revive-duplicate")
        assertEquals(first, duplicate)
        assertEquals(1, duplicate.reviveCount)
    }

    @Test fun firstAttemptRequiresInteractiveUnlockedAndHiddenActivity() {
        val pending = boot()
        assertFalse(allowed(pending, interactive = false))
        assertFalse(allowed(pending, locked = true))
        assertFalse(allowed(pending, visible = true))
        assertTrue(allowed(pending))
    }

    @Test fun temporaryLockDoesNotConsumeOrInvalidatePendingToken() {
        val pending = boot()
        assertFalse(allowed(pending, locked = true))
        assertEquals("boot-1", pending.requestId)
        assertEquals(0, pending.attempts)
        assertTrue(allowed(pending, at = time + 3_000, locked = false))
    }

    @Test fun screenOffAlwaysBlocksAnOtherwiseValidPendingToken() {
        val pending = boot().copy(screenOff = true)
        assertFalse(allowed(pending))
    }

    @Test fun attemptsHaveSpacingAndStopAfterThree() {
        var state = boot()
        assertTrue(allowed(state))
        state = StartupPolicy.attempted(state, time)
        assertFalse(allowed(state, at = time + StartupPolicy.RETRY_MS - 1))
        assertTrue(allowed(state, at = time + StartupPolicy.RETRY_MS))
        state = StartupPolicy.attempted(state, time + StartupPolicy.RETRY_MS)
        assertTrue(allowed(state, at = time + 2 * StartupPolicy.RETRY_MS))
        state = StartupPolicy.attempted(state, time + 2 * StartupPolicy.RETRY_MS)
        assertEquals(StartupPolicy.MAX_ATTEMPTS, state.attempts)
        assertFalse(allowed(state, at = time + 3 * StartupPolicy.RETRY_MS))
        assertFalse(allowed(state, at = state.until))
    }

    @Test fun recordingAttemptNeverExtendsOriginalDeadline() {
        val pending = boot()
        val attempted = StartupPolicy.attempted(pending, time + 20_000)
        assertEquals(pending.until, attempted.until)
        assertEquals(pending.requestId, attempted.requestId)
        assertEquals(time + 20_000, attempted.lastAttemptAt)
    }

    @Test fun delayedOrTimeReversedAttemptsCannotLaunch() {
        val pending = boot()
        assertFalse(allowed(pending, at = time - 1))
        assertTrue(allowed(pending, at = pending.until))
        assertFalse(allowed(pending, at = pending.until + 1))
    }

    @Test fun userDepartureInvalidatesEveryPendingRetry() {
        val pending = boot()
        val departed = StartupPolicy.left(pending, time + 1_000)
        assertTrue(departed.suppressed)
        assertFalse(departed.watching)
        assertEquals("", departed.requestId)
        assertEquals(0L, departed.until)
        assertFalse(allowed(departed, at = time + 20_000, token = pending.requestId))
    }

    @Test fun departedOrNeverVisibleStateCannotReviveOnServiceReconnect() {
        val initial = StartupState()
        assertEquals(initial, StartupPolicy.begin(initial, "revive", time, "forbidden"))
        val departed = StartupPolicy.left(StartupPolicy.visible(boot(), time), time + 1_000)
        assertEquals(departed, StartupPolicy.begin(departed, "revive", time + 2_000, "forbidden"))
    }

    @Test fun bareScreenOnOrUserPresentCannotUndoHomeSuppression() {
        val departed = StartupPolicy.left(StartupPolicy.visible(boot(), time), time + 1_000)
        assertEquals(departed, StartupPolicy.wake(departed, time + 2_000, "bare-screen-on"))
        assertEquals(departed, StartupPolicy.wake(departed, time + 3_000, "user-present"))
        assertFalse(allowed(departed, at = time + 3_000, token = "bare-screen-on"))
    }

    @Test fun duplicateWakeDoesNotReplaceTokenOrExtendDeadline() {
        val off = StartupPolicy.screenOff(boot(), time + 1_000)
        val awake = StartupPolicy.wake(off, time + 2_000, "wake-1")
        assertEquals(awake, StartupPolicy.wake(awake, time + 3_000, "wake-2"))
        assertEquals(awake, StartupPolicy.wake(awake, time + 4_000, "user-present"))
    }

    @Test fun realOffOnCycleRearmsAfterUserDeparture() {
        val departed = StartupPolicy.left(StartupPolicy.visible(boot(), time), time + 1_000)
        val off = StartupPolicy.screenOff(departed, time + 2_000)
        assertTrue(off.suppressed)
        assertTrue(off.screenOff)
        assertFalse(allowed(off, at = time + 2_000, token = "boot-1"))
        val awake = StartupPolicy.wake(off, time + 3_000, "wake-new")
        assertFalse(awake.suppressed)
        assertFalse(awake.screenOff)
        assertEquals("wake", awake.reason)
        assertEquals("wake-new", awake.requestId)
        assertTrue(allowed(awake, at = time + 3_000))
        assertFalse(allowed(awake, at = time + 3_000, token = "boot-1"))
    }

    @Test fun screenOffCancelsCurrentGenerationBeforeNextWake() {
        val pending = boot()
        val off = StartupPolicy.screenOff(pending, time + 1_000)
        assertEquals("", off.requestId)
        assertEquals(0L, off.until)
        assertFalse(allowed(off, at = time + 2_000, token = pending.requestId))
        val awake = StartupPolicy.wake(off, time + 3_000, "wake-new")
        assertTrue(allowed(awake, at = time + 3_000))
        assertEquals(0, awake.attempts)
    }

    @Test fun serviceReconnectWhileScreenOffCannotConsumeWakeTransition() {
        val watching = StartupPolicy.visible(boot(), time)
        val off = StartupPolicy.screenOff(watching, time + 1_000)
        assertEquals(off, StartupPolicy.begin(off, "revive", time + 2_000, "service-reconnect"))
        val awake = StartupPolicy.wake(off, time + 3_000, "actual-wake")
        assertEquals("actual-wake", awake.requestId)
        assertTrue(allowed(awake, at = time + 3_000))
    }

    @Test fun manualVisibilityRearmsWatchingAndClearsUserSuppression() {
        val departed = StartupPolicy.left(boot(), time)
        val manual = StartupPolicy.visible(departed, time + 1_000)
        assertTrue(manual.watching)
        assertFalse(manual.suppressed)
        assertFalse(manual.screenOff)
    }

    @Test fun confirmedBootCountIgnoresWallClockAdjustments() {
        val prior = boot()
        assertTrue(StartupPolicy.sameBoot(prior, 7, prior.bootEpoch + 86_400_000L, time + 1_000))
        assertTrue(StartupPolicy.sameBoot(prior, 7, prior.bootEpoch - 86_400_000L, time + 1_000))
    }

    @Test fun changedBootCountStartsANewGenerationEvenIfEpochLooksSimilar() {
        val prior = boot()
        assertFalse(StartupPolicy.sameBoot(prior, 8, prior.bootEpoch, time + 1_000))
    }

    @Test fun UptimeRollbackCannotMatchAnOldBoot() {
        val prior = boot()
        assertFalse(StartupPolicy.sameBoot(prior, 7, prior.bootEpoch, time - 1))
        assertFalse(StartupPolicy.sameBoot(prior.copy(bootCount = -1), -1, prior.bootEpoch, time - 1))
    }

    @Test fun initialEmptyStateCannotBeMistakenForAnExistingBoot() {
        assertFalse(StartupPolicy.sameBoot(StartupState(), -1, 0L, time))
    }

    @Test fun revivalNeedsPriorWatchingAndHasItsOwnThreeWindowBudget() {
        var state = StartupState(watching = true)
        var start = time
        for (index in 1..StartupPolicy.MAX_REVIVES) {
            state = StartupPolicy.begin(state, "revive", start, "revive-$index")
            assertEquals(index, state.reviveCount)
            assertEquals("revive-$index", state.requestId)
            assertTrue(allowed(state, at = start))
            start = state.until + 1
        }
        val refused = StartupPolicy.begin(state, "revive", start, "revive-4")
        assertEquals(state, refused)
        assertFalse(allowed(refused, at = start, token = "revive-4"))
    }

    @Test fun revivalBudgetResetsOnlyAfterTenMinuteWindow() {
        val first = StartupPolicy.begin(StartupState(watching = true), "revive", time, "first")
        val exhausted = first.copy(requestId = "", until = 0, reviveCount = StartupPolicy.MAX_REVIVES)
        assertEquals(exhausted, StartupPolicy.begin(exhausted, "revive", time + StartupPolicy.REVIVE_WINDOW_MS - 1, "early"))
        val renewed = StartupPolicy.begin(exhausted, "revive", time + StartupPolicy.REVIVE_WINDOW_MS, "renewed")
        assertEquals(1, renewed.reviveCount)
        assertEquals("renewed", renewed.requestId)
        assertTrue(allowed(renewed, at = time + StartupPolicy.REVIVE_WINDOW_MS))
    }

    @Test fun realPowerCycleCreatesFreshWakeBudgetWithoutAcceptingOldToken() {
        val exhausted = StartupPolicy.begin(StartupState(watching = true), "revive", time, "old-revive")
            .copy(reviveCount = StartupPolicy.MAX_REVIVES, attempts = StartupPolicy.MAX_ATTEMPTS)
        val awake = StartupPolicy.wake(StartupPolicy.screenOff(exhausted, time + 1_000), time + 2_000, "wake")
        assertEquals(0, awake.reviveCount)
        assertEquals(0, awake.attempts)
        assertTrue(allowed(awake, at = time + 2_000))
        assertFalse(allowed(awake, at = time + 2_000, token = "old-revive"))
    }
    @Test fun serviceReconnectNeedsProvenBootChangeRatherThanWallClockGuess() {
        val prior = boot()
        assertFalse(StartupPolicy.provenNewBoot(prior, 7, time + 1_000))
        assertTrue(StartupPolicy.provenNewBoot(prior, 8, time + 1_000))
        assertTrue(StartupPolicy.provenNewBoot(prior, 7, time - 1))
        assertTrue(StartupPolicy.provenNewBoot(StartupState(), -1, time))
    }

    @Test fun absentBootCountAndAdjustedEpochDoNotProveANewBoot() {
        val prior = boot().copy(bootCount = -1)
        assertFalse(StartupPolicy.provenNewBoot(prior, -1, time + 1_000))
        assertFalse(StartupPolicy.provenNewBoot(prior.copy(bootEpoch = prior.bootEpoch + 86_400_000L), -1, time + 1_000))
        assertFalse(StartupPolicy.provenNewBoot(prior.copy(bootEpoch = prior.bootEpoch - 86_400_000L), -1, time + 1_000))
        assertFalse(StartupPolicy.provenNewBoot(boot(), -1, time + 1_000))
        assertTrue(StartupPolicy.provenNewBoot(prior, -1, time - 1))
    }

    @Test fun automaticActivityEntryRejectsCancelledDisabledOrExpiredTokens() {
        val pending = boot()
        fun accepts(state: StartupState = pending, enabled: Boolean = true, token: String = pending.requestId,
            at: Long = time) = StartupPolicy.acceptsAutomaticEntry(state, enabled, token, at)
        assertTrue(accepts())
        assertFalse(accepts(enabled = false))
        assertFalse(accepts(token = ""))
        assertFalse(accepts(token = "previous-token"))
        assertFalse(accepts(state = StartupPolicy.left(pending, time), at = time + 1))
        assertFalse(accepts(state = StartupPolicy.screenOff(pending, time), at = time + 1))
        assertFalse(accepts(at = time - 1))
        assertFalse(accepts(at = pending.until + 1))
    }

    @Test fun thirdSubmittedLaunchCanStillConfirmItsActivityEntry() {
        var pending = boot()
        for (index in 0 until StartupPolicy.MAX_ATTEMPTS)
            pending = StartupPolicy.attempted(pending, time + index * StartupPolicy.RETRY_MS)
        val confirmationAt = time + 2 * StartupPolicy.RETRY_MS + 1_000
        assertFalse(allowed(pending, at = confirmationAt))
        assertTrue(StartupPolicy.acceptsAutomaticEntry(pending, true, pending.requestId, confirmationAt))
    }

    @Test fun newBootGenerationRejectsPreviousBootActivityEntry() {
        val old = boot("old-boot")
        val current = StartupPolicy.begin(StartupState(bootCount = 8), "boot", time + 1_000, "new-boot")
        assertFalse(StartupPolicy.acceptsAutomaticEntry(current, true, old.requestId, time + 1_001))
        assertTrue(StartupPolicy.acceptsAutomaticEntry(current, true, current.requestId, time + 1_001))
    }
}