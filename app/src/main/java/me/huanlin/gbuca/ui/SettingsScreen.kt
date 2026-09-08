package me.huanlin.gbuca.ui

import android.content.Intent
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import me.huanlin.gbuca.data.remote.HostNormalizer
import me.huanlin.gbuca.ui.components.PermissionChecklist
import me.huanlin.gbuca.ui.components.ReminderControls
import me.huanlin.gbuca.widget.TodayWidgetReceiver
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    reminderScheduler: ReminderScheduler,
    vm: AppViewModel,
    onRerunOobe: () -> Unit,
) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsState()
    val remindersEnabled by vm.remindersEnabled.collectAsState()
    val reminderMinutes by vm.reminderMinutes.collectAsState()
    var username by rememberSaveable { mutableStateOf(GbuCaApp.instance.creds.username ?: "") }
    var password by remember { mutableStateOf("") }
    var savedTick by remember { mutableIntStateOf(0) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TopAppBar(title = { Text(stringResource(R.string.settings_title)) })

        // ---- 服务器 ----
        SectionTitle(stringResource(R.string.settings_section_server))
        SettingsCard {
            Text(
                stringResource(
                    R.string.settings_server_jwxt,
                    vm.jwxtHost.ifBlank { stringResource(R.string.settings_server_unset) },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    R.string.settings_server_iaaa,
                    vm.iaaaHost.ifBlank { stringResource(R.string.settings_server_unset) },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            var editingServer by rememberSaveable { mutableStateOf(false) }
            OutlinedButton(onClick = { editingServer = true }) {
                Text(stringResource(R.string.settings_server_edit))
            }
            if (editingServer) {
                ServerEditDialog(vm = vm, onDismiss = { editingServer = false })
            }
        }

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
            ReminderControls(
                enabled = remindersEnabled,
                minutes = reminderMinutes,
                onEnabledChange = { vm.setRemindersEnabled(it) },
                onMinutesChange = { vm.setReminderMinutes(it) },
                modifier = Modifier.fillMaxWidth(),
            )
            PermissionChecklist(reminderScheduler, Modifier.fillMaxWidth())
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
            if (ui.needWebLogin) {
                TextButton(onClick = { WebLoginActivity.start(context) }) {
                    Text(stringResource(R.string.today_open_web_login))
                }
            }
            Text(
                stringResource(R.string.settings_term_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- 向导 ----
        SectionTitle(stringResource(R.string.settings_section_wizard))
        SettingsCard {
            Text(
                stringResource(R.string.settings_wizard_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onRerunOobe) {
                Text(stringResource(R.string.settings_rerun_wizard))
            }
        }

        // ---- 导出到日历 ----
        SectionTitle(stringResource(R.string.settings_section_export))
        SettingsCard {
            Text(
                stringResource(R.string.settings_export_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val shareLabel = stringResource(R.string.settings_export_share)
            val noAppMessage = stringResource(R.string.msg_export_no_app)
            val createDoc = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("text/calendar")
            ) { uri -> if (uri != null) vm.exportIcs(uri) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { createDoc.launch(vm.suggestedIcsFileName) }) {
                    Text(stringResource(R.string.settings_export_file))
                }
                OutlinedButton(onClick = {
                    vm.shareIcs { intent ->
                        val ok = runCatching {
                            context.startActivity(Intent.createChooser(intent, shareLabel))
                        }.isSuccess
                        if (!ok) vm.showExportMessage(noAppMessage)
                    }
                }) {
                    Text(shareLabel)
                }
            }
            ui.exportMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.exportOk == true) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
            }
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

/** 服务器地址编辑对话框：与 OOBE 向导同一套校验（裸主机名，强制 https）。 */
@Composable
private fun ServerEditDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var jwxt by rememberSaveable { mutableStateOf(vm.jwxtHost) }
    var iaaa by rememberSaveable { mutableStateOf(vm.iaaaHost) }
    val j = remember(jwxt) { HostNormalizer.normalize(jwxt) }
    val i = remember(iaaa) { HostNormalizer.normalize(iaaa) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_server_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = jwxt,
                    onValueChange = { jwxt = it },
                    label = { Text(stringResource(R.string.setup_jwxt_label)) },
                    singleLine = true,
                    isError = jwxt.isNotBlank() && j == null,
                    supportingText = {
                        if (jwxt.isNotBlank() && j == null) Text(stringResource(R.string.setup_host_invalid))
                    },
                )
                OutlinedTextField(
                    value = iaaa,
                    onValueChange = { iaaa = it },
                    label = { Text(stringResource(R.string.setup_iaaa_label)) },
                    singleLine = true,
                    isError = iaaa.isNotBlank() && i == null,
                    supportingText = { if (iaaa.isNotBlank() && i == null) Text(stringResource(R.string.setup_host_invalid)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = j != null && i != null,
                onClick = {
                    val jHost = j ?: return@TextButton
                    val iHost = i ?: return@TextButton
                    vm.saveHosts(jHost, iHost)
                    onDismiss()
                },
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
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
