# OOBE 向导化实施计划

> **For agentic workers:** 本计划按任务逐条执行，每个任务以「测试先行 → 实现 → 验证 → 提交」收尾。
> 步骤使用 `- [ ]` 复选框跟踪进度。任务之间互相独立可评审，按顺序执行。

**Goal:** 把首次使用流程改造成真正的 OOBE 向导（地址 → 登录 → 权限 → 提醒 → 完成），每步可回退，并在向导内完成权限授权与上课提醒设置。

**Architecture:** 步骤机 `domain/oobe/OobeFlow` 是纯 Kotlin（零 Android 依赖，可 JVM 单测）；
连通性探测 `data/remote/HostProbe` 独立 OkHttpClient；权限与提醒控件抽到
`ui/components/ReminderSettings.kt` 供 OOBE 与设置页共用；`ui/SetupFlowScreen.kt`
用 `when (step)` + `AnimatedContent` 渲染五步，`BackHandler` 接管返回键。
门控由 `AppViewModel.needsOobe` 决定，`SettingsStore.oobeDone` 持久化。

**Tech Stack:** Kotlin 2.4 · Jetpack Compose (BOM 2026.08) · Material 3 ·
androidx.activity.compose（`BackHandler` / `rememberLauncherForActivityResult`）·
androidx.lifecycle.compose（`LocalLifecycleOwner`）· OkHttp · java.time · JUnit 4。
**不新增任何第三方依赖，不新增任何 Android 权限。**

**Spec:** `docs/plans/2026-09-08-oobe-wizard-design.md`

## Global Constraints

- 不新增第三方依赖；不新增 Android 权限（`POST_NOTIFICATIONS` / `SCHEDULE_EXACT_ALARM` /
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 均已在 manifest 声明）。
- 面向用户的文案一律走 `strings.xml`，**不硬编码中文**（含探测失败原因：Kotlin 侧只返回枚举，UI 侧映射资源）。
- 密码只用 `remember`，**绝不进 `rememberSaveable` / Bundle**。
- 既有 47 个单测必须保持通过；新增单测全部为纯 JVM 测试。
- 老用户（已配置地址 + 已有凭据）升级后**不得**弹出向导。
- 探测失败**不拦截**流程，只提示原因并允许「仍然继续」。
- Compose 中禁止 `context.getString(...)`（lint `LocalContextGetResourceValueCall`），一律 `stringResource`。
- 提交信息沿用仓库风格（`feat(oobe): …`），中文正文。

**文件结构**

| 文件 | 责任 |
| --- | --- |
| `app/src/main/java/me/huanlin/gbuca/domain/oobe/OobeFlow.kt`（新建） | 步骤机：顺序、回退、入口步、进度 |
| `app/src/main/java/me/huanlin/gbuca/data/remote/HostProbe.kt`（新建） | 地址可达性探测 + 失败原因枚举 |
| `app/src/main/java/me/huanlin/gbuca/ui/components/ReminderSettings.kt`（新建） | 权限清单 + 提醒控件（OOBE 与设置页共用） |
| `app/src/main/java/me/huanlin/gbuca/ui/SetupFlowScreen.kt`（新建） | 五步向导 UI |
| `app/src/main/java/me/huanlin/gbuca/ui/SetupScreen.kt`（删除） | 被 `SetupFlowScreen` 取代 |
| `app/src/main/java/me/huanlin/gbuca/ui/MainActivity.kt`（修改） | 门控：`oobeStart != null` → 向导 |
| `app/src/main/java/me/huanlin/gbuca/ui/AppViewModel.kt`（修改） | `needsOobe` / `oobeStartStep` / `finishOobe` / `hasCredentials` / `completeSetup` 简化 |
| `app/src/main/java/me/huanlin/gbuca/data/local/SettingsStore.kt`（修改） | `oobeDone` 持久化 |
| `app/src/main/java/me/huanlin/gbuca/ui/SettingsScreen.kt`（修改） | 复用组件 + 「重新运行向导」入口 |
| `app/src/main/java/me/huanlin/gbuca/ui/AppNavHost.kt`（修改） | 传递 `onRerunOobe` |
| `app/src/main/res/values/strings.xml`（修改） | 新增 oobe_* / perm_* / 向导入口文案；删除被取代的旧条目 |
| `app/src/test/java/me/huanlin/gbuca/OobeFlowTest.kt`（新建） | 步骤机单测 |
| `app/src/test/java/me/huanlin/gbuca/HostProbeTest.kt`（新建） | 失败原因映射单测 |

---

### Task 1: OobeFlow 步骤机

