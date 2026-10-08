package cn.zhundian.core.motion

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Replay evidence tests. These synthetic traces do not measure physical walking accuracy. */
class MotionDetectorTest {
    private val all = MotionCapabilities(true, true, true, true, true, true)

    private fun replay(
        detector: MotionDetector,
        from: Long = 0,
        until: Long = 17_000,
        amplitude: Double = 1.2,
        frequencyHz: Double = 1.0,
        steps: Boolean = true,
        gyro: Boolean = true,
        orientation: Boolean = true,
        totalBaseline: Long? = null,
    ) {
        for (time in from..until step 20) {
            if (orientation) detector.onGravity(time, 0.0, 0.0, 9.80665)
            if (gyro) detector.onGyroscope(time, 0.02, 0.01, 0.01)
            detector.onAccelerometer(time, 0.0, 0.0, 9.80665 + amplitude * sin(2 * PI * frequencyHz * time / 1000))
            if (steps && time % 1000 == 0L) {
                if (totalBaseline != null) detector.onStepCounter(time, totalBaseline + time / 1000)
                else detector.onStepDetector(time)
            }
        }
    }

    @Test fun rhythmicNewStepsProduceOneFragmentEvenWithSmallGyroscopeRotation() {
        val detector = MotionDetector(all, sourceSessionId = "test")
        replay(detector)
        val first = detector.assess(17_000)
        assertEquals(MotionStatus.VALID_WALKING, first.status)
        assertNotNull(first.fragment)
        assertTrue(first.fragment!!.endElapsedMs - first.fragment.startElapsedMs >= 15_000)
        assertEquals("test:1", first.fragment.id)
        assertTrue(first.healthyDurationMs >= 16_000)
        assertEquals(MotionStatus.NORMAL_NO_WALKING, detector.assess(17_000).status)
        replay(detector, from = 17_020, until = 34_000)
        val second = detector.assess(34_000).fragment!!
        assertEquals("test:2", second.id)
        assertTrue(second.startElapsedMs > first.fragment.endElapsedMs)
    }

    @Test fun normalCollectionWithoutWalkingIsDifferentFromInsufficientData() {
        val detector = MotionDetector(all)
        assertEquals(MotionStatus.INSUFFICIENT_DATA, detector.assess(0).status)
        replay(detector, amplitude = 0.01, steps = false)
        assertEquals(MotionStatus.NORMAL_NO_WALKING, detector.assess(17_000).status)
        val gap = detector.assess(19_001)
        assertEquals(MotionStatus.INSUFFICIENT_DATA, gap.status)
        assertFalse(gap.unavailable)
    }

    @Test fun shortBurstAndDuplicateCallbacksCannotMeetDurationOrStepRequirements() {
        val detector = MotionDetector(all)
        replay(detector, until = 4_000)
        repeat(30) { detector.onStepDetector(4_000) }
        assertEquals(MotionStatus.NORMAL_NO_WALKING, detector.assess(4_000).status)
    }

    @Test fun violentShakingIsRejectedDespiteHardwareStepEvents() {
        val detector = MotionDetector(all)
        replay(detector, amplitude = 8.0)
        val result = detector.assess(17_000)
        assertEquals(MotionStatus.NORMAL_NO_WALKING, result.status)
        assertTrue(result.reason.contains("摇晃"))
    }

    @Test fun FastVibrationDoesNotMatchWalkingCadence() {
        val detector = MotionDetector(all)
        replay(detector, frequencyHz = 4.0)
        val result = detector.assess(17_000)
        assertEquals(MotionStatus.NORMAL_NO_WALKING, result.status)
        assertTrue(result.reason.contains("不匹配"))
    }

    @Test fun hardwareStepsWithoutAccelerationRhythmAreRejected() {
        val detector = MotionDetector(all)
        replay(detector, amplitude = 0.01)
        assertEquals(MotionStatus.NORMAL_NO_WALKING, detector.assess(17_000).status)
    }

    @Test fun firstCounterTotalIsBaselineAndNeverHistoricalWalking() {
        val detector = MotionDetector(all.copy(stepDetector = false))
        replay(detector, steps = false)
        detector.onStepCounter(17_000, 81_432)
        assertEquals(MotionStatus.NORMAL_NO_WALKING, detector.assess(17_000).status)
        detector.onStepCounter(17_000, 81_433)
        detector.onStepCounter(16_000, 90_000)
        assertEquals(MotionStatus.NORMAL_NO_WALKING, detector.assess(17_000).status)
    }

    @Test fun freshCounterDeltasCanVerifyWalkingWithoutStepDetector() {
        val detector = MotionDetector(all.copy(stepDetector = false))
        replay(detector, totalBaseline = 60_000)
        val result = detector.assess(17_000)
        assertEquals(MotionStatus.VALID_WALKING, result.status)
        assertEquals(17, result.fragment!!.steps)
    }

    @Test fun delayedCounterBatchDoesNotInventStepTiming() {
        val detector = MotionDetector(all.copy(stepDetector = false))
        detector.onStepCounter(0, 60_000)
        replay(detector, steps = false)
        detector.onStepCounter(17_000, 60_017)
        assertEquals(MotionStatus.NORMAL_NO_WALKING, detector.assess(17_000).status)
    }

    @Test fun counterIsIgnoredWhenDetectorProvidesSteps() {
        val detector = MotionDetector(all)
        replay(detector, steps = false)
        detector.onStepCounter(0, 50_000)
        detector.onStepCounter(17_000, 50_017)
        assertEquals(MotionStatus.NORMAL_NO_WALKING, detector.assess(17_000).status)
    }

