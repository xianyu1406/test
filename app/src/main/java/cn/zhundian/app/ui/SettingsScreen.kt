package cn.zhundian.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.zhundian.app.R
import cn.zhundian.core.schedule.ReminderIntensity

@Composable
internal fun SettingsScreen(state: UiState, controller: UiController) {
    var settings by rememberSaveable(state.settings, stateSaver = SettingsSaver) { mutableStateOf(state.settings) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle(stringResource(R.string.settings_title), stringResource(R.string.local_only))
        state.session?.let { session ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(session.title, style = MaterialTheme.typography.titleMedium)
                if (session.nextCheckText.isNotBlank()) Text(session.nextCheckText)
                Text(stringResource(R.string.failure_count, session.failures))
                if (session.fallbackText.isNotBlank()) Text(session.fallbackText)
                if (session.callStatus.isNotBlank()) Text(session.callStatus)
            } }
        }
        SectionTitle(stringResource(R.string.permission_title))
        state.capabilities.forEach { capability ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(capability.title, style = MaterialTheme.typography.titleMedium)
                Text(capability.detail, color = if (capability.granted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                if (capability.actionable) TextButton(onClick = { controller.openPermission(capability.key) }) { Text(stringResource(R.string.permission_fix)) }
            } }
        }
        SectionTitle(stringResource(R.string.audio_title))
        Text(stringResource(R.string.volume_cap, (settings.volume * 100).toInt()))
        Slider(value = settings.volume, onValueChange = { settings = settings.copy(volume = it) }, valueRange = 0.1f..1f)
        Text(stringResource(R.string.task_volume, (settings.taskVolume.coerceIn(.1f, 1f) * 100).toInt()))
        Slider(value = settings.taskVolume.coerceIn(.1f, 1f), onValueChange = { settings = settings.copy(taskVolume = it) }, valueRange = .1f..1f)
        LabeledSwitch(stringResource(R.string.tts_enabled), settings.tts, { settings = settings.copy(tts = it) })
        Text(stringResource(R.string.audio_hint), style = MaterialTheme.typography.bodySmall)
        SectionTitle(stringResource(R.string.motion_defaults))
        ParameterFields(settings) { settings = it }
        SectionTitle(stringResource(R.string.contact_title))
        Text(stringResource(R.string.contact_hint))
        TextFieldLine(R.string.contact_name, settings.contactName) { settings = settings.copy(contactName = it, contactConfirmed = false, contactEnabled = false) }
        OutlinedTextField(value = settings.contactNumber, onValueChange = { settings = settings.copy(contactNumber = it, contactConfirmed = false, contactEnabled = false) },
            label = { Text(stringResource(R.string.contact_number)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = settings.contactConfirmed, onCheckedChange = {
                settings = settings.copy(contactConfirmed = it, contactEnabled = if (it) settings.contactEnabled else false)
            }, enabled = settings.contactNumber.isNotBlank())
            Text(stringResource(R.string.contact_confirmation), Modifier.weight(1f))
        }
        LabeledSwitch(stringResource(R.string.contact_enable), settings.contactEnabled,
            { settings = settings.copy(contactEnabled = it) }, enabled = settings.contactConfirmed && settings.contactNumber.isNotBlank())
        Text(stringResource(R.string.settings_parameters_hint), style = MaterialTheme.typography.bodySmall)
        Button(onClick = { controller.saveSettings(settings) }, enabled = validSettings(settings), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.save_settings))
        }
        SectionTitle(stringResource(R.string.test_title))
        Text(stringResource(R.string.test_hint))
        listOf(ReminderIntensity.NORMAL to R.string.test_normal, ReminderIntensity.MEDIUM to R.string.test_medium, ReminderIntensity.STRONG to R.string.test_strong).forEach { (intensity, label) ->
            OutlinedButton(onClick = { controller.scheduleTest(intensity) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) }
        }
        SectionTitle(stringResource(R.string.backup_title))
        Text(stringResource(R.string.backup_hint))
        OutlinedButton(onClick = controller::exportBackup, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_backup)) }
        OutlinedButton(onClick = controller::chooseImport, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_backup)) }
    }
}

@Composable
internal fun HistoryScreen(history: List<UiHistory>) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageTitle(stringResource(R.string.history_title)) }
        if (history.isEmpty()) item { Text(stringResource(R.string.history_empty)) }
        items(history.sortedByDescending { it.timestamp }) { entry ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatOccurrenceTime(entry.timestamp), style = MaterialTheme.typography.labelLarge)
                Text(entry.message)
            } }
        }
    }
}