**Files:**
- Create: `app/src/main/java/me/huanlin/gbuca/domain/oobe/OobeFlow.kt`
- Test: `app/src/test/java/me/huanlin/gbuca/OobeFlowTest.kt`

**Interfaces:**
- Consumes: 无（纯 Kotlin）。
- Produces:
  - `enum class OobeStep { Address, Login, Permissions, Reminders, Done }`
  - `OobeFlow.steps: List<OobeStep>`
  - `OobeFlow.next(step: OobeStep): OobeStep?`
  - `OobeFlow.previous(step: OobeStep): OobeStep?`
  - `OobeFlow.startStep(hostsConfigured: Boolean, hasCredentials: Boolean): OobeStep`
  - `OobeFlow.progressOf(step: OobeStep): Pair<Int, Int>?`

- [ ] **Step 1: 写失败测试** `app/src/test/java/me/huanlin/gbuca/OobeFlowTest.kt`

```kotlin
package me.huanlin.gbuca

import me.huanlin.gbuca.domain.oobe.OobeFlow
import me.huanlin.gbuca.domain.oobe.OobeStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OobeFlowTest {

    @Test
    fun `steps follow the wizard order`() {
        assertEquals(
            listOf(
                OobeStep.Address,
                OobeStep.Login,
                OobeStep.Permissions,
                OobeStep.Reminders,
                OobeStep.Done,
            ),
            OobeFlow.steps,
        )
    }

    @Test
    fun `next walks forward and stops at done`() {
        assertEquals(OobeStep.Login, OobeFlow.next(OobeStep.Address))
        assertEquals(OobeStep.Permissions, OobeFlow.next(OobeStep.Login))
        assertEquals(OobeStep.Reminders, OobeFlow.next(OobeStep.Permissions))
        assertEquals(OobeStep.Done, OobeFlow.next(OobeStep.Reminders))
        assertNull(OobeFlow.next(OobeStep.Done))
    }

    @Test
    fun `previous walks backward and stops at address`() {
        assertEquals(OobeStep.Reminders, OobeFlow.previous(OobeStep.Done))
        assertEquals(OobeStep.Permissions, OobeFlow.previous(OobeStep.Reminders))
        assertEquals(OobeStep.Login, OobeFlow.previous(OobeStep.Permissions))
        assertEquals(OobeStep.Address, OobeFlow.previous(OobeStep.Login))
        assertNull(OobeFlow.previous(OobeStep.Address))
    }

    @Test
    fun `start step follows configuration state`() {
        assertEquals(OobeStep.Address, OobeFlow.startStep(hostsConfigured = false, hasCredentials = false))
        assertEquals(OobeStep.Address, OobeFlow.startStep(hostsConfigured = false, hasCredentials = true))
        assertEquals(OobeStep.Login, OobeFlow.startStep(hostsConfigured = true, hasCredentials = false))
        assertEquals(OobeStep.Permissions, OobeFlow.startStep(hostsConfigured = true, hasCredentials = true))
    }

    @Test
    fun `progress counts four steps and hides on done`() {
        assertEquals(1 to 4, OobeFlow.progressOf(OobeStep.Address))
        assertEquals(2 to 4, OobeFlow.progressOf(OobeStep.Login))
        assertEquals(3 to 4, OobeFlow.progressOf(OobeStep.Permissions))
        assertEquals(4 to 4, OobeFlow.progressOf(OobeStep.Reminders))
        assertNull(OobeFlow.progressOf(OobeStep.Done))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `gradlew.bat :app:testDebugUnitTest --tests "me.huanlin.gbuca.OobeFlowTest"`
Expected: 编译失败 —— `Unresolved reference: OobeFlow`

- [ ] **Step 3: 实现**

`app/src/main/java/me/huanlin/gbuca/domain/oobe/OobeFlow.kt`：

```kotlin
package me.huanlin.gbuca.domain.oobe

/** OOBE 向导的五个步骤，声明顺序即流程顺序。 */
enum class OobeStep { Address, Login, Permissions, Reminders, Done }

/**
 * OOBE 步骤机（纯 Kotlin，无 Android 依赖）。
 *
 * 只负责顺序、回退、入口步与进度标签；UI 按 [OobeStep] 渲染，不重复实现流程逻辑。
 */
object OobeFlow {

    val steps: List<OobeStep> = listOf(
        OobeStep.Address,
        OobeStep.Login,
        OobeStep.Permissions,
        OobeStep.Reminders,
        OobeStep.Done,
    )

    /** 下一步；已在 [OobeStep.Done] 时返回 null。 */
    fun next(step: OobeStep): OobeStep? {
        val index = steps.indexOf(step)
        if (index < 0) return null
        return steps.getOrNull(index + 1)
    }

