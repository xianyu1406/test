package cn.zhundian.core.call

import cn.zhundian.core.session.CallAvailability
import cn.zhundian.core.session.CallStatus
import cn.zhundian.core.session.Event
import cn.zhundian.core.session.Intensity
import cn.zhundian.core.session.Session
import cn.zhundian.core.session.SessionEngine
import cn.zhundian.core.session.Stage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** All call effects in this test are fake. No Android call adapter or phone number is used. */
class DurableCallGateTest {
    private class Fixture {
        var now = 480_000L
        var stored = SessionEngine.reduce(
            SessionEngine.create("wake-1", Intensity.STRONG, 0, fallbackEnabled = true),
            Event.Tick(now),
        )
        var availability = CallAvailability.READY
        var requests = 0
        var pauses = 0
        val gate = DurableCallGate()

        suspend fun attempt(
            gate: DurableCallGate = this.gate,
            before: suspend () -> Unit = { pauses++ },
            submission: suspend () -> Boolean = {
                // This is the externally observable safety boundary, not an in-memory claim.
                assertTrue(stored.callClaimed)
                assertEquals(CallStatus.CLAIMED_UNCERTAIN, stored.callStatus)
                requests++
                true
            },
            eligibility: suspend () -> CallAvailability = { availability },
        ): Session = gate.attempt(
            load = { stored }, save = { stored = it }, eligibility = eligibility,
            submit = submission, now = { now }, beforeSubmit = before,
        )
    }

    @Test fun concurrentTimeoutsAndDuplicateBroadcastsRequestOnce() = runTest {
        val f = Fixture()
        (1..30).map { async { f.attempt() } }.awaitAll()
        assertEquals(1, f.requests)
        assertEquals(1, f.pauses)
        assertEquals(CallStatus.SUBMITTED, f.stored.callStatus)
    }

    @Test fun unavailablePermissionLineOrCurrentCallDoNotConsumeOpportunity() = runTest {
        for (availability in listOf(
            CallAvailability.NO_PERMISSION, CallAvailability.NO_LINE, CallAvailability.ONGOING_CALL,
            CallAvailability.MANUAL_ONLY, CallAvailability.UNKNOWN,
        )) {
            val f = Fixture()
            f.availability = availability
            f.attempt()
            assertFalse("$availability must retain eligibility", f.stored.callClaimed)
            assertEquals(0, f.requests)
            f.availability = CallAvailability.READY
            f.attempt()
            assertEquals(1, f.requests)
        }
    }

    @Test fun emergencyStopWhileCheckingEligibilityPreventsClaimAndCall() = runTest {
        val f = Fixture()
        f.attempt(eligibility = {
            f.stored = SessionEngine.reduce(f.stored, Event.EmergencyStop(f.now))
            CallAvailability.READY
        })
        assertEquals(Stage.ABORTED, f.stored.stage)
        assertFalse(f.stored.callClaimed)
        assertEquals(0, f.requests)
    }

    @Test fun cancellationAfterClaimButBeforeApiPreventsCall() = runTest {
        for (emergency in listOf(false, true)) {
            val f = Fixture()
            f.attempt(before = {
                f.stored = SessionEngine.reduce(f.stored,
                    if (emergency) Event.EmergencyStop(f.now) else Event.Cancel(f.now))
            })
            assertTrue(f.stored.stage.terminal)
            assertEquals(0, f.requests)
        }
    }

    @Test fun restoredClaimedSessionCannotRetryEvenWithNewGateInstance() = runTest {
        val f = Fixture()
        f.stored = SessionEngine.reduce(f.stored, Event.FallbackAvailability(f.now, CallAvailability.READY))
        f.stored = SessionEngine.reduce(f.stored, Event.ClaimCall(f.now))
        f.attempt(gate = DurableCallGate())
        assertTrue(f.stored.callClaimed)
        assertEquals(CallStatus.CLAIMED_UNCERTAIN, f.stored.callStatus)
        assertEquals(0, f.requests)
    }

    @Test fun consentRevokedBeforeSubmissionPreventsCall() = runTest {
        val f = Fixture()
        f.attempt(before = {
            f.stored = f.stored.copy(fallbackEnabled = false)
        })
        assertEquals(0, f.requests)
        assertFalse(f.stored.fallbackEnabled)
    }

    @Test fun uncertainSubmissionPreservesOneShotAndLocalReminderWithoutLeakingErrorText() = runTest {
        val f = Fixture()
        f.attempt(submission = {
            f.requests++
            throw IllegalStateException("private diagnostic must not escape")
        })
        assertEquals(Stage.RINGING, f.stored.stage)
        assertEquals(CallStatus.CLAIMED_UNCERTAIN, f.stored.callStatus)
        assertFalse(f.stored.callMessage.orEmpty().contains("private"))
        f.attempt(gate = DurableCallGate())
        assertEquals(1, f.requests)
    }

    @Test fun knownFailedSubmissionDoesNotAutomaticallyRetry() = runTest {
        val f = Fixture()
        f.attempt(submission = { f.requests++; false })
        assertEquals(CallStatus.FAILED, f.stored.callStatus)
        assertEquals(Stage.RINGING, f.stored.stage)
        f.attempt()
        assertEquals(1, f.requests)
    }

    @Test fun finalPreflightProvesNoNativeApiInvocationAndRetainsOpportunity() = runTest {
        for (availability in listOf(
            CallAvailability.NO_PERMISSION, CallAvailability.NO_LINE, CallAvailability.ONGOING_CALL,
        )) {
            val f = Fixture()
            f.attempt(submission = {
                // Simulates the adapter's explicit apiInvoked=false result, NOT a thrown API.
                // The coordinator durably restores the opportunity while holding its lock.
                f.stored = SessionEngine.reduce(f.stored, Event.ResumeAfterCall(f.now)).copy(
                    callClaimed = false,
                    callAvailability = availability,
                    callStatus = CallStatus.BLOCKED,
                    callMessage = "最终检查未调用电话接口，保留请求机会",
                )
                false
            })
            assertFalse(f.stored.callClaimed)
            assertEquals(CallStatus.BLOCKED, f.stored.callStatus)
            assertEquals(Stage.RINGING, f.stored.stage)
            assertEquals(0, f.requests)
            f.attempt(gate = DurableCallGate())
            assertEquals(1, f.requests)
        }
    }

    @Test fun disabledFallbackNeverChecksEligibilityOrCalls() = runTest {
        val f = Fixture()
        f.stored = f.stored.copy(fallbackEnabled = false, callStatus = CallStatus.DISABLED)
        f.attempt(eligibility = { error("Disabled fallback must not inspect the phone") })
        assertEquals(0, f.requests)
        assertFalse(f.stored.callClaimed)
    }

    @Test fun otherCallPausesSessionWithoutConsumingFallbackClaim() = runTest {
        val f = Fixture()
        f.stored = SessionEngine.reduce(f.stored, Event.ExternalCallStarted(f.now))
        f.attempt()
        assertFalse(f.stored.callClaimed)
        assertEquals(0, f.requests)
        f.stored = SessionEngine.reduce(f.stored, Event.ResumeAfterCall(++f.now))
        f.attempt()
        assertEquals(1, f.requests)
    }
}
