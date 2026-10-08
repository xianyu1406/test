package cn.zhundian.app.platform

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import java.util.concurrent.atomic.AtomicBoolean

enum class CallEligibilityCode {
    READY, INVALID_NUMBER, NO_TELEPHONY, MISSING_PERMISSION, NO_LINE, LINE_NOT_SELECTED,
    ALREADY_IN_CALL, STATE_UNKNOWN,
}

data class CallEligibility(
    val eligible: Boolean,
    val reason: String,
    val code: CallEligibilityCode,
)

data class CallSubmission(
    val submitted: Boolean,
    val reason: String,
    /** False only when the final preflight returned before placeCall was invoked at all. */
    val apiInvoked: Boolean = true,
    val blockedEligibility: CallEligibility? = null,
)

/** OFF_HOOK includes dialing: it MUST NOT be presented as evidence that a call was answered. */
enum class ObservedCallState { UNKNOWN, IDLE, RINGING, OFF_HOOK }

interface CallGateway {
    fun preflight(number: String): CallEligibility

    /**
     * The single session coordinator must check cancellation and persist its one-shot request
     * claim immediately before this method. Never call from tests; tests inject a fake gateway.
     * A claimed request is not retried after process death or an uncertain platform submission.
     */
    fun submit(number: String, speaker: Boolean = true): CallSubmission

    fun observeCallState(callback: (ObservedCallState) -> Unit): AutoCloseable {
        callback(ObservedCallState.UNKNOWN)
        return AutoCloseable { }
    }
}

/**
 * Native cellular call adapter; no default-dialer role, own-call capability, SMS or phoneCall
 * foreground-service exemption is requested. The speaker extra is only a routing request.
 * READ_PHONE_STATE is an optional user grant, but this conservative automatic mode needs it
 * to establish that a usable cellular account exists and another call is not already active.
 * Without that evidence, callers retain their request opportunity and offer ACTION_DIAL.
 */
