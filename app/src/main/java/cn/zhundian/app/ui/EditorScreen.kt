package cn.zhundian.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.zhundian.app.R
import java.time.LocalDate

@Composable
internal fun EditorScreen(initial: ScheduleDraft, scope: EditScope, settings: UiSettings, controller: UiController, onBack: () -> Unit, onSaved: () -> Unit) {
    var draft by rememberSaveable(initial.id, stateSaver = DraftSaver) { mutableStateOf(initial) }
    var repeat by rememberSaveable(initial.id) { mutableStateOf(initial.weeklyDays.isNotEmpty()) }
    val preview = remember(draft, scope) {
        runCatching { controller.preview(draft, scope) }
            .getOrElse { UiPreview(error = it.localizedMessage) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.cancel)) }
        PageTitle(stringResource(R.string.editor_title))
        if (scope == EditScope.THIS) Text(stringResource(R.string.scope_this_hint))
        if (scope == EditScope.FUTURE) Text(stringResource(R.string.scope_future_hint))
        TextFieldLine(R.string.title_label, draft.title, { draft = draft.copy(title = it) })
        TextFieldLine(R.string.start_date, draft.startDate, { draft = draft.copy(startDate = it) })
        TextFieldLine(R.string.start_time, draft.startTime, { draft = draft.copy(startTime = it) })
        TextFieldLine(R.string.end_date, draft.endDate, { draft = draft.copy(endDate = it) })
        TextFieldLine(R.string.end_time, draft.endTime, { draft = draft.copy(endTime = it) })
        TextFieldLine(R.string.zone_id, draft.zoneId, { draft = draft.copy(zoneId = it) })
        Text(stringResource(R.string.zone_hint), style = MaterialTheme.typography.bodySmall)
        if (scope != EditScope.THIS) {
            LabeledSwitch(stringResource(R.string.repeat_weekly), repeat, {
                repeat = it
                draft = draft.copy(weeklyDays = if (it) setOf(runCatching { LocalDate.parse(draft.startDate).dayOfWeek.value }.getOrDefault(1)) else emptySet())
            })
            if (repeat) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    stringResource(R.string.weekday_names).split(" ").forEachIndexed { index, label ->
                        val day = index + 1
                        FilterChip(selected = day in draft.weeklyDays, onClick = {
                            draft = draft.copy(weeklyDays = if (day in draft.weeklyDays && draft.weeklyDays.size > 1) draft.weeklyDays - day else draft.weeklyDays + day)
                        }, label = { Text(label) })
                    }
                }
                TextFieldLine(R.string.repeat_until, draft.repeatUntil, { draft = draft.copy(repeatUntil = it) })
            }
        }
        TextFieldLine(R.string.location, draft.location, { draft = draft.copy(location = it) })
        OutlinedTextField(value = draft.notes, onValueChange = { draft = draft.copy(notes = it) },
            label = { Text(stringResource(R.string.notes)) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        LabeledSwitch(stringResource(R.string.important), draft.important, { draft = draft.copy(important = it) })
        Text(stringResource(R.string.important_hint), style = MaterialTheme.typography.bodySmall)
        LabeledSwitch(stringResource(R.string.enabled), draft.enabled, { draft = draft.copy(enabled = it) })
        NumberField(R.string.lead_minutes, draft.leadMinutes.toLong(), { draft = draft.copy(leadMinutes = it.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()) })
        IntensitySelector(draft.intensity) { draft = draft.copy(intensity = it) }
        if (draft.intensity != cn.zhundian.core.schedule.ReminderIntensity.NORMAL) Text(stringResource(R.string.carry_phone_rule))
        Text(stringResource(if (settings.contactEnabled && settings.contactConfirmed) R.string.contact_current_enabled else R.string.contact_current_disabled), style = MaterialTheme.typography.bodySmall)
        SectionTitle(stringResource(R.string.parameter_title))
        val params = settingsFromMap(draft.reminderParameters, settings)
        Text(stringResource(R.string.parameter_summary, params.mediumWindowSeconds, params.mediumWindows,
            params.strongInitialSeconds, params.strongIdleSeconds, params.strongTargetSeconds, params.strongMaxFailures, params.unconfirmedSeconds))
        OutlinedButton(onClick = { draft = draft.copy(reminderParameters = settings.parameterSnapshot()) }) { Text(stringResource(R.string.parameter_defaults)) }
        ParameterFields(params) { updated -> draft = draft.copy(reminderParameters = draft.reminderParameters + updated.parameterSnapshot()) }
        Text(stringResource(R.string.volume_cap, (params.volume * 100).toInt()))
        Slider(value = params.volume.coerceIn(.1f, 1f), onValueChange = { draft = draft.copy(reminderParameters = draft.reminderParameters + ("volumePermille" to (it * 1000).toLong())) }, valueRange = .1f..1f)
        Text(stringResource(R.string.task_volume, (params.taskVolume * 100).toInt()))
        Slider(value = params.taskVolume.coerceIn(.1f, 1f), onValueChange = { draft = draft.copy(reminderParameters = draft.reminderParameters + ("taskVolumePermille" to (it * 1000).toLong())) }, valueRange = .1f..1f)
        LabeledSwitch(stringResource(R.string.tts_enabled), params.tts, { draft = draft.copy(reminderParameters = draft.reminderParameters + ("tts" to if (it) 1L else 0L)) })
        SectionTitle(stringResource(R.string.preview_title))
        preview.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (preview.times.isEmpty()) Text(stringResource(R.string.preview_empty)) else preview.times.forEach { Text(it) }
        Text(stringResource(R.string.save_hint), style = MaterialTheme.typography.bodySmall)
        Button(onClick = { controller.saveSchedule(draft, scope); onSaved() },
            enabled = draft.title.isNotBlank() && preview.error == null && validSettings(params), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.save))
        }
    }
}

