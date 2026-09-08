# 设计：OOBE 向导化（可回退、含权限与提醒设置）

日期：2026-09-08
状态：已确认（用户逐节审批通过）

## 背景与问题

现状（v0.0.5）的首次使用流程只有两屏：`SetupScreen`（填两个域名）→ `LoginScreen`（登录）。
`MainActivity` 用一个 `needsSetup: Boolean` 做门控，`SetupScreen` 只有单向的「下一步」。

由此产生三个真实缺陷：

1. **填错地址是死胡同。** 地址一旦提交就没有任何回退入口：登录页没有「返回」，
   设置页要登录成功后才能进入。用户填错域名 → 登录失败 → 只能卸载重装。
2. **不像 OOBE。** 没有步骤感、没有进度、没有系统返回键回退、没有完成确认页。
3. **关键设置缺席。** 上课提醒依赖通知权限与精确闹钟权限，二者在 OOBE 里完全没提，
   用户往往第一次收不到提醒才去设置页翻找；提醒开关与提前分钟数同样没在 OOBE 里确认过。

## 需求决策记录

| 决策点 | 结论 |
| --- | --- |
| 步骤划分 | 地址 → 登录 → 权限 → 提醒 → 完成（5 步，权限与提醒各自独立成步） |
| 回退 | 每步「上一步」+ 系统返回键回退；首步按返回键退出 Activity |
| 老用户升级 | 已配置地址且已有凭据 → 静默视为完成，一次也不打扰 |
| 地址连通性 | 轻量探测：不通只警告（给出原因），仍可「仍然继续」 |
| 权限步 | 可跳过，未授予只给黄色提示，不硬拦 |
| 完成页 | 摘要（地址/账号/提醒/权限状态）+「开始使用」 |
| 设置页入口 | 新增「重新运行向导」，全部字段从当前值预填 |
| 明确不做 | 多域名、http、地址历史、向导内注册账号、改变登录语义（凭据登录 + 网页登录兜底保持现状） |

## 方案比选

- **A（采用）单 Composable + 纯 Kotlin 步骤机**：`domain/oobe/OobeFlow.kt` 只放
  `enum OobeStep` 与 `next/previous/startStep`（零 Android 依赖、可直接单测）；
  `ui/SetupFlowScreen.kt` 用 `when (step)` 渲染各步，`BackHandler` 接管返回键。
  优点：不引入嵌套 NavHost、无路由字符串、步骤逻辑可单测、与主应用导航彻底解耦。
  代价：转场动画手写一个 `AnimatedContent`。
- B（否决）嵌套 NavHost：能白拿返回栈与现成转场，但 OOBE 是线性流程，用不上图导航能力，
  却要付路由与参数传递的样板，且「回退到首步」的语义仍需特殊处理。
- C（否决）每步一个 Activity：状态跨进程传递最重，旋转/重建成本最高。

## 架构与数据流

```
SettingsStore (jwxtHost / iaaaHost / oobeDone / remindersEnabled / reminderMinutes)
      │
      ├─→ AppViewModel.needsOobe  ──→ MainActivity 门控 ──→ SetupFlowScreen
      │                                                        │
      │                          OobeFlow（纯 Kotlin 步骤机）───┤
      │                                                        ├─ Address   → HostProbe
      │                                                        ├─ Login     → GbuClient
      │                                                        ├─ Permissions → ReminderScheduler
      │                                                        ├─ Reminders → AppViewModel
      │                                                        └─ Done      → finishOobe()
      └─→ SettingsScreen ──复用──> ui/components/ReminderSettings.kt
```

## 组件设计

### 1. `domain/oobe/OobeFlow.kt`（新建，纯 Kotlin）

