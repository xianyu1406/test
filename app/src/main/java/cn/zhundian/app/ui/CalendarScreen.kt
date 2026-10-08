package cn.zhundian.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.zhundian.app.R
import cn.zhundian.core.schedule.Occurrence
import cn.zhundian.core.schedule.Schedule
import cn.zhundian.core.schedule.ScheduleEngine
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

@Composable
internal fun CalendarScreen(
    state: UiState, calendarOnly: Boolean,
    onAdd: (String, String) -> Unit, onEdit: (Schedule, String, EditScope) -> Unit,
    onTravel: () -> Unit, controller: UiController,
) {
    var dateText by rememberSaveable { mutableStateOf(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()) }
    val selected = LocalDate.parse(dateText)
    var weekly by rememberSaveable { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Pair<Schedule, String>?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    LaunchedEffect(calendarOnly) { if (!calendarOnly) dateText = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString() }
    val occurrences = remember(state.schedules, selected) { state.schedules.flatMap { schedule ->
        runCatching { ScheduleEngine.onCalendarDate(schedule, selected.toString()) }.getOrDefault(emptyList())
    }.sortedBy { it.startEpochMillis } }
    val next = remember(state.schedules, now) {
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString()
        state.schedules.flatMap { schedule ->
            runCatching { ScheduleEngine.onCalendarDate(schedule, today) + ScheduleEngine.nextOccurrences(schedule, now, 1) }
                .getOrDefault(emptyList())
        }.filter { it.startEpochMillis >= now }.minByOrNull { it.startEpochMillis }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageTitle(stringResource(if (calendarOnly) R.string.calendar_title else R.string.today_title), stringResource(R.string.date_rule)) }
        if (state.loading) item { Text(stringResource(R.string.loading)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (!calendarOnly) item {
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.upcoming), style = MaterialTheme.typography.labelLarge)
                if (next == null) Text(stringResource(R.string.no_upcoming)) else {
                    Text(next.title, style = MaterialTheme.typography.titleLarge)
                    Text(formatOccurrenceTime(next.startEpochMillis, next.zoneId))
                    val minutes = ((next.startEpochMillis - now) / 60_000).coerceAtLeast(0)
                    Text(if (minutes < 60) stringResource(R.string.remaining_minutes, minutes) else stringResource(R.string.remaining_hours, minutes / 60, minutes % 60))
                }
            } }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Button(onClick = { onAdd(selected.toString(), "09:00") }) { Text(stringResource(R.string.add_schedule)) }
                OutlinedButton(onClick = onTravel) { Text(stringResource(R.string.travel_template)) }
            }
        }
        item {
            CalendarGrid(selected, weekly, state.schedules, onSelect = { dateText = it.toString() }, onWeekly = { weekly = it })
        }
        item { SectionTitle(stringResource(R.string.selected_date, dateText)) }
        if (occurrences.isEmpty()) item { Text(stringResource(R.string.empty_day)) }
        items(occurrences, key = { it.instanceId }) { occurrence ->
            val schedule = state.schedules.firstOrNull { it.id == occurrence.scheduleId }
            OccurrenceCard(occurrence,
                edit = { if (schedule != null) {
                    if (schedule.weeklyDays.isNotEmpty()) editing = schedule to occurrence.dateKey
                    else onEdit(schedule, occurrence.dateKey, EditScope.ALL)
                } }, skip = { controller.skipOccurrence(occurrence.scheduleId, occurrence.dateKey) },
                delete = { deleteId = occurrence.scheduleId })
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("06:00", "09:00", "12:00", "15:00", "18:00", "21:00").forEach { time ->
                    OutlinedButton(onClick = { onAdd(dateText, time) }) { Text(stringResource(R.string.add_at_time, time)) }
                }
            }
        }
        if (state.schedules.any { !it.enabled }) {
            item { SectionTitle(stringResource(R.string.disabled_title)) }
            items(state.schedules.filter { !it.enabled }, key = { "disabled-${it.id}" }) { schedule ->
                OutlinedCard { Column(Modifier.padding(12.dp)) {
                    Text(schedule.title, style = MaterialTheme.typography.titleMedium)
                    LabeledSwitch(stringResource(R.string.enabled), false, { controller.setEnabled(schedule.id, it) })
                    TextButton(onClick = { onEdit(schedule, schedule.startLocal.substringBefore('T'), EditScope.ALL) }) { Text(stringResource(R.string.edit)) }
                } }
            }
        }
    }
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null },
        title = { Text(stringResource(R.string.delete_title)) }, text = { Text(stringResource(R.string.delete_detail)) },
        confirmButton = { TextButton(onClick = { controller.deleteSchedule(id); deleteId = null }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.cancel)) } }) }
    editing?.let { (schedule, date) -> AlertDialog(onDismissRequest = { editing = null },
        title = { Text(stringResource(R.string.edit_scope)) }, text = {
            Column { listOf(EditScope.THIS to R.string.edit_this, EditScope.FUTURE to R.string.edit_future, EditScope.ALL to R.string.edit_all).forEach { (scope, label) ->
                TextButton(onClick = { editing = null; onEdit(schedule, date, scope) }) { Text(stringResource(label)) }
            } }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } }) }
}

