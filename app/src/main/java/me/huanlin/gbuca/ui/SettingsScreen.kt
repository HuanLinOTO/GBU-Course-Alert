package me.huanlin.gbuca.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import me.huanlin.gbuca.BuildConfig
import me.huanlin.gbuca.GbuCaApp
import me.huanlin.gbuca.R
import me.huanlin.gbuca.reminder.LiveUpdateNotifier
import me.huanlin.gbuca.reminder.ReminderScheduler
import me.huanlin.gbuca.widget.TodayWidgetReceiver
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    reminderScheduler: ReminderScheduler,
    vm: AppViewModel,
) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsState()
    val remindersEnabled by vm.remindersEnabled.collectAsState()
    val reminderMinutes by vm.reminderMinutes.collectAsState()
    var username by rememberSaveable { mutableStateOf(GbuCaApp.instance.creds.username ?: "") }
    var password by remember { mutableStateOf("") }
    var savedTick by remember { mutableIntStateOf(0) }

    var notifGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifGranted = it
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TopAppBar(title = { Text(stringResource(R.string.settings_title)) })

        // ---- 账号 ----
        SectionTitle(stringResource(R.string.settings_section_account))
        SettingsCard {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.login_student_id)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (username.isBlank()) stringResource(R.string.login_password) else stringResource(R.string.settings_password_keep)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = {
                    if (password.any { it in '\uFF01'..'\uFF5E' || it == '\u3000' }) {
                        Text(
                            stringResource(R.string.login_fullwidth_hint),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
            Row {
                Button(onClick = {
                    if (username.isNotBlank()) {
                        vm.saveCredentials(username, password.ifBlank {
                            GbuCaApp.instance.creds.password ?: ""
                        })
                        savedTick++
                        vm.sync()
                    }
                }, enabled = !ui.syncing) {
                    if (ui.syncing) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (ui.syncing) stringResource(R.string.settings_syncing) else stringResource(R.string.settings_save_and_sync))
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { vm.sync() }, enabled = !ui.syncing) {
                    Text(stringResource(R.string.settings_sync_only))
                }
            }
            ui.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // ---- 提醒 ----
        SectionTitle(stringResource(R.string.settings_section_reminder))
        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_enable_reminders), Modifier.weight(1f))
                Switch(
                    checked = remindersEnabled,
                    onCheckedChange = { on -> vm.setRemindersEnabled(on) },
                )
            }
            Column {
                Text(stringResource(R.string.settings_reminder_minutes_label), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 20, 30).forEach { min ->
                        FilterChip(
                            selected = reminderMinutes == min,
                            onClick = { vm.setReminderMinutes(min) },
                            label = { Text(stringResource(R.string.settings_reminder_minutes_chip, min)) },
                        )
                    }
                }
            }
            if (!reminderScheduler.canScheduleExact()) {
                Column {
                    Text(
                        stringResource(R.string.settings_exact_alarm_missing),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { reminderScheduler.requestExactPermission() }) {
                        Text(stringResource(R.string.settings_grant_exact_alarm))
                    }
                }
            }
            if (!notifGranted && Build.VERSION.SDK_INT >= 33) {
                Column {
                    Text(
                        stringResource(R.string.settings_notif_missing),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                        Text(stringResource(R.string.settings_grant_notif))
                    }
                }
            }
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }) { Text(stringResource(R.string.settings_battery_whitelist)) }
        }

        // ---- Live Update ----
        SectionTitle(stringResource(R.string.settings_section_live))
        SettingsCard {
            Text(
                stringResource(R.string.settings_live_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { LiveUpdateNotifier.showTest(context) }) {
                    Text(stringResource(R.string.settings_live_test))
                }
                OutlinedButton(onClick = { LiveUpdateNotifier.cancel(context) }) {
                    Text(stringResource(R.string.settings_live_stop))
                }
            }
        }

        // ---- 学期配置 ----
        SectionTitle(stringResource(R.string.settings_section_term))
        SettingsCard {
            SemesterStartDatePicker(
                current = vm.semesterStartMonday,
                calibrating = ui.syncing,
                onCalibrate = { vm.calibrateSemesterStartFromServer() },
                onConfirm = { vm.setSemesterStartMonday(it) },
            )
            ui.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.calibrateOk == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Text(
                stringResource(R.string.settings_term_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- 关于 ----
        SectionTitle(stringResource(R.string.settings_section_about))
        val uriHandler = LocalUriHandler.current
        val repoUrl = stringResource(R.string.settings_about_repo_url)
        SettingsCard {
            Text(
                stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.settings_about_school),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.settings_about_repo),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { uriHandler.openUri(repoUrl) },
            )
            Text(
                stringResource(R.string.settings_about_copyright),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.settings_about),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val lastSync = vm.settings.lastSyncAt
            if (lastSync > 0) {
                Text(
                    stringResource(
                        R.string.settings_last_sync,
                        java.time.Instant.ofEpochMilli(lastSync).atZone(java.time.ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 分组卡片：包裹同类设置项的原生 M3 卡片。 */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SemesterStartDatePicker(
    current: LocalDate,
    calibrating: Boolean,
    onCalibrate: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    var text by remember(current) {
        mutableStateOf(current.format(DateTimeFormatter.ISO_LOCAL_DATE))
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.settings_term_start_label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = runCatching { LocalDate.parse(text) }.isFailure,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onCalibrate, enabled = !calibrating) {
            if (calibrating) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (calibrating) stringResource(R.string.settings_calibrating) else stringResource(R.string.settings_calibrate))
        }
        Spacer(Modifier.width(12.dp))
        TextButton(onClick = {
            runCatching { LocalDate.parse(text) }.getOrNull()?.let(onConfirm)
        }) { Text(stringResource(R.string.settings_save)) }
    }
}