class AndroidCallGateway(context: Context) : CallGateway {
    private val context = context.applicationContext
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)

    override fun preflight(number: String): CallEligibility = inspect(number).first

    @SuppressLint("MissingPermission") // Permission and account state are checked by inspect again here.
    override fun submit(number: String, speaker: Boolean): CallSubmission {
        val (eligibility, account) = inspect(number)
        if (!eligibility.eligible || account == null) return CallSubmission(
            false, eligibility.reason, apiInvoked = false, blockedEligibility = eligibility,
        )
        return try {
            telecom.placeCall(
                Uri.fromParts(PhoneAccount.SCHEME_TEL, normalized(number), null),
                Bundle().apply {
                    putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, speaker)
                    putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
                },
            )
            CallSubmission(true, "请求已提交给系统；接通状态未知，免提仅为请求")
        } catch (_: SecurityException) {
            CallSubmission(false, "系统拒绝拨号权限；等待用户手动拨打")
        } catch (_: RuntimeException) {
            CallSubmission(false, "系统未能提交呼叫；等待用户手动拨打")
        }
    }

    @SuppressLint("MissingPermission")
    private fun inspect(number: String): Pair<CallEligibility, PhoneAccountHandle?> {
        fun blocked(code: CallEligibilityCode, message: String) =
            CallEligibility(false, message, code) to null
        val normalized = normalized(number)
        if (!validNumber(normalized)) return blocked(CallEligibilityCode.INVALID_NUMBER, "请填写普通电话号码")
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ||
            telecom == null || telephony == null
        ) return blocked(CallEligibilityCode.NO_TELEPHONY, "设备没有本机电话能力；等待用户手动联系")
        if (!granted(Manifest.permission.CALL_PHONE) || !granted(Manifest.permission.READ_PHONE_STATE)) {
            return blocked(CallEligibilityCode.MISSING_PERMISSION, "需通话及电话状态权限；等待用户拨打")
        }
        return try {
            val emergency = if (Build.VERSION.SDK_INT >= 29) telephony.isEmergencyNumber(normalized)
            else @Suppress("DEPRECATION") PhoneNumberUtils.isEmergencyNumber(normalized)
            if (emergency) return blocked(CallEligibilityCode.INVALID_NUMBER, "兜底联系人不能设置为紧急服务号码")
            if (telecom.isInCall) {
                return blocked(CallEligibilityCode.ALREADY_IN_CALL, "已有通话，保留申请机会并等待通话结束")
            }
            val cellularAccounts = telecom.callCapablePhoneAccounts.filter { handle ->
                telecom.getPhoneAccount(handle)?.let { account ->
                    account.isEnabled && account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) &&
                        account.supportsUriScheme(PhoneAccount.SCHEME_TEL)
                } == true
            }
            if (cellularAccounts.isEmpty()) return blocked(CallEligibilityCode.NO_LINE, "无可用 SIM 电话线路；等待用户拨打")
            val preferred = telecom.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)
            val account = preferred?.takeIf { it in cellularAccounts }
                ?: cellularAccounts.singleOrNull()
                ?: return blocked(CallEligibilityCode.LINE_NOT_SELECTED, "双 SIM 未选择默认电话线路；请在系统设置中选择")
            val subscriptionId = SubscriptionManager.getDefaultVoiceSubscriptionId()
            val selectedTelephony = if (subscriptionId >= 0) {
                telephony.createForSubscriptionId(subscriptionId)
            } else telephony
            if (selectedTelephony.simState != TelephonyManager.SIM_STATE_READY) {
                return blocked(CallEligibilityCode.NO_LINE, "电话线路尚未就绪；等待用户拨打")
            }
            CallEligibility(true, "可申请一次本机电话；免提及接通不保证", CallEligibilityCode.READY) to account
        } catch (_: SecurityException) {
            blocked(CallEligibilityCode.MISSING_PERMISSION, "无法检查电话状态或权限已撤销；等待用户拨打")
        } catch (_: RuntimeException) {
            blocked(CallEligibilityCode.STATE_UNKNOWN, "电话状态未知，暂不自动拨号；等待用户拨打")
        }
    }

    override fun observeCallState(callback: (ObservedCallState) -> Unit): AutoCloseable {
        val handler = Handler(Looper.getMainLooper())
        val closed = AtomicBoolean(false)
        var registration: AutoCloseable? = null
        handler.post {
            if (closed.get()) return@post
            if (!granted(Manifest.permission.READ_PHONE_STATE) || telephony == null) {
                callback(ObservedCallState.UNKNOWN)
                return@post
            }
            registration = try {
                if (Build.VERSION.SDK_INT >= 31) observeModern { if (!closed.get()) callback(it) }
                else observeLegacy { if (!closed.get()) callback(it) }
            } catch (_: RuntimeException) {
                callback(ObservedCallState.UNKNOWN)
                null
            }
        }
        return AutoCloseable {
            closed.set(true)
            handler.post { runCatching { registration?.close() }; registration = null }
        }
    }

    @TargetApi(31)
    @SuppressLint("MissingPermission")
    private fun observeModern(callback: (ObservedCallState) -> Unit): AutoCloseable {
        val listener = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) = callback(observed(state))
        }
        telephony.registerTelephonyCallback(context.mainExecutor, listener)
        return AutoCloseable { telephony.unregisterTelephonyCallback(listener) }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun observeLegacy(callback: (ObservedCallState) -> Unit): AutoCloseable {
        val listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) = callback(observed(state))
        }
        telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        return AutoCloseable { telephony.listen(listener, PhoneStateListener.LISTEN_NONE) }
    }

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        fun manualDialIntent(number: String): Intent? = normalized(number).takeIf(::validNumber)?.let {
            Intent(Intent.ACTION_DIAL, Uri.fromParts(PhoneAccount.SCHEME_TEL, it, null))
        }

        private fun normalized(number: String) = PhoneNumberUtils.stripSeparators(number.trim()).orEmpty()
        private fun validNumber(number: String) = number.matches(Regex("\\+?[0-9]{3,20}"))
        private fun observed(state: Int) = when (state) {
            TelephonyManager.CALL_STATE_IDLE -> ObservedCallState.IDLE
            TelephonyManager.CALL_STATE_RINGING -> ObservedCallState.RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> ObservedCallState.OFF_HOOK
            else -> ObservedCallState.UNKNOWN
        }
    }
}
