package cn.zhundian.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.zhundian.app.R
import cn.zhundian.core.schedule.TravelRequest
import cn.zhundian.core.schedule.TravelTemplate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun TravelScreen(settings: UiSettings, controller: UiController, onBack: () -> Unit, onSaved: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()) }
    var time by rememberSaveable { mutableStateOf("09:00") }
    var zone by rememberSaveable { mutableStateOf("Asia/Shanghai") }
    var location by rememberSaveable { mutableStateOf("") }
    var preparation by rememberSaveable { mutableLongStateOf(30) }
    var journey by rememberSaveable { mutableLongStateOf(30) }
    var early by rememberSaveable { mutableLongStateOf(30) }
    var buffer by rememberSaveable { mutableLongStateOf(15) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    var nodes by rememberSaveable(stateSaver = Saver<List<ScheduleDraft>, String>(save = { Json.encodeToString(it) }, restore = { Json.decodeFromString(it) })) { mutableStateOf(emptyList()) }
    val defaultTitle = stringResource(R.string.travel_template)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.cancel)) }
        PageTitle(stringResource(R.string.travel_title), stringResource(R.string.travel_hint))
        TextFieldLine(R.string.title_label, title) { title = it }
        TextFieldLine(R.string.departure_date, date) { date = it }
        TextFieldLine(R.string.departure_time, time) { time = it }
        TextFieldLine(R.string.zone_id, zone) { zone = it }
        TextFieldLine(R.string.location, location) { location = it }
        NumberField(R.string.preparation_minutes, preparation) { preparation = it }
        NumberField(R.string.journey_minutes, journey) { journey = it }
        NumberField(R.string.arrival_minutes, early) { early = it }
        NumberField(R.string.buffer_minutes, buffer) { buffer = it }
        Button(onClick = {
            runCatching {
                require(listOf(preparation, journey, early, buffer).all { it in 0..10_080 })
                TravelTemplate.build(TravelRequest(title = title.ifBlank { defaultTitle }, departureLocal = "${date}T${time}", zoneId = zone,
                    preparationMinutes = preparation.toInt(), travelMinutes = journey.toInt(), earlyArrivalMinutes = early.toInt(), bufferMinutes = buffer.toInt(),
                    location = location, reminderParameters = settings.parameterSnapshot())).map { ScheduleDraft.from(it) }
            }.onSuccess { nodes = it; invalid = false }.onFailure { invalid = true }
        }) { Text(stringResource(R.string.generate_nodes)) }
        if (invalid) Text(stringResource(R.string.invalid_travel), color = MaterialTheme.colorScheme.error)
        nodes.forEachIndexed { index, node ->
            OutlinedCard { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.node_number, index + 1), style = MaterialTheme.typography.titleMedium)
                fun update(new: ScheduleDraft) { nodes = nodes.toMutableList().also { it[index] = new } }
                TextFieldLine(R.string.title_label, node.title) { update(node.copy(title = it)) }
                TextFieldLine(R.string.start_date, node.startDate) { update(node.copy(startDate = it, endDate = it)) }
                TextFieldLine(R.string.start_time, node.startTime) { val end = runCatching { java.time.LocalDateTime.parse("${node.startDate}T${it}").plusMinutes(1) }.getOrNull()
                    update(node.copy(startTime = it, endDate = end?.toLocalDate()?.toString() ?: node.endDate, endTime = end?.toLocalTime()?.toString() ?: node.endTime)) }
                IntensitySelector(node.intensity) { update(node.copy(intensity = it)) }
                val preview = runCatching { controller.preview(node) }.getOrElse { UiPreview(error = it.localizedMessage) }
                preview.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                preview.times.firstOrNull()?.let { Text(it) }
            } }
        }
        if (nodes.isNotEmpty()) Button(onClick = { controller.saveTravel(nodes); onSaved() },
            enabled = nodes.all { it.title.isNotBlank() && runCatching { controller.preview(it).error == null }.getOrDefault(false) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.save_nodes))
        }
    }
}
