package cn.zhundian.core.session

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SessionEngineTest {
    private fun create(level: Intensity = Intensity.STRONG, contact: Boolean = false) =
        SessionEngine.create("occurrence-42", level, 0, fallbackEnabled = contact)

    private fun challenge(s: Session, at: Long = s.lastEventMs): Session {
        var value = SessionEngine.reduce(s, Event.OpenChallenge(at))
        for (n in 1..value.challengeSize) value = SessionEngine.reduce(value, Event.TapNumber(at, n))
        return value
    }

    private fun walk(s: Session, end: Long, id: String = "walk-$end", duration: Long = 15_000) =
        SessionEngine.reduce(s, Event.Motion(end, id, end - duration, end))

    private fun healthyThrough(s: Session, end: Long): Session {
        var value = s
        var at = s.lastEventMs
        while (at <= end) {
            value = SessionEngine.reduce(value, Event.SensorStatus(at, SensorQuality.NORMAL, "正常采集，无有效步行"))
            at += 1_000
        }
        if (value.lastEventMs < end) value = SessionEngine.reduce(value, Event.SensorStatus(end, SensorQuality.NORMAL))
        return value
    }

    @Test fun normalSnoozesExactly120SecondsAndStopsOnlyItsInstance() {
        val independent = SessionEngine.create("departure", Intensity.NORMAL, 0)
        var s = SessionEngine.reduce(create(Intensity.NORMAL), Event.Snooze(1_000))
        assertEquals(121_000L, s.snoozeUntilMs)
        s = SessionEngine.reduce(s, Event.Tick(120_999))
        assertEquals(Stage.SNOOZED, s.stage)
        s = SessionEngine.reduce(s, Event.Tick(121_000))
        assertEquals(Stage.RINGING, s.stage)
        s = SessionEngine.reduce(s, Event.StopReminder(121_000))
        assertEquals(Stage.COMPLETED, s.stage)
        assertEquals(Stage.RINGING, independent.stage)
    }

    @Test fun challengeIsPermutationAndWrongTapNeverPasses() {
        var s = SessionEngine.reduce(create(), Event.OpenChallenge(0))
        assertEquals((1..20).toSet(), s.grid.toSet())
        assertEquals(20, s.grid.size)
        val savedGrid = s.grid
        s = SessionEngine.reduce(s, Event.TapNumber(0, 2))
        assertEquals(1, s.targetNumber)
        s = SessionEngine.reduce(s, Event.TapNumber(0, 1))
        val restored = Json.decodeFromString<Session>(Json.encodeToString(s))
        assertEquals(savedGrid, restored.grid)
        assertEquals(2, restored.targetNumber)
        assertEquals(Stage.SCHULTE, restored.stage)
    }

    @Test fun openingChallengeOnlyLowersTaskSoundAndPauseIsBounded() {
        var s = SessionEngine.reduce(create(), Event.OpenChallenge(0))
        assertEquals(SoundMode.TASK, s.soundMode(0))
        s = SessionEngine.reduce(s, Event.PauseSound(1_000))
        assertEquals(SoundMode.OFF, s.soundMode(30_999))
        assertEquals(SoundMode.TASK, s.soundMode(31_000))
        assertEquals(480_000L, s.nextFallbackDeadlineMs)
    }

    @Test fun mediumRequiresThreeCompleteNonOverlappingWindows() {
        var s = challenge(create(Intensity.MEDIUM))
        s = walk(s, 15_000)
        assertEquals(0, s.mediumWindow)
        assertEquals(Stage.MONITORING, s.stage)
        s = SessionEngine.reduce(s, Event.Tick(120_000))
        assertEquals(1, s.mediumWindow)
        assertFalse(s.mediumWindowPassed)
        s = walk(s, 135_000)
        s = SessionEngine.reduce(s, Event.Tick(240_000))
        assertEquals(2, s.mediumWindow)
        s = walk(s, 255_000)
        assertEquals(Stage.MONITORING, SessionEngine.reduce(s, Event.Tick(359_999)).stage)
        s = SessionEngine.reduce(s, Event.Tick(360_000))
        assertEquals(Stage.COMPLETED, s.stage)
        assertFalse(s.needsSensors)
    }

    @Test fun mediumRejectsRepeatedFragmentsAndHistoricalDataInNextWindow() {
        var s = walk(challenge(create(Intensity.MEDIUM)), 15_000, "same")
        s = SessionEngine.reduce(s, Event.Tick(120_000))
        s = SessionEngine.reduce(s, Event.Motion(120_001, "same", 0, 15_000))
        assertFalse(s.mediumWindowPassed)
        s = SessionEngine.reduce(s, Event.Motion(130_000, "span", 110_000, 130_000))
        assertFalse(s.mediumWindowPassed)
        s = walk(s, 145_000, "new")
        assertTrue(s.mediumWindowPassed)
    }

    @Test fun mediumHealthyNoWalkingFailsAndCreatesNewChallenge() {
        var s = challenge(create(Intensity.MEDIUM))
        val firstGrid = s.grid
        s = healthyThrough(s, 120_000)
        assertEquals(Stage.RINGING, s.stage)
        assertEquals(1, s.failures)
        assertNotEquals(firstGrid, s.grid)
        s = challenge(s)
        assertEquals(0, s.mediumWindow)
        assertFalse(s.mediumWindowPassed)
    }

    @Test fun missingSamplesCauseTechnicalFaultWithoutBehaviorFailure() {
        var s = challenge(create())
        s = SessionEngine.reduce(s, Event.Tick(120_000))
        assertEquals(Stage.TECHNICAL_FAULT, s.stage)
        assertEquals(0, s.failures)
        s = SessionEngine.reduce(s, Event.Tick(720_000))
        assertEquals(0, s.failures)
        assertTrue(s.fallbackTriggered)
    }

    @Test fun aSingleNormalStatusCannotStandInForTwoMinutesOfData() {
        var s = challenge(create())
        s = SessionEngine.reduce(s, Event.SensorStatus(119_999, SensorQuality.NORMAL))
        s = SessionEngine.reduce(s, Event.Tick(120_000))
        assertEquals(Stage.TECHNICAL_FAULT, s.stage)
        assertEquals(0, s.failures)
    }

    @Test fun sensorPermissionRevocationImmediatelyFaultsAndRetryPreservesStrongHistory() {
        var s = walk(challenge(create()), 15_000)
        s = walk(s, 40_000)
        val target = s.targetDeadlineMs
        val windows = s.successfulWindows
        s = SessionEngine.reduce(s, Event.SensorStatus(41_000, SensorQuality.UNAVAILABLE, "活动识别权限被撤销"))
        assertEquals(Stage.TECHNICAL_FAULT, s.stage)
        assertEquals(0, s.failures)
        s = SessionEngine.reduce(s, Event.RetrySensors(42_000))
        assertEquals(Stage.MOTION_VERIFY, s.stage)
        assertEquals(15_000L, s.t1Ms)
        assertEquals(target, s.targetDeadlineMs)
        assertEquals(windows, s.successfulWindows)
    }

    @Test fun mediumFaultRecoveryRestartsAllThreeWindows() {
        var s = walk(challenge(create(Intensity.MEDIUM)), 15_000)
        s = SessionEngine.reduce(s, Event.Tick(120_000))
        s = SessionEngine.reduce(s, Event.SensorStatus(121_000, SensorQuality.UNAVAILABLE))
        s = SessionEngine.reduce(s, Event.RetrySensors(122_000))
        assertEquals(0, s.mediumWindow)
        assertFalse(s.mediumWindowPassed)
        assertEquals(242_000L, s.motionDeadlineMs)
    }

    @Test fun timeoutCountsOnceWhileRingingAndShortSuccessDoesNotResetFailures() {
        var s = healthyThrough(challenge(create()), 120_000)
        assertEquals(1, s.failures)
        s = SessionEngine.reduce(s, Event.Tick(240_000))
        assertEquals(1, s.failures)
        s = walk(challenge(s), 255_000)
        assertEquals(1, s.failures)
        assertEquals(Stage.MONITORING, s.stage)
    }

    @Test fun onlyNewValidWalkingRefreshesNoMotionDeadline() {
        var s = walk(challenge(create()), 15_000)
        assertEquals(135_000L, s.motionDeadlineMs)
        s = SessionEngine.reduce(s, Event.Motion(30_000, "short-shake", 29_000, 30_000))
        assertEquals(135_000L, s.motionDeadlineMs)
        s = walk(s, 40_000, "valid")
        assertEquals(160_000L, s.motionDeadlineMs)
        s = SessionEngine.reduce(s, Event.Motion(50_000, "valid", 25_000, 40_000))
        assertEquals(160_000L, s.motionDeadlineMs)
    }

    @Test fun firstEightMinutesCannotBeResetBySoundPauseChallengeOrRetry() {
        var s = SessionEngine.reduce(create(contact = true), Event.OpenChallenge(0))
        s = SessionEngine.reduce(s, Event.PauseSound(450_000))
        assertEquals(480_000L, s.nextFallbackDeadlineMs)
        s = SessionEngine.reduce(s, Event.Tick(480_000))
        assertTrue(s.fallbackTriggered)
        assertFalse(s.callClaimed)
        assertEquals(0L, s.t0Ms)
    }

    @Test fun laterUnansweredRoundHasIndependentEightMinuteDeadline() {
        var s = walk(challenge(create(contact = true)), 15_000)
        s = healthyThrough(s, 135_000)
        assertEquals(Stage.RINGING, s.stage)
        assertEquals(615_000L, s.nextFallbackDeadlineMs)
        val roundId = s.roundId
        s = SessionEngine.reduce(s, Event.OpenChallenge(500_000))
        s = SessionEngine.reduce(s, Event.PauseSound(600_000))
        s = SessionEngine.reduce(s, Event.Tick(615_000))
        assertTrue(s.fallbackTriggered)
        assertEquals(roundId, s.roundId)
        assertEquals(1, s.failures)
    }

    @Test fun restoredMotionRetainsT1TargetWindowsAndCumulativeFailures() {
        var s = walk(challenge(create()), 15_000)
        s = walk(s, 40_000)
        s = healthyThrough(s, 160_000)
        val target = s.targetDeadlineMs
        val windows = s.successfulWindows
        s = walk(challenge(s), 175_000)
        assertEquals(Stage.MONITORING, s.stage)
        assertEquals(15_000L, s.t1Ms)
        assertEquals(target, s.targetDeadlineMs)
        assertTrue(s.successfulWindows.containsAll(windows))
        assertEquals(1, s.failures)
        assertNull(s.roundStartedMs)
    }

    @Test fun strongWindowBoundaryIsHalfOpenAndInitialFragmentNeverCounts() {
        var s = walk(challenge(create()), 15_000)
        assertEquals(emptySet<Int>(), s.successfulWindows)
        s = walk(s, 40_000)
        assertEquals(setOf(0), s.successfulWindows)
        s = walk(s, 135_000)
        assertEquals(setOf(0, 1), s.successfulWindows)
        s = SessionEngine.reduce(s, Event.Motion(140_000, "duplicate", 120_000, 135_000))
        assertEquals(setOf(0, 1), s.successfulWindows)
    }

    @Test fun changingMediumWindowDurationDoesNotSilentlyChangeStrongWindows() {
        val parameters = ReminderParameters(mediumWindowMs = 60_000)
        var s = walk(challenge(SessionEngine.create("separate-windows", Intensity.STRONG, 0, parameters)), 15_000)
        s = walk(s, 40_000)
        s = walk(s, 90_000)
        assertEquals(setOf(0), s.successfulWindows)
        s = walk(s, 135_000)
        assertEquals(setOf(0, 1), s.successfulWindows)
    }

    @Test fun timeoutBoundaryHasSameResultForTickThenMotionOrMotionThenTick() {
        val s = healthyThrough(challenge(create()), 119_000)
        val event = Event.Motion(120_000, "on-boundary", 105_000, 120_000)
        val tickFirst = SessionEngine.reduce(SessionEngine.reduce(s, Event.Tick(120_000)), event)
        val motionFirst = SessionEngine.reduce(SessionEngine.reduce(s, event), Event.Tick(120_000))
        assertEquals(Stage.RINGING, tickFirst.stage)
        assertEquals(tickFirst, motionFirst)
        assertEquals(1, motionFirst.failures)
    }

    @Test fun targetTimeAloneDoesNotFinishAndExplicitConfirmationIsRequired() {
        var s = walk(challenge(create()), 15_000)
        for (time in listOf(40_000L, 135_000, 240_000, 345_000, 450_000, 555_000, 615_000)) s = walk(s, time)
        assertEquals(Stage.MONITORING, s.stage)
        assertTrue(s.canFinish(615_000))
        s = SessionEngine.reduce(s, Event.ConfirmFinish(615_000))
        assertEquals(Stage.COMPLETED, s.stage)
    }

    @Test fun targetAfterMissingWindowsAllowsLaterEvidenceInsteadOfAutomaticFinish() {
        var s = walk(challenge(create()), 15_000)
        // Model a persisted session after interruption: target age is retained, no invented windows.
        s = s.copy(stage = Stage.TECHNICAL_FAULT, motionDeadlineMs = null, lastEventMs = 700_000)
        s = SessionEngine.reduce(s, Event.RetrySensors(700_000))
        s = walk(s, 715_000)
        assertFalse(s.canFinish(715_000))
        s = walk(s, 750_000)
        assertFalse(s.canFinish(750_000))
        s = walk(s, 855_000)
        assertTrue(s.canFinish(855_000))
        assertEquals(615_000L, s.targetDeadlineMs)
    }

    @Test fun graceFreezesRemainingDeadlineOnceWithoutGeneratingEvidence() {
        var s = walk(challenge(create()), 15_000)
        s = SessionEngine.reduce(s, Event.UseGrace(45_000))
        assertEquals(90_000L, s.graceRemainingMs)
        assertNull(s.motionDeadlineMs)
        assertEquals(emptySet<Int>(), s.successfulWindows)
        s = SessionEngine.reduce(s, Event.Tick(165_000))
        assertEquals(255_000L, s.motionDeadlineMs)
        assertEquals(615_000L, s.targetDeadlineMs)
        s = SessionEngine.reduce(s, Event.UseGrace(170_000))
        assertNull(s.graceUntilMs)
        assertEquals(255_000L, s.motionDeadlineMs)
    }

    @Test fun graceCannotBeEnteredBeforeFirstVerificationOrWhenDisabled() {
        assertFalse(SessionEngine.reduce(create(), Event.UseGrace(0)).graceUsed)
        val s = walk(challenge(SessionEngine.create("strict", Intensity.STRONG, 0,
            ReminderParameters(graceEnabled = false))), 15_000)
        assertFalse(SessionEngine.reduce(s, Event.UseGrace(20_000)).graceUsed)
    }

    @Test fun simultaneousFallbacksAndProcessRestorationClaimAtMostOnce() {
        var s = create(contact = true).copy(failures = 3, roundStartedMs = 0, roundId = "round-3")
        s = SessionEngine.reduce(s, Event.Tick(480_000))
        s = SessionEngine.reduce(s, Event.FallbackAvailability(480_000, CallAvailability.READY))
        s = SessionEngine.reduce(s, Event.ClaimCall(480_000))
        assertTrue(s.callClaimed)
        assertEquals(CallStatus.CLAIMED_UNCERTAIN, s.callStatus)
        val restored = Json.decodeFromString<Session>(Json.encodeToString(s))
        assertEquals(restored, SessionEngine.reduce(restored, Event.ClaimCall(480_000)))
        s = SessionEngine.reduce(restored, Event.CallSubmitted(480_000))
        assertEquals(CallStatus.SUBMITTED, s.callStatus)
        assertEquals(s, SessionEngine.reduce(s, Event.ClaimCall(480_000)))
    }

    @Test fun threeRealHealthyFailedRoundsTriggerOneFallbackWithoutResettingCount() {
        var s = create(contact = true)
        repeat(3) { round ->
            s = challenge(s)
            s = healthyThrough(s, requireNotNull(s.motionDeadlineMs))
            assertEquals(Stage.RINGING, s.stage)
            assertEquals(round + 1, s.failures)
            assertEquals(round == 2, s.fallbackTriggered)
        }
        assertEquals(360_000L, s.fallbackAtMs)
        s = SessionEngine.reduce(s, Event.Tick(480_000))
        assertEquals(3, s.failures)
        assertEquals(360_000L, s.fallbackAtMs)
        assertFalse(s.callClaimed)
    }

    @Test fun callSubmissionFailureConsumesClaimButResumesLocalReminderWithoutRetry() {
        var s = SessionEngine.reduce(create(contact = true), Event.Tick(480_000))
        s = SessionEngine.reduce(s, Event.FallbackAvailability(480_000, CallAvailability.READY))
        s = SessionEngine.reduce(s, Event.ClaimCall(480_000))
        assertEquals(Stage.CALL_PAUSED, s.stage)
        s = SessionEngine.reduce(s, Event.CallFailed(481_000, "系统拒绝本机拨号"))
        assertEquals(Stage.RINGING, s.stage)
        assertEquals(SoundMode.RING, s.soundMode(481_000))
        assertTrue(s.callClaimed)
        assertEquals(CallStatus.FAILED, s.callStatus)
        assertEquals(s, SessionEngine.reduce(s, Event.ClaimCall(481_000)))
    }

    @Test fun waitingOtherCallOrMissingPermissionDoesNotConsumeClaim() {
        var s = SessionEngine.reduce(create(contact = true), Event.Tick(480_000))
        for (availability in listOf(CallAvailability.ONGOING_CALL, CallAvailability.NO_PERMISSION, CallAvailability.NO_LINE)) {
            s = SessionEngine.reduce(s, Event.FallbackAvailability(480_000, availability))
            s = SessionEngine.reduce(s, Event.ClaimCall(480_000))
            assertFalse(s.callClaimed)
        }
        s = SessionEngine.reduce(s, Event.FallbackAvailability(480_000, CallAvailability.READY))
        s = SessionEngine.reduce(s, Event.ClaimCall(480_000))
        assertTrue(s.callClaimed)
    }

    @Test fun cancelAndEmergencyStopBeforeClaimPreventCallAndNeverAutoResume() {
        for (stop in listOf<Event>(Event.Cancel(480_000), Event.EmergencyStop(480_000))) {
            var s = SessionEngine.reduce(create(contact = true), Event.Tick(480_000))
            s = SessionEngine.reduce(s, Event.FallbackAvailability(480_000, CallAvailability.READY))
            s = SessionEngine.reduce(s, stop)
            val terminal = s
            s = SessionEngine.reduce(s, Event.ClaimCall(480_000))
            s = SessionEngine.reduce(s, Event.Tick(999_000))
            s = SessionEngine.reduce(s, Event.RetrySensors(999_000))
            assertFalse(s.callClaimed)
            assertEquals(terminal, s)
        }
    }

    @Test fun callFreezesMotionDeadlineAndRequiresFreshEvidenceOnResume() {
        var s = walk(challenge(create()), 15_000)
        s = SessionEngine.reduce(s, Event.ExternalCallStarted(45_000))
        assertEquals(Stage.CALL_PAUSED, s.stage)
        assertEquals(90_000L, s.frozenMotionRemainingMs)
        s = SessionEngine.reduce(s, Event.Tick(700_000))
        assertEquals(0, s.failures)
        assertEquals(SoundMode.OFF, s.soundMode(700_000))
        s = walk(s, 715_000)
        assertEquals(15_000L, s.lastValidMotionMs)
        s = SessionEngine.reduce(s, Event.ResumeAfterCall(720_000))
        assertEquals(810_000L, s.motionDeadlineMs)
        assertEquals(615_000L, s.targetDeadlineMs)
        assertTrue(s.requireFreshMotion)
        assertFalse(s.canFinish(720_000))
        s = walk(s, 735_000)
        assertFalse(s.requireFreshMotion)
    }

    @Test fun mediumIncomingCallRestartsCurrentWindowInFull() {
        var s = walk(challenge(create(Intensity.MEDIUM)), 15_000)
        s = SessionEngine.reduce(s, Event.ExternalCallStarted(30_000))
        s = SessionEngine.reduce(s, Event.ResumeAfterCall(80_000))
        assertEquals(200_000L, s.motionDeadlineMs)
        assertFalse(s.mediumWindowPassed)
        assertEquals(0, s.mediumWindow)
    }

    @Test fun rebootKeepsCountsButInvalidatesMotionAndNeverAutomaticallyPhones() {
        var s = walk(challenge(create(contact = true)), 15_000).copy(failures = 2)
        s = walk(s, 40_000)
        val originalWindows = s.successfulWindows
        s = SessionEngine.reduce(s, Event.RestoreAfterReboot(5_000, 900_000))
        assertEquals(Stage.TECHNICAL_FAULT, s.stage)
        assertEquals(2, s.failures)
        assertEquals(originalWindows, s.successfulWindows)
        assertFalse(s.fallbackEnabled)
        assertNull(s.lastValidMotionMs)
        assertEquals(-320_000L, s.targetDeadlineMs)
        s = SessionEngine.reduce(s, Event.RetrySensors(5_000))
        s = SessionEngine.reduce(s, Event.FallbackAvailability(5_000, CallAvailability.READY))
        s = SessionEngine.reduce(s, Event.ClaimCall(5_000))
        assertFalse(s.callClaimed)
    }

    @Test fun staleEventsAndOtherInstanceCannotAlterSession() {
        val s = SessionEngine.reduce(create(), Event.OpenChallenge(100))
        assertEquals(s, SessionEngine.reduce(s, Event.TapNumber(99, 1)))
        val another = SessionEngine.create("another", Intensity.NORMAL, 100)
        assertEquals(Stage.RINGING, another.stage)
        assertEquals(Stage.SCHULTE, s.stage)
    }
}
