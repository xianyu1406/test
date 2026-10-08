package cn.zhundian.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.zhundian.app.R
import cn.zhundian.core.schedule.ReminderIntensity

@Composable
internal fun ReminderScreen(session: UiReminder?, controller: UiController) {
    var confirmCancel by remember { mutableStateOf(false) }
    var confirmComplete by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Column(Modifier.fillMaxSize()) {
    if (session != null && !session.terminal) {
        Surface(tonalElevation = 3.dp) {
            Button(onClick = { controller.sessionAction(SessionActions.EMERGENCY_STOP) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 56.dp)) {
                Text(stringResource(R.string.emergency_stop))
            }
        }
    }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle(stringResource(R.string.reminder_title))
        if (session == null) {
            Text(stringResource(R.string.no_session))
            return@Column
        }
        Text(session.title, style = MaterialTheme.typography.headlineSmall)
        Text(IntensityLabel(session.intensity), style = MaterialTheme.typography.labelLarge)
        ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(session.detail, style = MaterialTheme.typography.titleMedium)
            if (session.windowProgress.isNotBlank()) Text(session.windowProgress)
            if (session.nextCheckText.isNotBlank()) Text(session.nextCheckText)
            Text(stringResource(R.string.failure_count, session.failures))
            if (session.fallbackText.isNotBlank()) Text(session.fallbackText)
            if (session.callStatus.isNotBlank()) Text(session.callStatus)
        } }
        if (!session.terminal) {
            // The emergency button stays outside the scroll area and never asks for confirmation.
            Text(stringResource(R.string.emergency_hint), style = MaterialTheme.typography.bodySmall)
            if (session.intensity == ReminderIntensity.NORMAL && session.stage != "CALL_PAUSED") {
                Button(onClick = { controller.sessionAction(SessionActions.STOP_REMINDER) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.stop_reminder))
                }
                OutlinedButton(onClick = { controller.sessionAction(SessionActions.SNOOZE) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.snooze))
                }
            } else if (session.stage == "RINGING") {
                Button(onClick = { controller.sessionAction(SessionActions.START_TASK) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.start_task))
                }
            }
            if (session.stage in setOf("RINGING", "SCHULTE", "TECHNICAL_FAULT")) {
                OutlinedButton(onClick = { controller.sessionAction(SessionActions.PAUSE_SOUND) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pause_sound))
                }
            }
            if (session.stage == "SCHULTE") {
                Text(stringResource(R.string.target_number, session.nextNumber), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.challenge_progress, (session.nextNumber - 1).coerceAtLeast(0), session.taskNumbers.size))
                Text(stringResource(R.string.challenge_hint), style = MaterialTheme.typography.bodySmall)
                val columns = if (session.taskNumbers.size <= 10) 2 else 4
                session.taskNumbers.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { number ->
                            FilledTonalButton(onClick = {
                                if (number != session.nextNumber) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                controller.sessionAction(SessionActions.TAP_NUMBER, number)
                            },
                                modifier = Modifier.weight(1f).heightIn(min = 64.dp), contentPadding = PaddingValues(8.dp)) {
                                Text(number.toString(), style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
            }
            if (session.stage in setOf("MOTION_VERIFY", "MONITORING", "TECHNICAL_FAULT")) {
                Text(stringResource(R.string.motion_baseline))
            }
            if (session.canRetry) {
                Button(onClick = { controller.sessionAction(SessionActions.RETRY_MOTION) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.retry_motion)) }
                OutlinedButton(onClick = { controller.openPermission("activity_recognition") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.repair_activity_permission)) }
            }
            if (session.canDowngrade) OutlinedButton(onClick = { controller.sessionAction(SessionActions.DOWNGRADE_NORMAL) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.downgrade_normal))
            }
            if (session.canGrace) OutlinedButton(onClick = { controller.sessionAction(SessionActions.PREPARATION_GRACE) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.preparation_grace))
            }
            if (session.canFinish) Button(onClick = { controller.sessionAction(SessionActions.CONFIRM_FINISH) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.finish_check))
            }
            if (session.canResumeCall) OutlinedButton(onClick = { controller.sessionAction(SessionActions.RESUME_AFTER_CALL) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.resume_call))
            }
            if (session.canManualDial) {
                OutlinedButton(onClick = { controller.sessionAction(SessionActions.MANUAL_DIAL) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.manual_dial)) }
                Text(stringResource(R.string.manual_dial_hint), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.reminder_lifecycle_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { confirmCancel = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel_occurrence)) }
            TextButton(onClick = { confirmComplete = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.complete_schedule)) }
        }
        if (session.canResume) OutlinedButton(onClick = { controller.sessionAction(SessionActions.RESUME_SESSION) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.resume_session)) }
    }
    }
    if (confirmCancel) AlertDialog(onDismissRequest = { confirmCancel = false }, title = { Text(stringResource(R.string.cancel_occurrence_title)) },
        text = { Text(stringResource(R.string.cancel_occurrence_body)) }, confirmButton = { TextButton(onClick = { controller.sessionAction(SessionActions.CANCEL_OCCURRENCE); confirmCancel = false }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.cancel)) } })
    if (confirmComplete) AlertDialog(onDismissRequest = { confirmComplete = false }, title = { Text(stringResource(R.string.complete_title)) },
        text = { Text(stringResource(R.string.complete_body)) }, confirmButton = { TextButton(onClick = { controller.sessionAction(SessionActions.COMPLETE_SCHEDULE); confirmComplete = false }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { confirmComplete = false }) { Text(stringResource(R.string.cancel)) } })
}
