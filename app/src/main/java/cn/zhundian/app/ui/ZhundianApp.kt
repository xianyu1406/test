package cn.zhundian.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.zhundian.app.R
import cn.zhundian.core.schedule.ReminderIntensity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val DraftSaver = Saver<ScheduleDraft, String>(save = { Json.encodeToString(it) }, restore = { Json.decodeFromString(it) })
internal val SettingsSaver = Saver<UiSettings, String>(save = { Json.encodeToString(it) }, restore = { Json.decodeFromString(it) })

@Composable
fun ZhundianApp(controller: UiController) {
    val state by controller.state.collectAsState()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var editor by rememberSaveable(stateSaver = Saver<ScheduleDraft?, String>(
        save = { it?.let { draft -> Json.encodeToString(draft) }.orEmpty() },
        restore = { if (it.isEmpty()) null else Json.decodeFromString<ScheduleDraft>(it) },
    )) { mutableStateOf(null) }
    var scope by rememberSaveable { mutableStateOf(EditScope.ALL) }
    var travel by rememberSaveable { mutableStateOf(false) }
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            BackHandler(enabled = editor != null || travel || page != 0) {
                if (editor != null) editor = null else if (travel) travel = false else page = 0
            }
            // The initial transition opens the reminder; subsequent recompositions never restart its task.
            LaunchedEffect(state.session?.id) { if (state.session?.terminal == false) { page = 2; editor = null; travel = false } }
            Scaffold(bottomBar = {
                if (editor == null && !travel) NavigationBar {
                    val labels = listOf(R.string.nav_today, R.string.nav_calendar, R.string.nav_reminder, R.string.nav_history, R.string.nav_settings)
                    val icons = listOf(R.string.nav_today_icon, R.string.nav_calendar_icon, R.string.nav_reminder_icon, R.string.nav_history_icon, R.string.nav_settings_icon)
                    labels.forEachIndexed { index, label ->
                        NavigationBarItem(selected = page == index, onClick = { page = index },
                            icon = { Text(stringResource(icons[index])) }, label = { Text(stringResource(label)) })
                    }
                }
            }) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    val currentEditor = editor
                    when {
                        currentEditor != null -> EditorScreen(currentEditor, scope, state.settings, controller,
                            onBack = { editor = null }, onSaved = { editor = null })
                        travel -> TravelScreen(state.settings, controller, onBack = { travel = false }, onSaved = { travel = false })
                        page == 0 || page == 1 -> CalendarScreen(state, calendarOnly = page == 1,
                            onAdd = { date, time ->
                                scope = EditScope.ALL
                                val end = java.time.LocalDateTime.parse("${date}T${time}").plusHours(1)
                                editor = ScheduleDraft(startDate = date, startTime = time, endDate = end.toLocalDate().toString(), endTime = end.toLocalTime().toString(),
                                    reminderParameters = state.settings.parameterSnapshot())
                            }, onEdit = { schedule, date, editScope -> scope = editScope; editor = ScheduleDraft.from(schedule, if (editScope == EditScope.ALL) null else date) },
                            onTravel = { travel = true }, controller = controller)
                        page == 2 -> ReminderScreen(state.session, controller)
                        page == 3 -> HistoryScreen(state.history)
                        else -> SettingsScreen(state, controller)
                    }
                }
            }
            state.message?.let { message ->
                AlertDialog(onDismissRequest = controller::dismissMessage, text = { Text(message) },
                    confirmButton = { TextButton(onClick = controller::dismissMessage) { Text(stringResource(R.string.dismiss)) } })
            }
            state.importPreview?.let { preview ->
                AlertDialog(onDismissRequest = controller::dismissImport,
                    title = { Text(stringResource(R.string.import_preview_title)) },
                    text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.import_count, preview.count))
                        Text(preview.summary)
                        preview.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Text(stringResource(R.string.backup_hint))
                    } }, confirmButton = { TextButton(onClick = controller::confirmImport, enabled = preview.error == null && preview.count > 0) {
                        Text(stringResource(R.string.confirm_import))
                    } }, dismissButton = { TextButton(onClick = controller::dismissImport) { Text(stringResource(R.string.cancel)) } })
            }
        }
    }
}

@Composable
internal fun PageTitle(title: String, subtitle: String? = null) {
    Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
internal fun SectionTitle(title: String) = Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))

@Composable
internal fun IntensityLabel(intensity: ReminderIntensity): String = stringResource(when (intensity) {
    ReminderIntensity.NORMAL -> R.string.intensity_normal
    ReminderIntensity.MEDIUM -> R.string.intensity_medium
    ReminderIntensity.STRONG -> R.string.intensity_strong
})

@Composable
internal fun IntensitySelector(selected: ReminderIntensity, onChange: (ReminderIntensity) -> Unit) {
    Text(stringResource(R.string.intensity_title), style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReminderIntensity.entries.forEach { intensity ->
            FilterChip(selected = selected == intensity, onClick = { onChange(intensity) }, label = { Text(IntensityLabel(intensity)) })
        }
    }
    Text(stringResource(when (selected) {
        ReminderIntensity.NORMAL -> R.string.normal_rule
        ReminderIntensity.MEDIUM -> R.string.medium_rule
        ReminderIntensity.STRONG -> R.string.strong_rule
    }), style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun LabeledSwitch(text: String, checked: Boolean, onChecked: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}
