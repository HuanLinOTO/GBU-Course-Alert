package me.huanlin.gbuca.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import me.huanlin.gbuca.R
import me.huanlin.gbuca.data.remote.HostNormalizer
import me.huanlin.gbuca.data.remote.HostProbe
import me.huanlin.gbuca.data.remote.ProbeFailure
import me.huanlin.gbuca.data.remote.ProbeResult
import me.huanlin.gbuca.domain.oobe.OobeFlow
import me.huanlin.gbuca.domain.oobe.OobeStep
import me.huanlin.gbuca.reminder.ReminderScheduler
import me.huanlin.gbuca.ui.components.PermissionChecklist
import me.huanlin.gbuca.ui.components.ReminderControls
import me.huanlin.gbuca.ui.components.rememberPermissionStates

private const val OOBE_ANIM_MS = 300

/**
 * OOBE 向导：地址 → 登录 → 权限 → 提醒 → 完成。
 * 每步都有「上一步」，系统返回键等价于上一步（首步交回系统退出）。
 */
@Composable
fun SetupFlowScreen(
    vm: AppViewModel,
    reminderScheduler: ReminderScheduler,
    startStep: OobeStep,
    onOpenWebLogin: () -> Unit,
    onFinished: () -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(startStep) }
    var forward by remember { mutableStateOf(true) }
    // 用户名跨步保留；密码只在登录步内部 remember，绝不进 Bundle
    var username by rememberSaveable { mutableStateOf(vm.credentialUsername.orEmpty()) }

    val backTo = OobeFlow.previous(step)
    BackHandler(enabled = backTo != null) {
        forward = false
        backTo?.let { step = it }
    }

    fun go(target: OobeStep, forwardDirection: Boolean) {
        forward = forwardDirection
        step = target
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))
        OobeFlow.progressOf(step)?.let { (index, total) ->
            Text(
                stringResource(R.string.oobe_progress, index, total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            stringResource(titleOf(step)),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(subtitleOf(step)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val enter = slideInHorizontally(tween(OOBE_ANIM_MS)) { if (forward) it else -it } +
                    fadeIn(tween(OOBE_ANIM_MS))
                val exit = slideOutHorizontally(tween(OOBE_ANIM_MS)) { if (forward) -it else it } +
                    fadeOut(tween(OOBE_ANIM_MS))
                enter togetherWith exit
            },
            label = "oobe",
        ) { current ->
            val back: (() -> Unit)? = OobeFlow.previous(current)?.let { previous ->
                { go(previous, false) }
            }
            Column(Modifier.fillMaxWidth()) {
                when (current) {
                    OobeStep.Address -> AddressStep(
                        vm = vm,
                        onPrevious = back,
                        onNext = { go(OobeStep.Login, true) },
                    )
                    OobeStep.Login -> LoginStep(
                        vm = vm,
                        username = username,
                        onUsernameChange = { username = it },
                        onPrevious = back,
                        onNext = { go(OobeStep.Permissions, true) },
                        onOpenWebLogin = onOpenWebLogin,
                    )
                    OobeStep.Permissions -> PermissionsStep(
                        reminderScheduler = reminderScheduler,
                        onPrevious = back,
                        onNext = { go(OobeStep.Reminders, true) },
                    )
                    OobeStep.Reminders -> RemindersStep(
                        vm = vm,
                        onPrevious = back,
                        onNext = { go(OobeStep.Done, true) },
                    )
                    OobeStep.Done -> DoneStep(
                        vm = vm,
                        reminderScheduler = reminderScheduler,
                        onPrevious = back,
                        onFinish = onFinished,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun titleOf(step: OobeStep): Int = when (step) {
    OobeStep.Address -> R.string.setup_title
    OobeStep.Login -> R.string.oobe_login_title
    OobeStep.Permissions -> R.string.oobe_perm_title
    OobeStep.Reminders -> R.string.oobe_reminder_title
    OobeStep.Done -> R.string.oobe_done_title
}

private fun subtitleOf(step: OobeStep): Int = when (step) {
    OobeStep.Address -> R.string.setup_subtitle
    OobeStep.Login -> R.string.oobe_login_subtitle
    OobeStep.Permissions -> R.string.oobe_perm_subtitle
    OobeStep.Reminders -> R.string.oobe_reminder_subtitle
    OobeStep.Done -> R.string.oobe_done_subtitle
}

private fun probeReasonOf(failure: ProbeFailure): Int = when (failure) {
    ProbeFailure.HostNotFound -> R.string.oobe_probe_reason_dns
    ProbeFailure.Timeout -> R.string.oobe_probe_reason_timeout
    ProbeFailure.Tls -> R.string.oobe_probe_reason_tls
    ProbeFailure.Unreachable -> R.string.oobe_probe_reason_other
}

/** 地址步：填两个域名 → 保存 → 并行探测；不可达只警告，仍可「仍然继续」。 */
@Composable
private fun AddressStep(
    vm: AppViewModel,
    onPrevious: (() -> Unit)?,
    onNext: () -> Unit,
) {
    var jwxt by rememberSaveable { mutableStateOf(vm.jwxtHost) }
    var iaaa by rememberSaveable { mutableStateOf(vm.iaaaHost) }
    var probing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<ProbeFailure?>(null) }
    val jHost = remember(jwxt) { HostNormalizer.normalize(jwxt) }
    val iHost = remember(iaaa) { HostNormalizer.normalize(iaaa) }
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    /** 真正离开地址步时才落盘，避免「探测失败后放弃」把错地址写进配置。 */
    fun advance() {
        val j = jHost ?: return
        val i = iHost ?: return
        vm.completeSetup(j, i)
        onNext()
    }

    fun probeAndAdvance() {
        val j = jHost ?: return
        val i = iHost ?: return
        focus.clearFocus()
        scope.launch {
            probing = true
            failure = null
            val results = coroutineScope {
                val jwxtProbe = async { HostProbe.probe(j) }
                val iaaaProbe = async { HostProbe.probe(i) }
                listOf(jwxtProbe.await(), iaaaProbe.await())
            }
            probing = false
            val unreachable = results.filterIsInstance<ProbeResult.Unreachable>().firstOrNull()
            if (unreachable == null) advance() else failure = unreachable.failure
        }
    }

    OutlinedTextField(
        value = jwxt,
        onValueChange = {
            jwxt = it
            failure = null
        },
        label = { Text(stringResource(R.string.setup_jwxt_label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        isError = jwxt.isNotBlank() && jHost == null,
        supportingText = {
            if (jwxt.isNotBlank() && jHost == null) {
                Text(stringResource(R.string.setup_host_invalid))
            }
        },
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = iaaa,
        onValueChange = {
            iaaa = it
            failure = null
        },
        label = { Text(stringResource(R.string.setup_iaaa_label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { probeAndAdvance() }),
        isError = iaaa.isNotBlank() && iHost == null,
        supportingText = {
            if (iaaa.isNotBlank() && iHost == null) {
                Text(stringResource(R.string.setup_host_invalid))
            }
        },
    )
    if (probing) {
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.oobe_probe_checking),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    failure?.let {
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.oobe_probe_failed, stringResource(probeReasonOf(it))),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(24.dp))
    OobeFooter(
        onPrevious = onPrevious,
        nextLabel = stringResource(
            if (failure != null) R.string.oobe_continue_anyway else R.string.setup_next
        ),
        nextEnabled = jHost != null && iHost != null,
        busy = probing,
        onNext = { if (failure != null) advance() else probeAndAdvance() },
    )
}

/** 登录步：已有凭据时只展示「已登录」，不强制重登。 */
@Composable
private fun LoginStep(
    vm: AppViewModel,
    username: String,
    onUsernameChange: (String) -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: () -> Unit,
    onOpenWebLogin: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    var password by remember { mutableStateOf("") }
    var switching by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val alreadyLoggedIn = vm.hasCredentials && !switching

    fun submit() {
        if (username.isNotBlank() && password.isNotBlank() && !ui.syncing) {
            focus.clearFocus()
            vm.login(username.trim(), password) { onNext() }
        }
    }

    if (alreadyLoggedIn) {
        Text(
            stringResource(R.string.oobe_login_already, vm.credentialUsername.orEmpty()),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { switching = true }) {
            Text(stringResource(R.string.oobe_login_switch))
        }
    } else {
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text(stringResource(R.string.login_student_id)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.login_password)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            supportingText = {
                if (password.any { it in '\uFF01'..'\uFF5E' || it == '\u3000' }) {
                    Text(
                        stringResource(R.string.login_fullwidth_hint),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
        )
        ui.message?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            if (onPrevious != null) {
                TextButton(onClick = onPrevious) {
                    Text(stringResource(R.string.oobe_login_back_to_address))
                }
            }
        }
        if (ui.needWebLogin) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenWebLogin, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.login_open_web))
            }
        }
    }
    Spacer(Modifier.height(24.dp))
    OobeFooter(
        onPrevious = onPrevious,
        nextLabel = stringResource(R.string.setup_next),
        nextEnabled = alreadyLoggedIn || (username.isNotBlank() && password.isNotBlank()),
        busy = ui.syncing,
        onNext = { if (alreadyLoggedIn) onNext() else submit() },
    )
}

/** 权限步：可跳过，未授予只提示。 */
@Composable
private fun PermissionsStep(
    reminderScheduler: ReminderScheduler,
    onPrevious: (() -> Unit)?,
    onNext: () -> Unit,
) {
    PermissionChecklist(reminderScheduler, Modifier.fillMaxWidth())
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.oobe_perm_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    OobeFooter(
        onPrevious = onPrevious,
        nextLabel = stringResource(R.string.setup_next),
        onNext = onNext,
    )
}

/** 提醒步：开关 + 提前分钟数。 */
@Composable
private fun RemindersStep(
    vm: AppViewModel,
    onPrevious: (() -> Unit)?,
    onNext: () -> Unit,
) {
    val enabled by vm.remindersEnabled.collectAsState()
    val minutes by vm.reminderMinutes.collectAsState()
    ReminderControls(
        enabled = enabled,
        minutes = minutes,
        onEnabledChange = { vm.setRemindersEnabled(it) },
        onMinutesChange = { vm.setReminderMinutes(it) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(24.dp))
    OobeFooter(
        onPrevious = onPrevious,
        nextLabel = stringResource(R.string.setup_next),
        onNext = onNext,
    )
}

/** 完成步：配置摘要 +「开始使用」。 */
@Composable
private fun DoneStep(
    vm: AppViewModel,
    reminderScheduler: ReminderScheduler,
    onPrevious: (() -> Unit)?,
    onFinish: () -> Unit,
) {
    val permissions = rememberPermissionStates(reminderScheduler)
    val enabled by vm.remindersEnabled.collectAsState()
    val minutes by vm.reminderMinutes.collectAsState()

    SummaryRow(stringResource(R.string.oobe_summary_jwxt), vm.jwxtHost)
    SummaryRow(stringResource(R.string.oobe_summary_iaaa), vm.iaaaHost)
    SummaryRow(
        stringResource(R.string.oobe_summary_account),
        vm.credentialUsername ?: stringResource(R.string.oobe_summary_account_none),
    )
    SummaryRow(
        stringResource(R.string.oobe_summary_reminder),
        if (enabled) {
            stringResource(R.string.oobe_summary_reminder_on, minutes)
        } else {
            stringResource(R.string.oobe_summary_reminder_off)
        },
    )
    SummaryRow(
        stringResource(R.string.oobe_summary_notification),
        stringResource(
            if (permissions.value.notifications) R.string.oobe_summary_allowed
            else R.string.oobe_summary_denied
        ),
        warn = !permissions.value.notifications,
    )
    SummaryRow(
        stringResource(R.string.oobe_summary_exact_alarm),
        stringResource(
            if (permissions.value.exactAlarm) R.string.oobe_summary_allowed
            else R.string.oobe_summary_denied
        ),
        warn = !permissions.value.exactAlarm,
    )
    SummaryRow(
        stringResource(R.string.oobe_summary_battery),
        stringResource(
            if (permissions.value.batteryWhitelisted) R.string.oobe_summary_joined
            else R.string.oobe_summary_not_joined
        ),
    )
    Spacer(Modifier.height(24.dp))
    OobeFooter(
        onPrevious = onPrevious,
        nextLabel = stringResource(R.string.oobe_start),
        onNext = {
            vm.finishOobe()
            onFinish()
        },
    )
}

@Composable
private fun SummaryRow(label: String, value: String, warn: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.4f),
        )
    }
}

/** 向导底部按钮：上一步（首步隐藏）+ 下一步。 */
@Composable
private fun OobeFooter(
    onPrevious: (() -> Unit)?,
    nextLabel: String,
    onNext: () -> Unit,
    nextEnabled: Boolean = true,
    busy: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onPrevious != null) {
            OutlinedButton(
                onClick = onPrevious,
                enabled = !busy,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Text(stringResource(R.string.oobe_previous))
            }
        }
        Button(
            onClick = onNext,
            enabled = nextEnabled && !busy,
            modifier = Modifier.weight(1f).height(48.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(nextLabel)
        }
    }
}
