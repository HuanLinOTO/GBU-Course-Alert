# 设计：今日休息（一键静音当天全部提醒）

日期：2026-09-08
状态：已确认（用户逐节审批通过）

## 背景与问题

现状的提醒只有两个全局开关：设置页的「上课提醒」总开关与「提前 N 分钟」。
它们都是**长期语义**——关掉就是永久关掉，改分钟数会影响所有天。

真实场景里缺的是**当天**的临时静音：某天身体不适、请假、出差、调休，
今天不想再被课程提醒和上课倒计时打扰，但**明天要照常提醒**。
用现有的总开关实现这件事，用户第二天必须记得手动打开——忘一次就是全天静音。

桌面小组件同样没有这个信息：静音当天小组件照常显示课程，
用户看到「下节课 08:00」却收不到提醒，会以为提醒坏了。

## 需求决策记录

| 决策点 | 结论 |
| --- | --- |
| 按钮位置 | 今日页最顶部（风控提示卡与日期条之上），固定在滚动区外 |
| 按钮文案 | 未休息：「休息」；休息中：「休息中 · 点此恢复」 |
| 再次点击 | 切换回正常状态，并立即恢复今天的提醒 |
| 取消范围 | 精确闹钟提醒 **+** Live Update 常驻倒计时（即 `ReminderScheduler.cancelAll()` 的全部效果） |
| 生效期 | 仅当日；跨零点自动失效，无需任何定时清理 |
| 休息中提示 | 按钮下方一条小字：「今日休息中，不会收到任何课程提醒」 |
| 小组件 | 周次行下方新增一行「今日休息」 |
| 明确不做 | 「休息到某天」/长期免打扰、按课程静音、休息历史记录、修改全局提醒开关语义、ICS 导出改动 |

## 方案比选

- **A（采用）存「休息日」日期，判定时与今天比较**：`SettingsStore.restDay: Long?` 存 epochDay，
  是否休息 = `restDay == 今天`。跨零点自动失效，**零定时任务、零开机广播**。
  代价：判定散落各处会重复写比较逻辑 —— 用单一纯函数 `RestDay.isResting` 收口。
- B（否决）存布尔开关 `resting`，靠 WorkManager / 开机广播在零点清零：
  多一个「清理失败」的失败面。典型坑：用户 23:59 点休息，零点清理没跑成 →
  第二天整天静音且无任何提示。
- C（否决）不存状态，进入休息时逐条取消今天剩余闹钟：
  UI 无从知道当前是否休息（按钮要显示「休息中」就没有依据）；
  更致命的是用户一按「同步」，`reschedule()` 会把今天的闹钟重新排上，休息被静默冲掉。

## 架构与数据流

```
SettingsStore.restDay (epochDay: Long?)
      │
      ├─→ RestDay.isResting(restEpochDay, today)   ← 纯函数，唯一判定点
      │        ├─→ AppViewModel.resting  ──→ TodayScreen 按钮与提示条
      │        ├─→ ReminderScheduler.reschedule() 跳过休息日课次
      │        └─→ TodayWidget.loadState() ──→ 小组件「今日休息」行
      │
      └─→ AppViewModel.setResting(on)
               ├─ on  → cancelAll()（闹钟 + Live Update）
               └─ off → rescheduleAsync()
```

## 组件设计

### 1. `domain/reminder/RestDay.kt`（新建，纯 Kotlin）

```kotlin
object RestDay {
    /** 存储的休息日与今天相同即处于休息状态；null / 其他日期均不生效。 */
    fun isResting(restEpochDay: Long?, today: LocalDate): Boolean
}
```

- 「仅针对当日」这条语义的唯一实现点，UI、调度器、小组件共用，避免三处各写一遍比较。
- 无 Android 依赖，可直接单测。

### 2. `data/local/SettingsStore.kt`（改）

```kotlin
/** 「今日休息」的日期（epochDay）；null = 未休息。跨零点自动失效。 */
var restDay: Long?
    get() = prefs.getLong("rest_day", NO_REST).takeIf { it != NO_REST }
    set(v) = prefs.edit { if (v == null) remove("rest_day") else putLong("rest_day", v) }
```

- SharedPreferences 没有 `getLong` 的 null 语义，用 `NO_REST = Long.MIN_VALUE` 哨兵 + `takeIf` 还原。

### 3. `reminder/ReminderScheduler.kt`（改）

`reschedule()` 在遍历课次时跳过休息日：

```kotlin
if (RestDay.isResting(settings.restDay, date)) return@forEach  // 含 Live Update，一并跳过
```

- 跳过位置在 `dayList.forEachIndexed` 内、`scheduleForClass` 之前，
  因此**闹钟与 Live Update 同时被跳过**。
- 效果：休息期间 `SyncWorker`（12h 周期）、`BootReceiver`、设置页改动触发的重排
  **都不会把今天的提醒排回来**，而明天的提醒照常安排。