```kotlin
enum class OobeStep { Address, Login, Permissions, Reminders, Done }

object OobeFlow {
    val steps: List<OobeStep>                 // 固定顺序，共 5 步
    fun next(step: OobeStep): OobeStep?       // Done → null
    fun previous(step: OobeStep): OobeStep?   // Address → null
    fun startStep(hostsConfigured: Boolean, hasCredentials: Boolean): OobeStep
    fun progressOf(step: OobeStep): Pair<Int, Int>?   // Done 返回 null（不显示进度）
}
```

- `startStep`：无地址 → `Address`；有地址无凭据 → `Login`；否则 → `Permissions`。
  设置页「重新运行向导」显式传 `Address`。
- `progressOf`：`Address..Reminders` → `(1..4, 4)`；`Done` → `null`。

### 2. `data/remote/HostProbe.kt`（新建）

```kotlin
sealed interface ProbeResult {
    data object Reachable : ProbeResult
    data class Unreachable(val reason: String) : ProbeResult
}

object HostProbe {
    suspend fun probe(host: String): ProbeResult
    fun reasonOf(e: IOException): String   // 纯函数，可单测
}
```

- 独立 `OkHttpClient`（connect/read 6s、callTimeout 10s），**不复用** `GbuClient` 的
  apiClient（避免 Referer 拦截器与 CookieJar 干扰）。
- 请求 `HEAD https://host/`；**任何 HTTP 响应（含 302/401/403/405）都算可达**，
  只有 `IOException` 才算不可达 —— 避免认证失败、HEAD 被拒、重定向造成假阴性。
- `reasonOf` 映射：`UnknownHostException` → 域名不存在；`SocketTimeoutException` → 连接超时；
  `SSLException` → HTTPS 证书校验失败；其他 `IOException` → 无法连接。

### 3. `ui/SetupFlowScreen.kt`（新建，替换 `SetupScreen.kt`）

- 入参：`vm: AppViewModel`、`reminderScheduler: ReminderScheduler`、`startStep: OobeStep`、
  `onFinished: () -> Unit`。
- 步骤状态 `var step by rememberSaveable { mutableStateOf(startStep) }`；
  `BackHandler(enabled = true)` → `OobeFlow.previous(step)`，为 null 时交给系统（退出 Activity）。
- 顶部：步骤进度「第 n 步 / 共 4 步」+ 标题 + 说明；底部：「上一步」（首步隐藏）/「下一步」。
- 转场：`AnimatedContent` 水平滑入滑出（与 `AppNavHost` 的 300ms 保持一致）。
- 跨步保留的输入（用户名）提升到本 Composable 的 `rememberSaveable`；
  **密码只用 `remember`，绝不进 Bundle**。地址与提醒值每步从 `vm`/`settings` 重新读取。
- 删除 `ui/SetupScreen.kt`（被本文件取代）。

各步内容：

| 步 | 内容 | 前进条件 |
| --- | --- | --- |
| Address | 两个输入框（预填已保存值）+ 格式校验 | 规范化成功 → 并行探测两个 host → 全部可达即前进；否则显示原因，按钮变「仍然继续」 |
| Login | 学号/密码 + 「使用网页登录」兜底；已有凭据时显示「已登录：学号 X」并可切换账号 | 登录成功，或已有凭据 |
| Permissions | 通知 / 精确闹钟 / 电池优化 三行（复用组件） | 始终可前进；未授予仅黄色提示 |
| Reminders | 提醒开关 + 提前 5/10/15/20/30 分钟（复用组件） | 始终可前进 |
| Done | 摘要 + 「开始使用」 | `vm.finishOobe()` → `onFinished()` |

### 4. `ui/components/ReminderSettings.kt`（新建，消除重复）

- `PermissionChecklist(scheduler: ReminderScheduler, modifier: Modifier)`：
  三行「权限名 + 状态 + 授权按钮」；用 `androidx.lifecycle.compose.LocalLifecycleOwner` 的
  `ON_RESUME` 重读状态，从系统设置返回即时刷新。
  - 通知：33+ 运行时请求 `POST_NOTIFICATIONS`；<33 视为已允许。
  - 精确闹钟：31+ `canScheduleExactAlarms()`；按钮走 `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`。
  - 电池优化：`PowerManager.isIgnoringBatteryOptimizations(pkg)`；按钮优先
    `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（manifest 已声明该权限，直弹本应用对话框），
    失败回退现有 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` 列表页。