    /** 上一步；已在 [OobeStep.Address] 时返回 null（交给系统返回键退出）。 */
    fun previous(step: OobeStep): OobeStep? {
        val index = steps.indexOf(step)
        if (index <= 0) return null
        return steps[index - 1]
    }

    /**
     * 自动进入向导时的起始步：
     * 缺地址 → 地址步；有地址无凭据 → 登录步；否则 → 权限步。
     */
    fun startStep(hostsConfigured: Boolean, hasCredentials: Boolean): OobeStep = when {
        !hostsConfigured -> OobeStep.Address
        !hasCredentials -> OobeStep.Login
        else -> OobeStep.Permissions
    }

    /** 进度「第 n 步 / 共 m 步」；完成步不显示进度，返回 null。 */
    fun progressOf(step: OobeStep): Pair<Int, Int>? {
        val index = steps.indexOf(step)
        if (index < 0 || step == OobeStep.Done) return null
        return (index + 1) to (steps.size - 1)
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `gradlew.bat :app:testDebugUnitTest --tests "me.huanlin.gbuca.OobeFlowTest"`
Expected: PASS（5 个用例）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/domain/oobe/OobeFlow.kt app/src/test/java/me/huanlin/gbuca/OobeFlowTest.kt
git commit -m "feat(oobe): OobeFlow 步骤机（顺序/回退/入口步/进度）"
```

---

### Task 2: HostProbe 地址探测

**Files:**
- Create: `app/src/main/java/me/huanlin/gbuca/data/remote/HostProbe.kt`
- Test: `app/src/test/java/me/huanlin/gbuca/HostProbeTest.kt`

**Interfaces:**
- Consumes: 无。
- Produces:
  - `enum class ProbeFailure { HostNotFound, Timeout, Tls, Unreachable }`
  - `sealed interface ProbeResult { data object Reachable; data class Unreachable(val failure: ProbeFailure) }`
  - `suspend fun HostProbe.probe(host: String): ProbeResult`
  - `fun HostProbe.failureOf(e: java.io.IOException): ProbeFailure`

- [ ] **Step 1: 写失败测试** `app/src/test/java/me/huanlin/gbuca/HostProbeTest.kt`

```kotlin
package me.huanlin.gbuca

import me.huanlin.gbuca.data.remote.HostProbe
import me.huanlin.gbuca.data.remote.ProbeFailure
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class HostProbeTest {

    @Test
    fun `unknown host maps to host not found`() {
        assertEquals(ProbeFailure.HostNotFound, HostProbe.failureOf(UnknownHostException("nope")))
    }

    @Test
    fun `timeout maps to timeout`() {
        assertEquals(ProbeFailure.Timeout, HostProbe.failureOf(SocketTimeoutException("slow")))
    }

    @Test
    fun `tls failure maps to tls`() {
        assertEquals(ProbeFailure.Tls, HostProbe.failureOf(SSLHandshakeException("bad cert")))
    }

    @Test
    fun `connection refused maps to unreachable`() {
        assertEquals(ProbeFailure.Unreachable, HostProbe.failureOf(ConnectException("refused")))
    }

    @Test
    fun `no route maps to unreachable`() {
        assertEquals(ProbeFailure.Unreachable, HostProbe.failureOf(NoRouteToHostException("noroute")))
    }

    @Test
    fun `generic io failure maps to unreachable`() {
        assertEquals(ProbeFailure.Unreachable, HostProbe.failureOf(IOException("boom")))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `gradlew.bat :app:testDebugUnitTest --tests "me.huanlin.gbuca.HostProbeTest"`
Expected: 编译失败 —— `Unresolved reference: HostProbe`

- [ ] **Step 3: 实现**

`app/src/main/java/me/huanlin/gbuca/data/remote/HostProbe.kt`：

```kotlin
package me.huanlin.gbuca.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** 探测失败的归类；文案在 UI 层映射为 strings.xml 资源。 */
enum class ProbeFailure { HostNotFound, Timeout, Tls, Unreachable }

/** 地址可达性探测结果。 */
sealed interface ProbeResult {
    data object Reachable : ProbeResult
    data class Unreachable(val failure: ProbeFailure) : ProbeResult
}

/**
 * OOBE 地址步的轻量连通性探测。
 *
 * 判定口径刻意宽松：只要拿到任何 HTTP 响应（含 302/401/403/405）就算可达，
 * 只有网络层异常才算不可达 —— 避免认证失败、HEAD 被拒、重定向造成假阴性。
 * 独立 OkHttpClient，不复用 GbuClient 的 apiClient（避免 Referer 拦截器与 CookieJar 干扰）。
 */
object HostProbe {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    /** 探测 `https://{host}/` 是否可达。永不抛异常。 */
    suspend fun probe(host: String): ProbeResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://$host/").head().build()
        try {
            client.newCall(request).execute().use { ProbeResult.Reachable }
        } catch (e: IOException) {
            ProbeResult.Unreachable(failureOf(e))
        }
    }

    /** 网络异常 → 失败归类（纯函数，可单测）。 */
    fun failureOf(e: IOException): ProbeFailure = when (e) {
        is UnknownHostException -> ProbeFailure.HostNotFound
        is SocketTimeoutException -> ProbeFailure.Timeout
        is SSLException -> ProbeFailure.Tls
        else -> ProbeFailure.Unreachable
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `gradlew.bat :app:testDebugUnitTest --tests "me.huanlin.gbuca.HostProbeTest"`
Expected: PASS（6 个用例）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/data/remote/HostProbe.kt app/src/test/java/me/huanlin/gbuca/HostProbeTest.kt
git commit -m "feat(oobe): HostProbe 地址可达性探测（宽松判定 + 失败归类）"
```

---

### Task 3: 权限与提醒组件抽取

**Files:**
- Create: `app/src/main/java/me/huanlin/gbuca/ui/components/ReminderSettings.kt`
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/SettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `ReminderScheduler.canScheduleExact()` / `requestExactPermission()`。
- Produces:
  - `data class PermissionStates(notifications: Boolean, exactAlarm: Boolean, batteryWhitelisted: Boolean)`
  - `@Composable fun rememberPermissionStates(scheduler: ReminderScheduler): MutableState<PermissionStates>`
  - `@Composable fun PermissionChecklist(scheduler: ReminderScheduler, modifier: Modifier = Modifier)`
  - `@Composable fun ReminderControls(enabled: Boolean, minutes: Int, onEnabledChange: (Boolean) -> Unit, onMinutesChange: (Int) -> Unit, modifier: Modifier = Modifier)`

- [ ] **Step 1: strings.xml —— 删除被取代的旧条目**

删除以下 5 行（新组件用统一的 `perm_*` 文案）：

```xml
    <string name="settings_exact_alarm_missing">未授予精确闹钟权限，提醒可能延迟</string>
    <string name="settings_grant_exact_alarm">授予精确闹钟权限</string>
    <string name="settings_notif_missing">未授予通知权限</string>
    <string name="settings_grant_notif">授予通知权限</string>
    <string name="settings_battery_whitelist">电池优化白名单（可选）</string>
```

- [ ] **Step 2: strings.xml —— 新增权限文案**

在「设置」区块内 `settings_reminder_minutes_chip` 之后插入：

```xml
    <string name="perm_notification">通知权限</string>
    <string name="perm_exact_alarm">精确闹钟权限</string>
    <string name="perm_battery">后台运行（电池优化白名单）</string>
    <string name="perm_grant">去授权</string>
    <string name="perm_status_granted">已允许</string>
    <string name="perm_status_missing">未允许</string>
    <string name="perm_status_optional">未加入（可选）</string>
```

- [ ] **Step 3: 新建 `ui/components/ReminderSettings.kt`**

```kotlin
package me.huanlin.gbuca.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.huanlin.gbuca.R
import me.huanlin.gbuca.reminder.ReminderScheduler

/** 提醒相关三项权限的当前状态。 */
data class PermissionStates(
    val notifications: Boolean,
    val exactAlarm: Boolean,
    /** true = 已加入电池优化白名单（后台可稳定运行）。 */
    val batteryWhitelisted: Boolean,
)

private val REMINDER_MINUTES = listOf(5, 10, 15, 20, 30)

/** 读取三项权限状态；非 Composable，便于在回调中主动刷新。 */
fun readPermissionStates(context: Context, scheduler: ReminderScheduler): PermissionStates = PermissionStates(
    notifications = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED,
    exactAlarm = scheduler.canScheduleExact(),
    batteryWhitelisted = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(context.packageName),
)

/**
 * 权限状态的可观察快照：ON_RESUME 时重新读取，从系统设置返回后立即刷新。
 * 返回值可直接赋值刷新（运行时权限回调里用）。
 */
@Composable
fun rememberPermissionStates(scheduler: ReminderScheduler): MutableState<PermissionStates> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf(readPermissionStates(context, scheduler)) }
    DisposableEffect(lifecycleOwner, context, scheduler) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.value = readPermissionStates(context, scheduler)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

/** 三行权限清单：状态 + 授权按钮。OOBE 权限步与设置页共用。 */
@Composable
fun PermissionChecklist(scheduler: ReminderScheduler, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val states = rememberPermissionStates(scheduler)
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 对话框关闭后主动重读，不依赖 ON_RESUME 是否触发
        states.value = readPermissionStates(context, scheduler)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PermissionRow(
            title = stringResource(R.string.perm_notification),
            granted = states.value.notifications,
            onAction = {
                if (Build.VERSION.SDK_INT >= 33) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
        PermissionRow(
            title = stringResource(R.string.perm_exact_alarm),
            granted = states.value.exactAlarm,
            onAction = { scheduler.requestExactPermission() },
        )
        PermissionRow(
            title = stringResource(R.string.perm_battery),
            granted = states.value.batteryWhitelisted,
            optional = true,
            onAction = { requestIgnoreBatteryOptimizations(context) },
        )
    }
}

/** 上课提醒开关 + 提前分钟数。OOBE 提醒步与设置页共用。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReminderControls(
    enabled: Boolean,
    minutes: Int,
    onEnabledChange: (Boolean) -> Unit,
    onMinutesChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_enable_reminders), Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        Column {
            Text(
                stringResource(R.string.settings_reminder_minutes_label),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            // FlowRow：窄屏/大字号时整只换行到下一行，避免 chip 被压缩后 label 逐字竖排
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                REMINDER_MINUTES.forEach { min ->
                    FilterChip(
                        selected = minutes == min,
                        onClick = { onMinutesChange(min) },
                        label = {
                            Text(
                                stringResource(R.string.settings_reminder_minutes_chip, min),
                                maxLines = 1,
                                softWrap = false,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    onAction: () -> Unit,
    optional: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(
                    when {
                        granted -> R.string.perm_status_granted
                        optional -> R.string.perm_status_optional
                        else -> R.string.perm_status_missing
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        if (!granted) {
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onAction) { Text(stringResource(R.string.perm_grant)) }
        }
    }
}

/** 优先弹出本应用的电池优化对话框；OEM 不支持时回退到系统列表页。 */
private fun requestIgnoreBatteryOptimizations(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
    val ok = runCatching { context.startActivity(direct) }.isSuccess
    if (!ok) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
```

- [ ] **Step 4: SettingsScreen 复用组件**

`app/src/main/java/me/huanlin/gbuca/ui/SettingsScreen.kt`：

1. 删除第 78-88 行的 `notifGranted` / `notifLauncher` 局部状态。
2. 把「提醒」分组（第 181-243 行）整体替换为：

```kotlin
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
```

3. 删除不再使用的 import：`android.Manifest`、`android.os.Build`、`android.provider.Settings`、
   `androidx.compose.foundation.layout.ExperimentalLayoutApi`、
   `androidx.compose.foundation.layout.FlowRow`、`androidx.compose.material3.FilterChip`、
   `androidx.compose.material3.Switch`。
4. `@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)` 改为
   `@OptIn(ExperimentalMaterial3Api::class)`。
5. 新增 import：

```kotlin
import me.huanlin.gbuca.ui.components.PermissionChecklist
import me.huanlin.gbuca.ui.components.ReminderControls
```

- [ ] **Step 5: 编译 + 单测**

Run: `gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，既有单测全部通过

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/ui/components/ReminderSettings.kt app/src/main/java/me/huanlin/gbuca/ui/SettingsScreen.kt app/src/main/res/values/strings.xml
git commit -m "refactor(ui): 权限清单与提醒控件抽为共用组件（设置页复用）"
```

---

### Task 4: OOBE 向导五步

**Files:**
- Create: `app/src/main/java/me/huanlin/gbuca/ui/SetupFlowScreen.kt`
- Delete: `app/src/main/java/me/huanlin/gbuca/ui/SetupScreen.kt`
- Modify: `app/src/main/java/me/huanlin/gbuca/data/local/SettingsStore.kt`
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/AppViewModel.kt`
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `OobeFlow`/`OobeStep`（Task 1）、`HostProbe`/`ProbeResult`/`ProbeFailure`（Task 2）、
  `PermissionChecklist`/`ReminderControls`/`rememberPermissionStates`（Task 3）、
  `AppViewModel.login(u, p, onSuccess)`、`setRemindersEnabled`、`setReminderMinutes`。
- Produces:
  - `@Composable fun SetupFlowScreen(vm: AppViewModel, reminderScheduler: ReminderScheduler, startStep: OobeStep, onOpenWebLogin: () -> Unit, onFinished: () -> Unit)`
  - `SettingsStore.oobeDone: Boolean`
  - `AppViewModel.credentialUsername: String?`、`hasCredentials: Boolean`、`needsOobe: Boolean`、
    `oobeStartStep(): OobeStep`、`finishOobe()`、`completeSetup(jwxtHost: String, iaaaHost: String)`

- [ ] **Step 1: SettingsStore 新增 `oobeDone`**

在 `app/src/main/java/me/huanlin/gbuca/data/local/SettingsStore.kt` 的 `iaaaHost` 之后插入：

```kotlin
    /** OOBE 是否已完整走过（含权限与提醒步骤）；老安装由门控判定为已完成，不写该标记。 */
    var oobeDone: Boolean
        get() = prefs.getBoolean("oobe_done", false)
        set(v) = prefs.edit { putBoolean("oobe_done", v) }
```

- [ ] **Step 2: AppViewModel 门控与状态**

在 `app/src/main/java/me/huanlin/gbuca/ui/AppViewModel.kt`：

1. 新增 import：

```kotlin
import me.huanlin.gbuca.domain.oobe.OobeFlow
import me.huanlin.gbuca.domain.oobe.OobeStep
```

2. 把「服务器地址（OOBE / 设置页）」区块里的 `completeSetup` 替换为下列内容
   （**签名去掉 `onDone` 回调**）：

```kotlin
    // ---- 服务器地址与 OOBE（向导 / 设置页） ----

    val jwxtHost: String get() = settings.jwxtHost
    val iaaaHost: String get() = settings.iaaaHost

    /** OOBE 是否已完成（两个地址均已配置）；未完成时不做任何网络访问。 */
    val serverConfigured: Boolean
        get() = settings.jwxtHost.isNotBlank() && settings.iaaaHost.isNotBlank()

    /** 已保存的学号；无凭据时 null。 */
    val credentialUsername: String? get() = app.creds.username

    val hasCredentials: Boolean get() = app.creds.username != null

    /**
     * 是否需要走 OOBE 向导。
     * 已配置地址且已有凭据的既有安装（v0.0.5 及以前）静默视为完成，不打扰。
     */
    val needsOobe: Boolean
        get() = !settings.oobeDone && !(serverConfigured && hasCredentials)

    /** 自动进入向导时的起始步（第一个未完成的步骤）。 */
    fun oobeStartStep(): OobeStep = OobeFlow.startStep(serverConfigured, hasCredentials)

    /** 向导「开始使用」：写完成标记。 */
    fun finishOobe() {
        settings.oobeDone = true
    }

    /** 向导地址步：保存服务器地址（调用方已用 HostNormalizer 校验）。 */
    fun completeSetup(jwxtHost: String, iaaaHost: String) {
        settings.jwxtHost = jwxtHost
        settings.iaaaHost = iaaaHost
    }
```

- [ ] **Step 3: strings.xml 新增向导文案**

在「OOBE 服务器配置向导」区块的 `setup_next` 之后插入：

```xml
    <string name="oobe_progress">第 %1$d 步 / 共 %2$d 步</string>
    <string name="oobe_previous">上一步</string>
    <string name="oobe_continue_anyway">仍然继续</string>
    <string name="oobe_start">开始使用</string>
    <string name="oobe_probe_checking">正在检测地址…</string>
    <string name="oobe_probe_failed">无法连接：%1$s。地址可能填错，请核对后重试。</string>
    <string name="oobe_probe_reason_dns">域名不存在</string>
    <string name="oobe_probe_reason_timeout">连接超时</string>
    <string name="oobe_probe_reason_tls">HTTPS 证书校验失败</string>
    <string name="oobe_probe_reason_other">网络不可达</string>
    <string name="oobe_login_title">登录教务系统</string>
    <string name="oobe_login_subtitle">使用学号与密码登录 iAAA，成功后自动同步课表</string>
    <string name="oobe_login_already">已登录：学号 %1$s</string>
    <string name="oobe_login_switch">切换账号</string>
    <string name="oobe_login_back_to_address">返回上一步检查地址</string>
    <string name="oobe_perm_title">开启提醒权限</string>
    <string name="oobe_perm_subtitle">上课提醒依赖以下权限</string>
    <string name="oobe_perm_hint">现在跳过也没关系，之后可在「设置 → 上课提醒」随时开启。</string>
    <string name="oobe_reminder_title">设置上课提醒</string>
    <string name="oobe_reminder_subtitle">提醒会在上课前按你选择的提前量推送</string>
    <string name="oobe_done_title">一切就绪</string>
    <string name="oobe_done_subtitle">以下是你的配置，确认后即可开始使用</string>
    <string name="oobe_summary_jwxt">教务系统</string>
    <string name="oobe_summary_iaaa">统一认证</string>
    <string name="oobe_summary_account">账号</string>
    <string name="oobe_summary_account_none">未登录</string>
    <string name="oobe_summary_reminder">上课提醒</string>
    <string name="oobe_summary_reminder_on">提前 %1$d 分钟</string>
    <string name="oobe_summary_reminder_off">已关闭</string>
    <string name="oobe_summary_notification">通知权限</string>
    <string name="oobe_summary_exact_alarm">精确闹钟</string>
    <string name="oobe_summary_battery">电池优化白名单</string>
    <string name="oobe_summary_allowed">已允许</string>
    <string name="oobe_summary_denied">未允许</string>
    <string name="oobe_summary_joined">已加入</string>
    <string name="oobe_summary_not_joined">未加入（可选）</string>
```

- [ ] **Step 4: 新建 `ui/SetupFlowScreen.kt`**

```kotlin
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

    fun probeAndAdvance() {
        val j = jHost ?: return
        val i = iHost ?: return
        focus.clearFocus()
        vm.completeSetup(j, i)
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
            if (unreachable == null) onNext() else failure = unreachable.failure
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
        onNext = { if (failure != null) onNext() else probeAndAdvance() },
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
```

- [ ] **Step 5: 删除旧的 `ui/SetupScreen.kt`**

```bash
git rm app/src/main/java/me/huanlin/gbuca/ui/SetupScreen.kt
```

- [ ] **Step 6: MainActivity 门控**

把 `app/src/main/java/me/huanlin/gbuca/ui/MainActivity.kt` 的 `setContent { ... }` 内容替换为：

```kotlin
        setContent {
            GbuCaTheme {
                val vm: AppViewModel = viewModel(factory = AppViewModel.Factory)
                // null = 不在向导中；非 null = 向导入口步（老用户已配置+已登录时为 null）
                var oobeStart by rememberSaveable {
                    mutableStateOf(if (vm.needsOobe) vm.oobeStartStep() else null)
                }
                var loggedIn by rememberSaveable { mutableStateOf(app.creds.username != null) }
                when (val start = oobeStart) {
                    null -> if (loggedIn) {
                        AppNavHost(
                            vm = vm,
                            onOpenWebLogin = { WebLoginActivity.start(this) },
                            reminderScheduler = app.reminderScheduler,
                        )
                    } else {
                        LoginScreen(
                            vm = vm,
                            onOpenWebLogin = { WebLoginActivity.start(this) },
                            onLoggedIn = { loggedIn = true },
                        )
                    }
                    else -> SetupFlowScreen(
                        vm = vm,
                        reminderScheduler = app.reminderScheduler,
                        startStep = start,
                        onOpenWebLogin = { WebLoginActivity.start(this) },
                        onFinished = {
                            oobeStart = null
                            loggedIn = app.creds.username != null
                        },
                    )
                }
            }
        }
```

新增 import：`me.huanlin.gbuca.domain.oobe.OobeStep`（`mutableStateOf`、`rememberSaveable`、`getValue`、`setValue` 已存在）。

> 注：Task 5 会在这里补 `onRerunOobe`；本步先保证可编译。

- [ ] **Step 7: 编译 + 全量单测**

Run: `gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL（新增 11 个用例 + 既有 47 个全部通过）

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/domain/oobe app/src/main/java/me/huanlin/gbuca/data/remote/HostProbe.kt app/src/main/java/me/huanlin/gbuca/ui/components/ReminderSettings.kt app/src/main/java/me/huanlin/gbuca/ui/SetupFlowScreen.kt app/src/main/java/me/huanlin/gbuca/ui/MainActivity.kt app/src/main/java/me/huanlin/gbuca/ui/AppViewModel.kt app/src/main/java/me/huanlin/gbuca/data/local/SettingsStore.kt app/src/main/res/values/strings.xml
git commit -m "feat(oobe): 五步向导（地址探测/登录/权限/提醒/完成 + 上一步与返回键）"
```

---

### Task 5: 设置页「重新运行向导」入口

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/SettingsScreen.kt`
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/AppNavHost.kt`
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/MainActivity.kt`

**Interfaces:**
- Consumes: `SetupFlowScreen`（Task 4）、`AppViewModel.needsOobe`。
- Produces:
  - `AppNavHost(vm, onOpenWebLogin, onRerunOobe: () -> Unit, reminderScheduler)`
  - `SettingsScreen(reminderScheduler, vm, onRerunOobe: () -> Unit)`

- [ ] **Step 1: strings.xml 新增入口文案**

在「设置」区块 `settings_section_export` 之前插入：

```xml
    <string name="settings_section_wizard">向导</string>
    <string name="settings_wizard_hint">重新走一遍首次使用向导，逐步确认服务器地址、登录、权限与提醒设置。</string>
    <string name="settings_rerun_wizard">重新运行向导</string>
```

- [ ] **Step 2: SettingsScreen 增加入口**

1. 函数签名加参数：

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    reminderScheduler: ReminderScheduler,
    vm: AppViewModel,
    onRerunOobe: () -> Unit,
) {
```

2. 在「导出到日历」分组之前插入：

```kotlin
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
```

- [ ] **Step 3: AppNavHost 传递回调**

`app/src/main/java/me/huanlin/gbuca/ui/AppNavHost.kt`：

1. `AppNavHost` 签名加 `onRerunOobe: () -> Unit`（放在 `onOpenWebLogin` 之后）：

```kotlin
@Composable
fun AppNavHost(
    vm: AppViewModel,
    onOpenWebLogin: () -> Unit,
    onRerunOobe: () -> Unit,
    reminderScheduler: ReminderScheduler,
) {
```

2. `composable("tabs")` 里传给 `TabsScreen`：

```kotlin
            TabsScreen(
                vm = vm,
                onOpenCourse = { rwh -> nav.navigate("course/$rwh") },
                onOpenWebLogin = onOpenWebLogin,
                onRerunOobe = onRerunOobe,
                reminderScheduler = reminderScheduler,
            )
```

3. `TabsScreen` 签名加同名参数，并在 `SettingsScreen` 调用处传入：

```kotlin
                2 -> SettingsScreen(
                    reminderScheduler = reminderScheduler,
                    vm = vm,
                    onRerunOobe = onRerunOobe,
                )
```

- [ ] **Step 4: MainActivity 接线**

在 `AppNavHost(...)` 调用中补一行：

```kotlin
                            onRerunOobe = { oobeStart = OobeStep.Address },
```

- [ ] **Step 5: 编译 + 单测 + lint**

Run: `gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug`
Expected: BUILD SUCCESSFUL；lint 0 errors

- [ ] **Step 6: 提交**

```bash
git add app/src/main/res/values/strings.xml app/src/main/java/me/huanlin/gbuca/ui/SettingsScreen.kt app/src/main/java/me/huanlin/gbuca/ui/AppNavHost.kt app/src/main/java/me/huanlin/gbuca/ui/MainActivity.kt
git commit -m "feat(oobe): 设置页「重新运行向导」入口"
```

---

### Task 6: 全量验证与文档

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 全量验证**

Run: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
Expected: BUILD SUCCESSFUL，单测 58 个通过（既有 47 + 新增 11），lint 0 errors

- [ ] **Step 2: README 补充向导描述**

在「功能」列表 `- **后台同步**` 之前插入：

```markdown
- **首次使用向导**：地址 → 登录 → 权限 → 提醒 → 完成 五步，每步可回退；填错地址会做轻量连通性探测并给出原因，设置页可随时重跑
```

- [ ] **Step 3: 提交**

```bash
git add README.md
git commit -m "docs: README 补充 OOBE 五步向导"
```

- [ ] **Step 4: 更新实施计划勾选**

把本文件中所有 `- [ ]` 改为 `- [x]`，提交：

```bash
git add docs/plans/2026-09-08-oobe-wizard-impl.md
git commit -m "docs: OOBE 向导化实施计划勾选完成"
```

## Self-Review

- **Spec 覆盖**：五步划分（Task 4 `OobeFlow.steps`）✓ 每步可回退 + 返回键（Task 4 `BackHandler`/`OobeFooter`）✓
  老用户不打扰（Task 4 `needsOobe`）✓ 轻量探测可继续（Task 2 + Task 4 `AddressStep`）✓
  权限三项 + 可跳过（Task 3 `PermissionChecklist`）✓ 提醒设置（Task 3 `ReminderControls`）✓
  完成页摘要（Task 4 `DoneStep`）✓ 设置页重跑入口（Task 5）✓ 组件抽取去重（Task 3）✓
  探测原因不硬编码中文（Task 2 枚举 + Task 4 `probeReasonOf`）✓ 密码不入 Bundle（Task 4 `remember`）✓
- **类型一致性**：`OobeStep`/`OobeFlow` 在 Task 1 定义，Task 4/5 使用一致；
  `ProbeFailure` 枚举名在 Task 2 与 Task 4 `probeReasonOf` 一致；
  `PermissionStates.batteryWhitelisted` 在 Task 3 定义、Task 4 `DoneStep` 使用一致；
  `completeSetup` 去掉 `onDone` 参数后，Task 4 是唯一调用点（旧 `SetupScreen.kt` 已删除）。
- **占位符扫描**：无 TBD/TODO；每个代码步骤含完整可编译代码。
