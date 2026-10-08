package cn.zhundian.app.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import cn.zhundian.core.motion.MotionCapabilities
import cn.zhundian.core.motion.MotionDetector
import cn.zhundian.core.motion.MotionMode
import cn.zhundian.core.motion.MotionReport
import cn.zhundian.core.motion.MotionStatus

/**
 * Real SensorManager adapter. The active reminder foreground service owns this source and must
 * call stop on completion/cancellation. Events are collected on one thread; reports reach main.
 * There is deliberately no simulated-success API in the production adapter.
 */
class AndroidMotionSource(
    context: Context,
    private val onReport: (MotionReport) -> Unit,
) {
    private val context = context.applicationContext
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var listener: SensorEventListener? = null
    private var detector: MotionDetector? = null
    @Volatile private var generation = 0L

    fun capabilities(): MotionCapabilities {
        fun has(type: Int) = sensors?.getDefaultSensor(type) != null
        return MotionCapabilities(
            stepDetector = has(Sensor.TYPE_STEP_DETECTOR),
            stepCounter = has(Sensor.TYPE_STEP_COUNTER),
            accelerometer = has(Sensor.TYPE_ACCELEROMETER),
            gravity = has(Sensor.TYPE_GRAVITY),
            rotationVector = has(Sensor.TYPE_ROTATION_VECTOR),
            gyroscope = has(Sensor.TYPE_GYROSCOPE),
            activityRecognitionPermission = activityPermissionGranted(),
        )
    }

    /** Choosing REDUCED_SENSORS is a UI/user decision; it is never selected automatically. */
    @Synchronized fun start(mode: MotionMode = MotionMode.FULL): MotionReport {
        stop()
        val run = generation
        val capability = capabilities()
        val source = MotionDetector(capability, mode)
        detector = source
        val missing = capability.missingFor(mode)
        if (missing.isNotEmpty()) return source.assess(SystemClock.elapsedRealtime()).also { publish(it, run) }
        val handlerThread = HandlerThread("ZhundianMotion").apply { start() }
        thread = handlerThread
        val handler = Handler(handlerThread.looper)
        worker = handler
        val events = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (run != generation) return
                val elapsedMs = event.timestamp / 1_000_000L
                val values = event.values
                fun vector(action: (Long, Double, Double, Double) -> Unit) {
                    if (values.size >= 3) action(elapsedMs, values[0].toDouble(), values[1].toDouble(), values[2].toDouble())
                }
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> vector(source::onAccelerometer)
                    Sensor.TYPE_GRAVITY -> vector(source::onGravity)
                    Sensor.TYPE_ROTATION_VECTOR -> if (values.size >= 3) source.onRotationVector(
                        elapsedMs, values[0].toDouble(), values[1].toDouble(), values[2].toDouble(), values.getOrNull(3)?.toDouble(),
                    )
                    Sensor.TYPE_GYROSCOPE -> vector(source::onGyroscope)
                    Sensor.TYPE_STEP_DETECTOR -> if (values.isNotEmpty() && values[0] > 0) source.onStepDetector(elapsedMs)
                    Sensor.TYPE_STEP_COUNTER -> if (values.isNotEmpty() && values[0].isFinite()) source.onStepCounter(elapsedMs, values[0].toLong())
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        listener = events
        val required = mutableListOf(Sensor.TYPE_ACCELEROMETER)
        required += if (capability.stepDetector) Sensor.TYPE_STEP_DETECTOR else Sensor.TYPE_STEP_COUNTER
        if (mode == MotionMode.FULL) {
            required += Sensor.TYPE_GYROSCOPE
            required += if (capability.gravity) Sensor.TYPE_GRAVITY else Sensor.TYPE_ROTATION_VECTOR
        }
        try {
            for (type in required) {
                val sensor = sensors?.getDefaultSensor(type)
                val registered = sensor != null && sensors?.registerListener(
                    events, sensor, SensorManager.SENSOR_DELAY_GAME, 0, handler,
                ) == true
                if (!registered) {
                    source.markUnavailable("无法开始采集${sensorLabel(type)}，可重新检测或退回普通提醒")
                    sensors?.unregisterListener(events)
                    break
                }
            }
        } catch (_: SecurityException) {
            source.markUnavailable("身体活动权限已撤销，暂时无法验证运动")
            sensors?.unregisterListener(events)
        } catch (_: RuntimeException) {
            source.markUnavailable("系统未允许传感器采集，暂时无法验证运动")
            sensors?.unregisterListener(events)
        }
        handler.post(object : Runnable {
            override fun run() {
                if (run != generation) return
                if (!activityPermissionGranted()) {
                    source.markUnavailable("身体活动权限已撤销，暂时无法验证运动")
                    sensors?.unregisterListener(events)
                }
                publish(source.assess(SystemClock.elapsedRealtime()), run)
                handler.postDelayed(this, 1_000L)
            }
        })
        return source.assess(SystemClock.elapsedRealtime())
    }

    /** The callback remains the canonical stream, including evidence found by this explicit poll. */
    @Synchronized fun reportNow(): MotionReport = (detector?.assess(SystemClock.elapsedRealtime())
        ?: MotionReport(MotionStatus.INSUFFICIENT_DATA, "运动采集尚未开始", observedAtElapsedMs = SystemClock.elapsedRealtime()))
        .also { publish(it, generation) }

    @Synchronized fun stop() {
        generation++
        listener?.let { sensors?.unregisterListener(it) }
        listener = null
        worker?.removeCallbacksAndMessages(null)
        worker = null
        thread?.quitSafely()
        thread = null
        detector = null
    }

    private fun activityPermissionGranted() = Build.VERSION.SDK_INT < 29 ||
        context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun publish(report: MotionReport, run: Long) {
        mainHandler.post { if (run == generation) onReport(report) }
    }

    private fun sensorLabel(type: Int) = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "加速度"
        Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR -> "计步"
        Sensor.TYPE_GYROSCOPE -> "陀螺仪"
        else -> "方向"
    }
}