- 进入休息用现成的 `cancelAll()`：它已同时清空闹钟 key 与 `LiveUpdateNotifier.cancel()`，
  不必新增取消路径。

### 4. `ui/AppViewModel.kt`（改）

```kotlin
private val _resting = MutableStateFlow(RestDay.isResting(settings.restDay, LocalDate.now()))
val resting: StateFlow<Boolean> = _resting

fun setResting(on: Boolean) {
    settings.restDay = if (on) LocalDate.now().toEpochDay() else null
    _resting.value = on
    if (on) app.reminderScheduler.cancelAll() else app.reminderScheduler.rescheduleAsync()
    viewModelScope.launch { TodayWidgetReceiver.refreshAll(app) }
}
```

- 照 `setRemindersEnabled` 的既有写法（`StateFlow` + 副作用）。
- 小组件刷新与同步后的路径一致，不新增机制。

### 5. `ui/TodayScreen.kt`（改）

- 位置：`Scaffold` 内容 `Column` 的**第一个**子项，在 `ui.needWebLogin` 风控卡与 `DayPager` 之上，
  不在 `LazyColumn` 内 —— 保证休息中滚动课程列表时按钮与提示条始终可见。
- 未休息：全宽 `OutlinedButton`「休息」。
- 休息中：全宽 `FilledTonalButton`（`primaryContainer` 底色）「休息中 · 点此恢复」，
  下方一行 `bodySmall` / `onSurfaceVariant` 的「今日休息中，不会收到任何课程提醒」。
- 点击 → `vm.setResting(!resting)`。
- 仅当 `isToday`（`dayOffset == 0`）时显示 —— 翻到别的日期时该按钮无意义。

### 6. `widget/TodayWidget.kt`（改）

- `WidgetState` 新增 `resting: Boolean`。
- `loadState()` 用同一个 `RestDay.isResting(app.settings.restDay, now)`。
- `WidgetContent` 在周次行与课程列表之间渲染一行「今日休息」
  （`fontSize = 11.sp`、`GlanceTheme.colors.onSurfaceVariant`），仅在 `resting` 为 true 时出现。

### 7. `res/values/strings.xml`（改）

| key | 文案 |
| --- | --- |
| `today_rest_action` | 休息 |
| `today_rest_active` | 休息中 · 点此恢复 |
| `today_rest_hint` | 今日休息中，不会收到任何课程提醒 |
| `widget_rest` | 今日休息 |

## 错误处理与边界

- **休息中同步**：`reschedule` 跳过今天 → 休息保持，不闪回提醒。
- **休息中重启 / 开机**：`BootReceiver` → `reschedule` 跳过今天 → 休息保持。
- **休息中正在上课**：`cancelAll()` 撤掉正在显示的常驻倒计时通知。
- **跨零点**：`restDay` 是昨天 → `isResting` 为 false，自动恢复正常；
  按钮与提示条在下次进入今日页时消失。
- **跨零点后的闹钟**：依赖既有的 `SyncWorker` 12h 周期重排（既有行为，本次不新增定时器）；
  若用户跨零点后打开 App 或手动同步，也会立即排上。
- **全局「上课提醒」已关闭时点休息**：按钮照常可用（状态独立），恢复时 `reschedule`
  内部本就会因 `remindersEnabled == false` 直接返回 0，无副作用。
- **翻到非今日**：不显示按钮，避免「给某一天设休息」这种超范围语义。

## 测试

- 新增 JVM 单测 `RestDayTest`：
  - `null` → 不休息；
  - 今天 → 休息；
  - 昨天 → 不休息（跨零点自动失效）；
  - 明天 → 不休息（防「预支休息」）。
- 新增 JVM 单测覆盖 `SettingsStore` 的 null 往返？`SettingsStore` 依赖 `Context`，
  既有测试体系里没有 Robolectric —— 不为这一处引入。哨兵逻辑由 `RestDayTest` 的
  「null → 不休息」间接覆盖（`NO_REST` 经 `takeIf` 还原为 null）。
- 既有 58 个单测保持通过。
- 手动清单（真机）：
  1. 点「休息」→ 按钮变「休息中 · 点此恢复」+ 提示条出现；
  2. `dumpsys notification` 中常驻倒计时消失、`dumpsys alarm` 中今天的闹钟清空；
  3. 休息中按「同步」→ 提醒不复现；
  4. 再点一次 → 提醒重新排上（`dumpsys alarm` 有今天的课次）；
  5. 小组件出现「今日休息」行，恢复后消失；
  6. 杀进程重开 → 休息状态保持。

## 文档

- 新增 `docs/plans/2026-09-08-rest-day-{design,impl}.md`。
- README 功能清单补充「今日休息」。
- 新增文案全部进 `strings.xml`，不硬编码中文。