- `ReminderControls(enabled, minutes, onEnabledChange, onMinutesChange)`：
  开关 + `FlowRow` chips（保留现有「窄屏整只换行」的处理）。
- `SettingsScreen` 改为复用这两个组件，删除其中的重复实现。

### 5. 门控与迁移（`MainActivity` / `AppViewModel` / `SettingsStore`）

- `SettingsStore.oobeDone: Boolean`（新 prefs 键，默认 false）。
- `AppViewModel.needsOobe: Boolean = !settings.oobeDone && !(serverConfigured && creds.username != null)`
  —— 已配置地址且已登录的老用户静默视为完成，不弹向导、不写标记。
- `AppViewModel.finishOobe()` 写 `oobeDone = true`；`AppViewModel.oobeStartStep()` 委托 `OobeFlow.startStep`。
- `MainActivity`：

```kotlin
var showOobe by rememberSaveable { mutableStateOf(vm.needsOobe) }
when {
    showOobe -> SetupFlowScreen(vm, app.reminderScheduler, vm.oobeStartStep()) { showOobe = false }
    loggedIn -> AppNavHost(..., onRerunOobe = { showOobe = true })
    else -> LoginScreen(...)
}
```

### 6. 设置页入口

- 「关于」分组上方新增 `OutlinedButton`「重新运行向导」。
- `onRerunOobe: () -> Unit` 沿 `AppNavHost → TabsScreen → SettingsScreen` 传递
  （与现有 `onOpenWebLogin` 同一路径，不引入新的全局状态）。

## 错误处理与边界

- 地址探测失败**不拦截**，只提示原因 + 「仍然继续」；修改任一输入框即清除警告。
- 登录失败：沿用 `friendlyError`；错误文案旁额外给「返回上一步检查地址」按钮
  （正是本次要修的场景）。
- 旋转 / 进程重建：步骤索引与用户名 `rememberSaveable`；密码不入 Bundle。
- 已有凭据时登录步不强制重登（避免「重新运行向导」变成强制重新登录）。
- 用户从系统设置返回后权限状态即时刷新（`ON_RESUME`）。
- 完成步之后若无凭据（理论不可达），`MainActivity` 仍会落到 `LoginScreen`，不会白屏。

## 测试

- 新增 JVM 单测 `OobeFlowTest`：`next`/`previous` 全枚举遍历、`Address.previous == null`、
  `Done.next == null`、`startStep` 三种组合、`progressOf` 边界。
- 新增 JVM 单测 `HostProbeReasonTest`：`UnknownHostException`/`SocketTimeoutException`/
  `SSLException`/其他 `IOException` → 文案映射，且永不为空。
- 既有 47 个单测保持通过（`SetupScreen` 无单测，删除不影响）。
- 手动清单：
  1. 首次启动走完 5 步，完成页「开始使用」进主界面；
  2. 每步「上一步」与系统返回键；
  3. 填错域名 → 探测警告（含原因）→「仍然继续」→ 登录失败 → 「返回上一步检查地址」→ 改对 → 通过；
  4. 旋转设备步骤不丢；
  5. 权限步从系统设置返回后状态刷新；
  6. 设置页「重新运行向导」全部预填、不强制重登；
  7. 已配置地址 + 已登录的安装升级后不弹向导。

## 文档

- 新增 `docs/plans/2026-09-08-oobe-wizard-{design,impl}.md`。
- README「首次使用」相关描述同步为 5 步向导。
- 新增文案全部进 `strings.xml`，不硬编码中文；`SetupScreen` 相关旧字符串按需保留或删除。