@Composable
internal fun TextFieldLine(@StringRes label: Int, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(stringResource(label)) },
        singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
internal fun NumberField(@StringRes label: Int, value: Long, onValueChange: (Long) -> Unit) {
    OutlinedTextField(value = value.toString(), onValueChange = { text ->
        if (text.isEmpty()) onValueChange(0) else text.toLongOrNull()?.let(onValueChange)
    }, label = { Text(stringResource(label)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}

@Composable
internal fun ParameterFields(settings: UiSettings, onChange: (UiSettings) -> Unit) {
    NumberField(R.string.medium_seconds, settings.mediumWindowSeconds) { onChange(settings.copy(mediumWindowSeconds = it)) }
    NumberField(R.string.medium_count, settings.mediumWindows.toLong()) { onChange(settings.copy(mediumWindows = it.coerceIn(0, 100).toInt())) }
    NumberField(R.string.initial_seconds, settings.strongInitialSeconds) { onChange(settings.copy(strongInitialSeconds = it)) }
    NumberField(R.string.idle_seconds, settings.strongIdleSeconds) { onChange(settings.copy(strongIdleSeconds = it)) }
    NumberField(R.string.target_seconds, settings.strongTargetSeconds) { onChange(settings.copy(strongTargetSeconds = it)) }
    NumberField(R.string.max_failures, settings.strongMaxFailures.toLong()) { onChange(settings.copy(strongMaxFailures = it.coerceIn(0, 100).toInt())) }
    NumberField(R.string.unconfirmed_seconds, settings.unconfirmedSeconds) { onChange(settings.copy(unconfirmedSeconds = it)) }
    LabeledSwitch(stringResource(R.string.grace_enabled), settings.graceEnabled, { onChange(settings.copy(graceEnabled = it)) })
    Text(stringResource(R.string.grace_hint), style = MaterialTheme.typography.bodySmall)
    if (!validSettings(settings.copy(contactEnabled = false))) Text(stringResource(R.string.invalid_settings), color = MaterialTheme.colorScheme.error)
}

internal fun validSettings(settings: UiSettings): Boolean =
    settings.mediumWindowSeconds in 30..3600 && settings.mediumWindows in 3..12 &&
        settings.strongInitialSeconds in 30..3600 && settings.strongIdleSeconds in 30..3600 &&
        settings.strongTargetSeconds in 360..21600 &&
        settings.strongMaxFailures in 1..20 && settings.unconfirmedSeconds in 60..7200 &&
        (!settings.contactEnabled || (settings.contactNumber.isNotBlank() && settings.contactConfirmed))

internal fun settingsFromMap(map: Map<String, Long>, fallback: UiSettings): UiSettings = fallback.copy(
    mediumWindowSeconds = (map["mediumWindowMs"] ?: fallback.mediumWindowSeconds * 1000) / 1000,
    mediumWindows = map["mediumWindows"]?.toInt() ?: fallback.mediumWindows,
    strongInitialSeconds = (map["initialVerificationMs"] ?: fallback.strongInitialSeconds * 1000) / 1000,
    strongIdleSeconds = (map["noMotionMs"] ?: fallback.strongIdleSeconds * 1000) / 1000,
    strongTargetSeconds = (map["sustainedTargetMs"] ?: fallback.strongTargetSeconds * 1000) / 1000,
    strongMaxFailures = map["maxFailures"]?.toInt() ?: fallback.strongMaxFailures,
    unconfirmedSeconds = (map["unconfirmedMs"] ?: fallback.unconfirmedSeconds * 1000) / 1000,
    graceEnabled = map["graceEnabled"]?.let { it == 1L } ?: fallback.graceEnabled,
    volume = map["volumePermille"]?.div(1000f) ?: fallback.volume,
    taskVolume = (map["taskVolumePermille"]?.div(1000f) ?: fallback.taskVolume).coerceIn(.1f, 1f),
    tts = map["tts"]?.let { it == 1L } ?: fallback.tts,
)