    @Test fun collectionGapCannotBridgeTwoPartialWalkingSegments() {
        val detector = MotionDetector(all)
        replay(detector, until = 10_000)
        assertEquals(MotionStatus.INSUFFICIENT_DATA, detector.assess(13_000).status)
        replay(detector, from = 14_000, until = 24_000)
        assertEquals(MotionStatus.NORMAL_NO_WALKING, detector.assess(24_000).status)
        replay(detector, from = 24_020, until = 30_000)
        assertEquals(MotionStatus.VALID_WALKING, detector.assess(30_000).status)
    }

    @Test fun permissionRevocationIsFaultAndNeverInactivityOrSuccess() {
        val detector = MotionDetector(all)
        replay(detector)
        detector.markUnavailable("身体活动权限已撤销")
        val result = detector.assess(17_000)
        assertEquals(MotionStatus.INSUFFICIENT_DATA, result.status)
        assertNull(result.fragment)
        assertTrue(result.unavailable)
        assertTrue(result.reason.contains("权限"))
    }

    @Test fun missingGyroscopeRequiresExplicitWeakerMode() {
        val capabilities = all.copy(gyroscope = false)
        val strict = MotionDetector(capabilities)
        replay(strict, gyro = false)
        assertEquals(MotionStatus.INSUFFICIENT_DATA, strict.assess(17_000).status)
        assertTrue(strict.assess(17_000).unavailable)
        assertTrue(strict.assess(17_000).reason.contains("陀螺仪"))
        val weaker = MotionDetector(capabilities, MotionMode.REDUCED_SENSORS)
        replay(weaker, gyro = false)
        assertEquals(MotionStatus.VALID_WALKING, weaker.assess(17_000).status)
    }

    @Test fun noStepHardwareCannotSilentlyBecomeCompleteMultiSensorVerification() {
        val detector = MotionDetector(all.copy(stepDetector = false, stepCounter = false), MotionMode.REDUCED_SENSORS)
        replay(detector)
        assertEquals(MotionStatus.INSUFFICIENT_DATA, detector.assess(17_000).status)
        assertTrue(detector.assess(17_000).reason.contains("没有系统计步"))
    }

    @Test fun stationaryGyroscopeIsValidButMissingGyroscopeSamplesAreUnknown() {
        val detector = MotionDetector(all)
        replay(detector, gyro = false)
        assertEquals(MotionStatus.INSUFFICIENT_DATA, detector.assess(17_000).status)
    }

    @Test fun shortPickupAndSeparateShakesNeverCombineIntoSustainedWalking() {
        val detector = MotionDetector(all)
        replay(detector, until = 5_000)
        replay(detector, from = 5_020, until = 12_000, steps = false)
        replay(detector, from = 12_020, until = 17_000)
        assertEquals(MotionStatus.NORMAL_NO_WALKING, detector.assess(17_000).status)
    }

    @Test fun vehicleLikeVibrationWithoutSystemStepsCannotPass() {
        val detector = MotionDetector(all)
        replay(detector, amplitude = 1.8, frequencyHz = 8.0, steps = false)
        assertEquals(MotionStatus.NORMAL_NO_WALKING, detector.assess(17_000).status)
    }

    @Test fun identityRotationVectorCanReplaceGravitySensorWithoutRequiringTurns() {
        val detector = MotionDetector(all.copy(gravity = false))
        for (time in 0L..17_000 step 20) {
            detector.onRotationVector(time, 0.0, 0.0, 0.0)
            replay(detector, from = time, until = time, orientation = false)
        }
        assertEquals(MotionStatus.VALID_WALKING, detector.assess(17_000).status)
    }

    @Test fun counterResetDoesNotTreatPreviousTotalAsNewSteps() {
        val detector = MotionDetector(all.copy(stepDetector = false))
        replay(detector, totalBaseline = 60_000, until = 10_000)
        detector.onStepCounter(11_000, 0)
        replay(detector, from = 10_020, until = 17_000, steps = false)
        detector.onStepCounter(17_000, 7)
        assertEquals(MotionStatus.NORMAL_NO_WALKING, detector.assess(17_000).status)
    }

    @Test fun sidewaysPhoneUsesGravityAxisWithoutNeedingLargeGyroscopeRotation() {
        val detector = MotionDetector(all)
        for (time in 0L..17_000 step 20) {
            detector.onGravity(time, 9.80665, 0.0, 0.0)
            detector.onGyroscope(time, 0.01, 0.01, 0.01)
            detector.onAccelerometer(time, 9.80665 + 1.2 * sin(2 * PI * time / 1000), 0.0, 0.0)
            if (time % 1000 == 0L) detector.onStepDetector(time)
        }
        assertEquals(MotionStatus.VALID_WALKING, detector.assess(17_000).status)
    }

    @Test fun tiltedRotationVectorSuppliesGravityAxisWhenGravityHardwareMissing() {
        val detector = MotionDetector(all.copy(gravity = false))
        for (time in 0L..17_000 step 20) {
            detector.onRotationVector(time, kotlin.math.sqrt(0.5), 0.0, 0.0)
            detector.onGyroscope(time, 0.01, 0.01, 0.01)
            detector.onAccelerometer(time, 0.0, 9.80665 + 1.2 * sin(2 * PI * time / 1000), 0.0)
            if (time % 1000 == 0L) detector.onStepDetector(time)
        }
        assertEquals(MotionStatus.VALID_WALKING, detector.assess(17_000).status)
    }
}
