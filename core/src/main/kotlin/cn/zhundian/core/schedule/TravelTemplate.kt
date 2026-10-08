package cn.zhundian.core.schedule

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class TravelRequest(
    val title: String = "赶车",
    val departureLocal: String,
    val zoneId: String = "Asia/Shanghai",
    val preparationMinutes: Int,
    val travelMinutes: Int,
    val earlyArrivalMinutes: Int,
    val bufferMinutes: Int,
    val location: String = "",
    val intensity: ReminderIntensity = ReminderIntensity.NORMAL,
    val reminderParameters: Map<String, Long> = emptyMap(),
)

/** Every output is a normal, independently editable schedule and reminder instance. */
object TravelTemplate {
    fun build(request: TravelRequest): List<Schedule> {
        require(request.title.isNotBlank()) { "请输入行程名称" }
        require(listOf(request.preparationMinutes, request.travelMinutes, request.earlyArrivalMinutes, request.bufferMinutes)
            .all { it in 0..10_080 }) { "每项时长须在 0 分钟至 7 天之间" }
        val zone = ZoneId.of(request.zoneId)
        val departure = Instant.ofEpochMilli(ScheduleEngine.resolve(LocalDateTime.parse(request.departureLocal), zone))
        val arrival = departure.minus(Duration.ofMinutes(request.earlyArrivalMinutes.toLong()))
        val leave = arrival.minus(Duration.ofMinutes(request.travelMinutes.toLong()))
        val ready = leave.minus(Duration.ofMinutes(request.bufferMinutes.toLong()))
        val wake = ready.minus(Duration.ofMinutes(request.preparationMinutes.toLong()))
        val group = UUID.randomUUID().toString()
        return listOf("起床" to wake, "准备出门" to ready, "最晚出门" to leave, "目标到站" to arrival).mapIndexed { index, (label, instant) ->
            val end = instant.plusSeconds(60)
            Schedule(
                id = "$group-$index", title = "${request.title} · $label",
                startLocal = instant.atZone(zone).toLocalDateTime().toString(),
                endLocal = end.atZone(zone).toLocalDateTime().toString(),
                zoneId = request.zoneId, location = request.location,
                notes = "发车：${request.departureLocal}（${request.zoneId}）。按输入时长倒推；可逐项编辑。检票时间请另行添加。",
                important = true, intensity = request.intensity, reminderParameters = request.reminderParameters,
                fixedStartEpochMillis = instant.toEpochMilli(), fixedEndEpochMillis = end.toEpochMilli(),
            )
        }
    }
}
