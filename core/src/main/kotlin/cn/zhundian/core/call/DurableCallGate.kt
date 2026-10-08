package cn.zhundian.core.call

import cn.zhundian.core.session.CallAvailability
import cn.zhundian.core.session.CallStatus
import cn.zhundian.core.session.Event
import cn.zhundian.core.session.Session
import cn.zhundian.core.session.SessionEngine
import cn.zhundian.core.session.Stage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * At most one native call request per reminder instance, backed by the persisted Session.
 * Keep one gate in the application's serial coordinator, which must also serialize cancellation
 * and ordinary session writes. [save] must finish its database transaction before returning.
 * A process dying after the claim but before submit deliberately loses that attempt: callers
 * must not retry a CLAIMED_UNCERTAIN session. No number or exception message is retained here.
 */
class DurableCallGate {
    private val mutex = Mutex()

    suspend fun attempt(
        load: suspend () -> Session,
        save: suspend (Session) -> Unit,
        eligibility: suspend () -> CallAvailability,
        submit: suspend () -> Boolean,
        now: () -> Long,
        beforeSubmit: suspend () -> Unit = {},
    ): Session = mutex.withLock {
        var session = load()
        if (!session.canAttempt()) return@withLock session

        val available = try {
            eligibility()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CallAvailability.UNKNOWN
        }
        // Eligibility may suspend while the user aborts. Re-read before doing any claiming.
        session = load()
        if (!session.canAttempt()) return@withLock session
        session = SessionEngine.reduce(session, Event.FallbackAvailability(now(), available))
        save(session)
        if (available != CallAvailability.READY) return@withLock session

        session = load()
        if (!session.canAttempt() || session.callAvailability != CallAvailability.READY) return@withLock session
        val claim = SessionEngine.reduce(session, Event.ClaimCall(now()))
        if (!claim.callClaimed) return@withLock claim
        // The one-shot permission is committed before any external side effect or suspension.
        save(claim)

        try {
            session = load()
            if (session.stage.terminal || !session.callClaimed || !session.fallbackEnabled) return@withLock session
            beforeSubmit()
            // Last cancellation check is after the potentially suspending audio operation too.
            session = load()
            if (session.stage.terminal || !session.callClaimed || !session.fallbackEnabled ||
                session.callStatus != CallStatus.CLAIMED_UNCERTAIN) return@withLock session
            val submitted = submit()
            session = load()
            if (session.stage.terminal) return@withLock session
            // The platform adapter may prove its final preflight returned without invoking
            // the native API. Only that same live coordinator may persist an unclaimed blocked
            // state, preserving the opportunity. A crash or a thrown API call cannot do this.
            if (!session.callClaimed) return@withLock session
            val result = SessionEngine.reduce(
                session,
                if (submitted) Event.CallSubmitted(now())
                else Event.CallFailed(now(), "系统未提交呼叫；等待用户手动拨打，不自动重试"),
            )
            save(result)
            result
        } catch (failure: Exception) {
            // A thrown native call might have reached the system. Resume local checks, preserve
            // the durable claim and avoid suggesting that retry is safe. Even cancellation must
            // leave this state recoverable before it propagates back to the service.
            val recovered = withContext(NonCancellable) {
                val current = load()
                if (current.stage.terminal) current else {
                    val resumed = SessionEngine.reduce(current, Event.ResumeAfterCall(now())).copy(
                        callStatus = CallStatus.CLAIMED_UNCERTAIN,
                        callMessage = "提交结果不确定；继续本地提醒，不会自动补拨",
                    )
                    save(resumed)
                    resumed
                }
            }
            if (failure is CancellationException) throw failure
            recovered
        }
    }

    private fun Session.canAttempt(): Boolean = !stage.terminal && stage != Stage.CALL_PAUSED &&
        fallbackEnabled && fallbackTriggered && !callClaimed
}
