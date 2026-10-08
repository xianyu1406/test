package cn.zhundian.core.motion

import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.sqrt

/** Initial conservative rules. These values are a baseline pending physical-device calibration. */
data class MotionRules(
    val minimumWalkingMs: Long = 15_000,
    val minimumSteps: Int = 10,
    val maximumStepGapMs: Long = 2_200,
    val minimumStepGapMs: Long = 250,
    val staleAfterMs: Long = 2_000,
    val minimumHealthyMs: Long = 2_000,
    val minimumAccelerationRms: Double = 0.18,
    val maximumAccelerationRms: Double = 4.5,
) {
    init {
        require(minimumWalkingMs >= 15_000 && minimumSteps >= 10)
        require(minimumStepGapMs > 0 && maximumStepGapMs > minimumStepGapMs)
    }
}

data class MotionCapabilities(
    val stepDetector: Boolean,
    val stepCounter: Boolean,
    val accelerometer: Boolean,
    val gravity: Boolean,
    val rotationVector: Boolean,
    val gyroscope: Boolean,
    val activityRecognitionPermission: Boolean = true,
) {
    fun missingFor(mode: MotionMode): List<String> = buildList {
        if (!activityRecognitionPermission) add("未授权身体活动权限")
        if (!stepDetector && !stepCounter) add("没有系统计步传感器")
        if (!accelerometer) add("没有加速度传感器")
        if (mode == MotionMode.FULL && !gyroscope) add("没有陀螺仪")
        if (mode == MotionMode.FULL && !gravity && !rotationVector) add("没有重力或旋转向量传感器")
    }
}

/** REDUCED_SENSORS must only be selected after explicit user consent. It still requires hardware steps. */
enum class MotionMode { FULL, REDUCED_SENSORS }
enum class MotionStatus { VALID_WALKING, NORMAL_NO_WALKING, INSUFFICIENT_DATA }

data class WalkingFragment(
    val id: String,
    val startElapsedMs: Long,
    val endElapsedMs: Long,
    val steps: Int,
)

data class MotionReport(
    val status: MotionStatus,
    val reason: String,
    val fragment: WalkingFragment? = null,
    /** Cumulative healthy collection duration for this source session, never historical steps. */
    val healthyDurationMs: Long = 0,
    val observedAtElapsedMs: Long = 0,
    val reducedSensors: Boolean = false,
    /** Hardware/permission/registration fault; normal warmup and temporary sample gaps are false. */
    val unavailable: Boolean = false,
)

/**
 * Pure replayable detector. Feed monotonic elapsed-realtime milliseconds, in timestamp order per
 * sensor. Call assess periodically even if no events arrive; silence is INSUFFICIENT_DATA.
 * A successful fragment consumes its steps and is emitted once. This is evidence about the phone,
 * not a claim that a person is awake. No health or accuracy certification is implied.
 */
