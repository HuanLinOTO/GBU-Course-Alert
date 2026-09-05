package me.huanlin.gbuca.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
    var username by rememberSaveable { mutableStateOf(vm.let { me.huanlin.gbuca.GbuCaApp.instance.creds.username } ?: "") }
    var password by rememberSaveable { mutableStateOf("") }
    var savedTick by remember { mutableStateOf(0) }

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
        TopAppBar(title = { Text("设置") })

        // ---- 账号 ----
        SectionTitle("账号")
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("学号") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(if (username.isBlank()) "密码" else "密码（留空保持不变）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = {
                if (password.any { it in '\uFF01'..'\uFF5E' || it == '\u3000' }) {
                    Text(
                        "检测到全角字符（如 ！＃＠）。若登录失败，请改用半角符号重新输入",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
        )
        Row {
            Button(onClick = {
                if (username.isNotBlank()) {
                    vm.saveCredentials(username, password.ifBlank {
                        me.huanlin.gbuca.GbuCaApp.instance.creds.password ?: ""
                    })
                    savedTick++
                    vm.sync()
                }
            }, enabled = !ui.syncing) {
                if (ui.syncing) {
                    androidx.compose.material3.CircularProgressIndicator(
                        Modifier.size(16.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (ui.syncing) "同步中…" else "保存并同步")
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { vm.sync() }, enabled = !ui.syncing) { Text("仅同步") }
        }
        ui.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ---- 提醒 ----
        SectionTitle("上课提醒")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("启用提醒", Modifier.weight(1f))
            Switch(
                checked = vm.settings.remindersEnabled,
                onCheckedChange = { on ->
                    vm.settings.remindersEnabled = on
                    if (on) reminderScheduler.rescheduleAsync() else reminderScheduler.cancelAll()
                },
            )
        }
        Text("提前提醒时间", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 15, 20, 30).forEach { min ->
                FilterChip(
                    selected = vm.settings.reminderMinutes == min,
                    onClick = {
                        vm.settings.reminderMinutes = min
                        if (vm.settings.remindersEnabled) reminderScheduler.rescheduleAsync()
                    },
                    label = { Text("${min}分钟") },
                )
            }
        }
        if (!reminderScheduler.canScheduleExact()) {
            Text(
                "未授予精确闹钟权限，提醒可能延迟",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { reminderScheduler.requestExactPermission() }) {
                Text("授予精确闹钟权限")
            }
        }
        if (!notifGranted && Build.VERSION.SDK_INT >= 33) {
            Text(
                "未授予通知权限",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text("授予通知权限")
            }
        }
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }) { Text("电池优化白名单（可选）") }

        // ---- Live Update ----
        SectionTitle("实时通知 (Live Update)")
        Text(
            "开课前按「提前提醒时间」显示候课倒计时，上课期间常驻显示课程进度，每分钟刷新（Android 16+ 效果最佳）。\n" +
                "ColorOS 用户：应用详情 → 通知管理 → 流体云，允许显示",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { me.huanlin.gbuca.reminder.LiveUpdateNotifier.showTest(context) }) {
                Text("测试完整流程（12倍速）")
            }
            OutlinedButton(onClick = { me.huanlin.gbuca.reminder.LiveUpdateNotifier.cancel(context) }) {
                Text("停止")
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // ---- 学期配置 ----
        SectionTitle("学期配置")
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
                color = if (it.startsWith("校准成功")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        Text(
            "「第 X 周」依据学期第一周周一计算。通常已在同步时从教务系统自动校准，如与实际不符可手动修改。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // ---- 关于 ----
        SectionTitle("关于")
        Text(
            "GBU 课表 · 数据仅存本机（凭据经 Keystore 加密），不同步任何服务器。\n" +
                "半角数字之学号 + 明文密码经 HTTPS 直连学校 iAAA 认证。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val lastSync = vm.settings.lastSyncAt
        if (lastSync > 0) {
            Text(
                "上次同步：${java.time.Instant.ofEpochMilli(lastSync).atZone(java.time.ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(24.dp))
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
        label = { Text("学期第一周周一 (YYYY-MM-DD)") },
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
            Text(if (calibrating) "校准中…" else "从教务系统校准")
        }
        Spacer(Modifier.width(12.dp))
        TextButton(onClick = {
            runCatching { LocalDate.parse(text) }.getOrNull()?.let(onConfirm)
        }) { Text("保存") }
    }
}