@Composable
private fun CalendarGrid(selected: LocalDate, weekly: Boolean, schedules: List<Schedule>, onSelect: (LocalDate) -> Unit, onWeekly: (Boolean) -> Unit) {
    val month = YearMonth.from(selected)
    val start = if (weekly) selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weeks = if (weekly) 1 else ((month.lengthOfMonth() + month.atDay(1).dayOfWeek.value - 1 + 6) / 7)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(month.toString(), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            FilterChip(selected = !weekly, onClick = { onWeekly(false) }, label = { Text(stringResource(R.string.month_view)) })
            FilterChip(selected = weekly, onClick = { onWeekly(true) }, label = { Text(stringResource(R.string.week_view)) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onSelect(if (weekly) selected.minusWeeks(1) else selected.minusMonths(1)) }) { Text(stringResource(R.string.previous)) }
            TextButton(onClick = { onSelect(LocalDate.now(ZoneId.of("Asia/Shanghai"))) }) { Text(stringResource(R.string.back_today)) }
            TextButton(onClick = { onSelect(if (weekly) selected.plusWeeks(1) else selected.plusMonths(1)) }) { Text(stringResource(R.string.next)) }
        }
        Row { stringResource(R.string.weekday_names).split(" ").forEach { Text(it, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
        repeat(weeks) { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(7) { index ->
                    val date = start.plusDays((week * 7 + index).toLong())
                    val hasSchedule = remember(schedules, date) { schedules.any { runCatching { ScheduleEngine.onCalendarDate(it, date.toString()).isNotEmpty() }.getOrDefault(false) } }
                    val dateLabel = stringResource(if (hasSchedule) R.string.calendar_date_with_schedule else R.string.calendar_date_empty, date.toString())
                    Surface(color = if (date == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.small, modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = dateLabel }.clickable { onSelect(date) }) {
                        Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(date.dayOfMonth.toString(), fontWeight = if (date == selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (date.month == selected.month) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (hasSchedule) "•" else " ", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OccurrenceCard(occurrence: Occurrence, edit: () -> Unit, skip: () -> Unit, delete: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(occurrence.title, style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (occurrence.important) Text(stringResource(R.string.important), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(IntensityLabel(occurrence.intensity))
        }
        Text("${formatOccurrenceTime(occurrence.startEpochMillis, occurrence.zoneId)} — ${formatOccurrenceTime(occurrence.endEpochMillis, occurrence.zoneId)}")
        if (occurrence.location.isNotBlank()) Text(occurrence.location)
        if (occurrence.notes.isNotBlank()) Text(occurrence.notes)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = edit) { Text(stringResource(R.string.edit)) }
            TextButton(onClick = skip) { Text(stringResource(R.string.skip)) }
            TextButton(onClick = delete) { Text(stringResource(R.string.delete)) }
        }
    } }
}

internal fun formatOccurrenceTime(epoch: Long, zone: String = "Asia/Shanghai"): String =
    Instant.ofEpochMilli(epoch).atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) + " · " + zone