class MotionDetector(
    private val capabilities: MotionCapabilities,
    private val mode: MotionMode = MotionMode.FULL,
    private val rules: MotionRules = MotionRules(),
    private val sourceSessionId: String = UUID.randomUUID().toString(),
) {
    private data class Value(val time: Long, val value: Double)
    private val accelerations = ArrayDeque<Value>()
    private val gyros = ArrayDeque<Value>()
    private val steps = ArrayDeque<Long>()
    private var gravityMagnitude = 9.80665
    private var gravityX = 0.0
    private var gravityY = 0.0
    private var gravityZ = 1.0
    private var lastAcceleration = -1L
    private var lastGyro = -1L
    private var lastOrientation = -1L
    private var lastStep = -1L
    private var counterValue: Long? = null
    private var counterTime = -1L
    private var healthyMs = 0L
    private var consecutiveHealthyMs = 0L
    private var fragmentSequence = 0L
    private var lastAssessment = -1L
    private var collectionFault: String? = null
    private var lastReason = "尚未识别出持续、有节律的步行"

    @Synchronized fun onAccelerometer(timeMs: Long, x: Double, y: Double, z: Double) {
        if (timeMs <= lastAcceleration || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val delta = if (lastAcceleration >= 0) timeMs - lastAcceleration else 0
        val companionsHealthy = mode == MotionMode.REDUCED_SENSORS ||
            (recent(lastGyro, timeMs) && recent(lastOrientation, timeMs))
        if (delta in 1..400 && companionsHealthy && collectionFault == null) {
            healthyMs += delta
            consecutiveHealthyMs += delta
        } else if (delta > 400 || !companionsHealthy || collectionFault != null) {
            consecutiveHealthyMs = 0
            // Never bridge a collection gap with previously collected steps.
            steps.clear()
        }
        lastAcceleration = timeMs
        // Project onto the observed gravity direction, so rhythm is independent of phone posture.
        val vertical = if (mode == MotionMode.FULL) x * gravityX + y * gravityY + z * gravityZ
            else sqrt(x * x + y * y + z * z)
        accelerations.addLast(Value(timeMs, vertical - gravityMagnitude))
        prune(timeMs)
    }

    @Synchronized fun onGravity(timeMs: Long, x: Double, y: Double, z: Double) {
        if (timeMs <= lastOrientation || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val magnitude = sqrt(x * x + y * y + z * z)
        if (magnitude in 7.0..12.0) {
            gravityMagnitude = magnitude
            gravityX = x / magnitude
            gravityY = y / magnitude
            gravityZ = z / magnitude
            lastOrientation = timeMs
        }
    }

    /** Rotation vector supplies the gravity axis; a large rotation is never required. */
    @Synchronized fun onRotationVector(timeMs: Long, x: Double, y: Double, z: Double, quaternionW: Double? = null) {
        if (timeMs <= lastOrientation || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val norm = x * x + y * y + z * z
        if (norm <= 1.01 && (quaternionW == null || quaternionW.isFinite() && quaternionW in -1.01..1.01)) {
            val w = quaternionW ?: sqrt((1.0 - norm).coerceAtLeast(0.0))
            gravityX = 2.0 * (x * z - y * w)
            gravityY = 2.0 * (y * z + x * w)
            gravityZ = 1.0 - 2.0 * (x * x + y * y)
            lastOrientation = timeMs
        }
    }

    @Synchronized fun onGyroscope(timeMs: Long, x: Double, y: Double, z: Double) {
        if (timeMs <= lastGyro || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
        lastGyro = timeMs
        gyros.addLast(Value(timeMs, sqrt(x * x + y * y + z * z)))
        prune(timeMs)
    }

    @Synchronized fun onStepDetector(timeMs: Long) {
        if (capabilities.stepDetector) addStep(timeMs)
    }

    @Synchronized fun onStepCounter(timeMs: Long, totalSteps: Long) {
        if (capabilities.stepDetector || !capabilities.stepCounter || timeMs <= counterTime || totalSteps < 0) return
        val previous = counterValue
        val previousTime = counterTime
        counterValue = totalSteps
        counterTime = timeMs
        // First total is only a baseline. A hardware reset starts a new baseline too.
        if (previous == null || totalSteps < previous) {
            steps.clear()
            return
        }
        val delta = totalSteps - previous
        val span = timeMs - previousTime
        if (delta == 0L) return
        // Reject delayed batches rather than inventing step timing from historical totals.
        if (span > rules.staleAfterMs || delta > 4 || span / delta < rules.minimumStepGapMs) {
            steps.clear()
            lastReason = "计步回调延迟或突增，等待新的连续步行"
            return
        }
        for (index in 1..delta) addStep(previousTime + span * index / delta)
    }

    /** Permission loss/registration failure is a technical fault and cannot count as inactivity. */
    @Synchronized fun markUnavailable(reason: String) {
        collectionFault = reason
        steps.clear()
        consecutiveHealthyMs = 0
    }

    @Synchronized fun assess(nowElapsedMs: Long): MotionReport {
        fun report(status: MotionStatus, reason: String, fragment: WalkingFragment? = null, unavailable: Boolean = false) =
            MotionReport(status, reason, fragment, healthyMs, nowElapsedMs, mode == MotionMode.REDUCED_SENSORS, unavailable)
        val missing = capabilities.missingFor(mode)
        if (missing.isNotEmpty()) return report(MotionStatus.INSUFFICIENT_DATA, missing.joinToString("；") + "。可重新授权或明确退回普通提醒", unavailable = true)
        collectionFault?.let { return report(MotionStatus.INSUFFICIENT_DATA, it, unavailable = true) }
        if (nowElapsedMs < lastAssessment) {
            markUnavailable("单调时钟发生回退，需重新开始传感器采集")
            return report(MotionStatus.INSUFFICIENT_DATA, collectionFault!!, unavailable = true)
        }
        lastAssessment = nowElapsedMs
        if (!recent(lastAcceleration, nowElapsedMs) ||
            (mode == MotionMode.FULL && (!recent(lastGyro, nowElapsedMs) || !recent(lastOrientation, nowElapsedMs)))) {
            steps.clear()
            consecutiveHealthyMs = 0
            return report(MotionStatus.INSUFFICIENT_DATA, "传感器数据不足或已断流，暂时无法验证运动")
        }
        if (consecutiveHealthyMs < rules.minimumHealthyMs) return report(MotionStatus.INSUFFICIENT_DATA, "正在收集新的连续传感器数据")
        prune(nowElapsedMs)
        if (steps.size < rules.minimumSteps || steps.last - steps.first < rules.minimumWalkingMs) {
            return report(MotionStatus.NORMAL_NO_WALKING, lastReason)
        }
        val segmentStart = steps.first
        val segmentEnd = steps.last
        if (segmentEnd > nowElapsedMs) return report(MotionStatus.INSUFFICIENT_DATA, "计步时间异常，等待新的传感器数据")
        val intervalList = steps.toList().zipWithNext { a, b -> (b - a).toDouble() }
        val intervalMean = intervalList.average()
        val intervalVariation = sqrt(intervalList.map { (it - intervalMean) * (it - intervalMean) }.average()) / intervalMean
        if (intervalVariation > 0.55) return report(MotionStatus.NORMAL_NO_WALKING, "步伐间隔不够稳定，继续自然步行")
        val samples = accelerations.filter { it.time in segmentStart..segmentEnd }
        if (samples.size < (segmentEnd - segmentStart) / 150) {
            return report(MotionStatus.INSUFFICIENT_DATA, "步行片段的加速度采样不足")
        }
        val mean = samples.map { it.value }.average()
        val rms = sqrt(samples.map { (it.value - mean) * (it.value - mean) }.average())
        if (rms < rules.minimumAccelerationRms) return report(MotionStatus.NORMAL_NO_WALKING, "计步信号缺少可对应的身体运动节律")
        if (rms > rules.maximumAccelerationRms) return report(MotionStatus.NORMAL_NO_WALKING, "加速度变化过大，可能是摇晃或振动")
        val peaks = mutableListOf<Long>()
        for (i in 1 until samples.lastIndex) {
            val item = samples[i]
            if (item.value - mean >= 0.35 && item.value >= samples[i - 1].value && item.value > samples[i + 1].value &&
                (peaks.isEmpty() || item.time - peaks.last() >= 200)) peaks += item.time
        }
        if (peaks.size < steps.size * 0.6 || peaks.size > steps.size * 1.8) {
            return report(MotionStatus.NORMAL_NO_WALKING, "加速度节律与系统步伐不匹配，未确认有效步行")
        }
        if (mode == MotionMode.FULL) {
            val turns = gyros.filter { it.time in segmentStart..segmentEnd }
            if (turns.size < (segmentEnd - segmentStart) / 250) return report(MotionStatus.INSUFFICIENT_DATA, "步行片段的陀螺仪数据不足")
            val turnRms = sqrt(turns.map { it.value * it.value }.average())
            if (turnRms > 4.5) return report(MotionStatus.NORMAL_NO_WALKING, "手机转动过于剧烈，未确认自然步行")
        }
        val fragment = WalkingFragment("$sourceSessionId:${++fragmentSequence}", segmentStart, segmentEnd, steps.size)
        steps.clear()
        lastReason = "已记录上一段步行，等待下一段新的步行"
        val qualifier = if (mode == MotionMode.REDUCED_SENSORS) "（用户选择的较弱检测）" else ""
        return report(MotionStatus.VALID_WALKING, "识别到至少15秒、10步且节律一致的新步行片段$qualifier；参数待真机校准", fragment)
    }

    private fun recent(timestamp: Long, now: Long) = timestamp >= 0 && now - timestamp in 0..rules.staleAfterMs

    private fun addStep(timeMs: Long) {
        if (timeMs < 0 || timeMs <= lastStep || (lastStep >= 0 && timeMs - lastStep < rules.minimumStepGapMs)) return
        if (lastStep >= 0 && timeMs - lastStep > rules.maximumStepGapMs) steps.clear()
        lastStep = timeMs
        steps.addLast(timeMs)
        prune(timeMs)
    }

    private fun prune(now: Long) {
        val cutoff = now - maxOf(30_000L, rules.minimumWalkingMs + 5_000)
        while (accelerations.isNotEmpty() && accelerations.first.time < cutoff) accelerations.removeFirst()
        while (gyros.isNotEmpty() && gyros.first.time < cutoff) gyros.removeFirst()
        while (steps.isNotEmpty() && steps.first < cutoff) steps.removeFirst()
    }
}
