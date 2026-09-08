# 今日休息实施计划

> **For agentic workers:** 本计划按任务逐条执行，每个任务以「测试先行 → 实现 → 验证 → 提交」收尾。
> 步骤使用复选框跟踪进度。任务之间互相独立可评审，按顺序执行。

**Goal:** 在今日页顶部加一个「休息」按钮，一键取消当天全部提醒（精确闹钟 + Live Update 常驻倒计时），再次点击恢复；休息仅对当日生效，跨零点自动失效。

**Architecture:** 休息状态就是**一个日期**（`SettingsStore.restDay: Long?`，存 epochDay）。
是否休息由纯函数 `RestDay.isResting(restEpochDay, today)` 判定，UI / 调度器 / 小组件三处共用。
`ReminderScheduler.reschedule()` 跳过休息日的课次，因此同步、开机、改设置触发的重排都无法把休息冲掉。
进入休息复用现成的 `cancelAll()`（已同时清闹钟与 Live Update），不新增取消路径。

**Tech Stack:** Kotlin 2.4 · Jetpack Compose (BOM 2026.08) · Material 3 · Glance AppWidget ·
java.time · JUnit 4。
**不新增任何第三方依赖，不新增任何 Android 权限。**

**Spec:** `docs/plans/2026-09-08-rest-day-design.md`

## Global Constraints

- 不新增第三方依赖；不新增 Android 权限。
- 面向用户的文案一律走 `strings.xml`，**不硬编码中文**。
- Compose 中禁止 `context.getString(...)`（lint `LocalContextGetResourceValueCall`），一律 `stringResource`。
- 「仅针对当日」的语义**只允许**在 `RestDay.isResting` 里实现一次，禁止在 UI / 调度器 / 小组件各写一遍日期比较。
- 既有 58 个单测必须保持通过；新增单测为纯 JVM 测试（不引入 Robolectric）。
- 休息状态不得修改全局「上课提醒」开关（`remindersEnabled`）与「提前分钟数」。
- 提交信息沿用仓库风格（`feat(rest): …`），中文正文。

**文件结构**

| 文件 | 责任 |
| --- | --- |
| `app/src/main/java/me/huanlin/gbuca/domain/reminder/RestDay.kt`（新建） | 「仅当日」判定的唯一实现 |
| `app/src/main/java/me/huanlin/gbuca/data/local/SettingsStore.kt`（修改） | `restDay` 持久化 + `isRestingToday()` |
| `app/src/main/java/me/huanlin/gbuca/reminder/ReminderScheduler.kt`（修改） | 重排时跳过休息日课次 |
| `app/src/main/java/me/huanlin/gbuca/ui/AppViewModel.kt`（修改） | `resting` 状态、`setResting`、`refreshResting` |
| `app/src/main/java/me/huanlin/gbuca/ui/TodayScreen.kt`（修改） | 顶部按钮 + 提示条 + 返回前台重新判定 |
| `app/src/main/java/me/huanlin/gbuca/widget/TodayWidget.kt`（修改） | `WidgetState.resting` + 「今日休息」行 |
| `app/src/main/res/values/strings.xml`（修改） | `today_rest_*` / `widget_rest` |
| `app/src/test/java/me/huanlin/gbuca/RestDayTest.kt`（新建） | 「仅当日」判定单测 |

---

### Task 1: RestDay 纯函数

**Files:**
- Create: `app/src/main/java/me/huanlin/gbuca/domain/reminder/RestDay.kt`
- Test: `app/src/test/java/me/huanlin/gbuca/RestDayTest.kt`

**Interfaces:**
- Consumes: 无（纯 Kotlin + `java.time`）。
- Produces: `RestDay.isResting(restEpochDay: Long?, today: LocalDate): Boolean`

- [ ] **Step 1: 写失败测试** `app/src/test/java/me/huanlin/gbuca/RestDayTest.kt`

```kotlin
package me.huanlin.gbuca

import me.huanlin.gbuca.domain.reminder.RestDay
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RestDayTest {

    private val today = LocalDate.of(2026, 9, 8)

    @Test
    fun `no stored rest day means not resting`() {
        assertFalse(RestDay.isResting(null, today))
    }

    @Test
    fun `stored today means resting`() {
        assertTrue(RestDay.isResting(today.toEpochDay(), today))
    }

    @Test
    fun `yesterday expires at midnight`() {
        assertFalse(RestDay.isResting(today.minusDays(1).toEpochDay(), today))
    }

    @Test
    fun `tomorrow does not pre-empt`() {
        assertFalse(RestDay.isResting(today.plusDays(1).toEpochDay(), today))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `gradlew.bat :app:testDebugUnitTest --tests "me.huanlin.gbuca.RestDayTest"`
Expected: 编译失败（`Unresolved reference: RestDay`）

- [ ] **Step 3: 实现** `app/src/main/java/me/huanlin/gbuca/domain/reminder/RestDay.kt`

```kotlin
package me.huanlin.gbuca.domain.reminder

import java.time.LocalDate

/**
 * 「今日休息」判定：休息只对存储的那一天生效，跨零点自动失效。
 * 存储值为 epochDay，null 表示未休息。
 */
object RestDay {

    /** 存储的休息日与今天相同即处于休息状态；null / 其他日期均不生效。 */
    fun isResting(restEpochDay: Long?, today: LocalDate): Boolean =
        restEpochDay != null && restEpochDay == today.toEpochDay()
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `gradlew.bat :app:testDebugUnitTest --tests "me.huanlin.gbuca.RestDayTest"`
Expected: 4 个测试全部 PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/domain/reminder/RestDay.kt app/src/test/java/me/huanlin/gbuca/RestDayTest.kt
git commit -m "feat(rest): 新增「今日休息」当日判定纯函数与单测"
```

---

### Task 2: 持久化休息日 + 调度器跳过

**Files:**
- Modify: `app/src/main/java/me/huanlin/gbuca/data/local/SettingsStore.kt`（`remindersEnabled` 之后，约 64-66 行）
- Modify: `app/src/main/java/me/huanlin/gbuca/reminder/ReminderScheduler.kt:91-93`

**Interfaces:**
- Consumes: `RestDay.isResting(Long?, LocalDate)`（Task 1）。
- Produces:
  - `SettingsStore.restDay: Long?`（getter/setter）
  - `SettingsStore.isRestingToday(): Boolean`

- [ ] **Step 1: `SettingsStore` 增加 `restDay` 与 `isRestingToday()`**

在 `remindersEnabled` 之后插入：

```kotlin
    /** 「今日休息」的日期（epochDay）；null = 未休息。跨零点自动失效。 */
    var restDay: Long?
        get() = prefs.getLong(KEY_REST_DAY, NO_REST).takeIf { it != NO_REST }
        set(v) = prefs.edit {
            if (v == null) remove(KEY_REST_DAY) else putLong(KEY_REST_DAY, v)
        }

    /** 休息是否对今天生效（跨零点自动为 false）。 */
    fun isRestingToday(): Boolean = RestDay.isResting(restDay, LocalDate.now())
```

`companion object` 内追加：

```kotlin
        private const val KEY_REST_DAY = "rest_day"

        /** SharedPreferences 没有 null long：用哨兵值表示「未休息」。 */
        private const val NO_REST = Long.MIN_VALUE
```

文件顶部 import 追加：

```kotlin
import me.huanlin.gbuca.domain.reminder.RestDay
```

（`java.time.LocalDate` 已 import。）

- [ ] **Step 2: `ReminderScheduler.reschedule()` 跳过休息日**

把循环体开头：

```kotlin
        for (dayOffset in 0..hours / 24 + 1) {
            val date = now.toLocalDate().plusDays(dayOffset.toLong())
            val week = ScheduleLogic.weekOf(date, startMonday) ?: continue
```

改为：

```kotlin
        for (dayOffset in 0..hours / 24 + 1) {
            val date = now.toLocalDate().plusDays(dayOffset.toLong())
            // 「今日休息」：整日跳过（闹钟与 Live Update 一并跳过），
            // 因此同步 / 开机 / 改设置触发的重排都不会把今天的提醒排回来
            if (RestDay.isResting(settings.restDay, date)) continue
            val week = ScheduleLogic.weekOf(date, startMonday) ?: continue
```

文件顶部 import 追加：

```kotlin
import me.huanlin.gbuca.domain.reminder.RestDay
```

- [ ] **Step 3: 编译 + 全量单测**

Run: `gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL，62 个单测通过（既有 58 + RestDayTest 4）

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/data/local/SettingsStore.kt app/src/main/java/me/huanlin/gbuca/reminder/ReminderScheduler.kt
git commit -m "feat(rest): 持久化休息日并在重排时跳过当天课次"
```

---

### Task 3: ViewModel 休息状态

**Files:**
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/AppViewModel.kt`（`setReminderMinutes` 之后，约 42-46 行）

**Interfaces:**
- Consumes: `SettingsStore.restDay`、`SettingsStore.isRestingToday()`（Task 2）；`ReminderScheduler.cancelAll()`、`rescheduleAsync()`（既有）；`TodayWidgetReceiver.refreshAll(Context)`（既有，suspend）。
- Produces:
  - `AppViewModel.resting: StateFlow<Boolean>`
  - `AppViewModel.setResting(on: Boolean)`
  - `AppViewModel.refreshResting()`

- [ ] **Step 1: 实现**

在 `setReminderMinutes` 之后插入：

```kotlin
    /** 「今日休息」是否对今天生效；跨零点或从后台返回时由 [refreshResting] 重新判定。 */
    private val _resting = MutableStateFlow(settings.isRestingToday())
    val resting: StateFlow<Boolean> = _resting

    /**
     * 切换「今日休息」：开启即取消当天全部提醒（精确闹钟 + Live Update 常驻倒计时）；
     * 关闭即按当前设置重排。仅对当日生效，跨零点自动失效。
     */
    fun setResting(on: Boolean) {
        settings.restDay = if (on) java.time.LocalDate.now().toEpochDay() else null
        _resting.value = on
        if (on) app.reminderScheduler.cancelAll() else app.reminderScheduler.rescheduleAsync()
        viewModelScope.launch { me.huanlin.gbuca.widget.TodayWidgetReceiver.refreshAll(app) }
    }

    /** 重新判定休息状态（跨零点后 / 从后台回到前台）。 */
    fun refreshResting() {
        _resting.value = settings.isRestingToday()
    }
```

（`MutableStateFlow` / `StateFlow` / `viewModelScope.launch` 已 import；`TodayWidgetReceiver` 按既有风格用全限定名。）

- [ ] **Step 2: 编译 + 全量单测**

Run: `gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL，62 个单测通过

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/ui/AppViewModel.kt
git commit -m "feat(rest): ViewModel 暴露休息状态与切换入口"
```

---

### Task 4: 今日页顶部按钮与提示条

**Files:**
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/TodayScreen.kt`
- Modify: `app/src/main/res/values/strings.xml:84`（`today_periods` 之后）

**Interfaces:**
- Consumes: `AppViewModel.resting`、`AppViewModel.setResting`、`AppViewModel.refreshResting`（Task 3）。
- Produces: 私有 Composable `RestBar(resting: Boolean, isToday: Boolean, onToggle: () -> Unit)`。

- [ ] **Step 1: 新增文案** `app/src/main/res/values/strings.xml`

在 `today_periods` 之后插入：

```xml
    <string name="today_rest_action">休息</string>
    <string name="today_rest_active">休息中 · 点此恢复</string>
    <string name="today_rest_hint">今日休息中，不会收到任何课程提醒</string>
```

- [ ] **Step 2: 今日页接线**

`import` 追加：

```kotlin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
```

在 `val ui by vm.ui.collectAsState()` 之后插入：

```kotlin
    val resting by vm.resting.collectAsState()

    // 跨零点或从后台回到前台时重新判定：否则按钮会显示「休息中」但提醒其实已经恢复
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshResting()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
```

`Column(Modifier.padding(padding).fillMaxSize()) {` 之后的**第一个**子项插入：

```kotlin
            RestBar(resting = resting, isToday = isToday, onToggle = { vm.setResting(!resting) })
```

在本文件内新增私有 Composable（放在 `DayPager` 定义之后）：

```kotlin
/** 「今日休息」：一键静音当天全部提醒，再次点击恢复；仅今日页显示。 */
@Composable
private fun RestBar(resting: Boolean, isToday: Boolean, onToggle: () -> Unit) {
    if (!isToday) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (resting) {
            Button(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) { Text(stringResource(R.string.today_rest_active)) }
            Text(
                stringResource(R.string.today_rest_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            OutlinedButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.today_rest_action))
            }
        }
    }
}
```

- [ ] **Step 3: 编译 + 单测 + lint**

Run: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
Expected: BUILD SUCCESSFUL，62 个单测通过，lint 0 errors（既有 `WebLoginActivity.kt` 的 2 个 `UseKtx` warning 仍在）

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/ui/TodayScreen.kt app/src/main/res/values/strings.xml
git commit -m "feat(rest): 今日页顶部新增「休息」按钮与休息中提示"
```

---

### Task 5: 小组件「今日休息」行

**Files:**
- Modify: `app/src/main/java/me/huanlin/gbuca/widget/TodayWidget.kt`
- Modify: `app/src/main/res/values/strings.xml:201`（`widget_empty` 之后）

**Interfaces:**
- Consumes: `SettingsStore.restDay`（Task 2）、`RestDay.isResting`（Task 1）。
- Produces: `WidgetState.resting: Boolean`（第三个构造参数）。

- [ ] **Step 1: 新增文案** `app/src/main/res/values/strings.xml`

在 `widget_empty` 之后插入：

```xml
    <string name="widget_rest">今日休息</string>
```

- [ ] **Step 2: `WidgetState` 增加字段并填充**

`data class WidgetState` 改为：

```kotlin
data class WidgetState(
    val date: LocalDate,
    val week: Int?,
    /** true = 今天处于「休息」状态（提醒已静音）。 */
    val resting: Boolean,
    val items: List<WidgetItem>,
) {
```

`loadState()` 的构造改为：

```kotlin
            WidgetState(
                date = now,
                week = week,
                resting = RestDay.isResting(app.settings.restDay, now),
                items = today.map { m ->
```

文件顶部 import 追加：

```kotlin
import me.huanlin.gbuca.domain.reminder.RestDay
```

- [ ] **Step 3: `WidgetContent` 渲染该行**

在头部 `Row(...) { ... }` 之后插入：

```kotlin
        if (state.resting) {
            Text(
                text = context.getString(R.string.widget_rest),
                style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                modifier = GlanceModifier.padding(horizontal = 10.dp, vertical = 1.dp),
            )
        }
```

- [ ] **Step 4: 编译 + 单测**

Run: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL，62 个单测通过

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/widget/TodayWidget.kt app/src/main/res/values/strings.xml
git commit -m "feat(rest): 小组件显示「今日休息」"
```

---

### Task 6: 全量验证与文档

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 全量验证**

Run: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
Expected: BUILD SUCCESSFUL，单测 62 个通过（既有 58 + 新增 4），lint 0 errors

- [ ] **Step 2: 真机验证清单**

装到设备（`gradlew.bat :app:installDebug`）后逐项确认：

1. 今日页顶部出现全宽「休息」按钮（在风控卡与日期条之上，翻到其他日期时不显示）。
2. 点「休息」→ 按钮变「休息中 · 点此恢复」+ 下方出现提示条。
3. `adb shell dumpsys alarm | rg gbuca` 中今天的课次闹钟消失；
   `adb shell dumpsys notification | rg gbuca` 中常驻 Live Update 通知消失。
4. 休息中按「同步」→ 提醒不复现（`dumpsys alarm` 仍无今天的课次）。
5. 再点一次 → 按钮恢复「休息」，提示条消失，`dumpsys alarm` 重新出现今天的课次。
6. 小组件出现「今日休息」行；恢复后消失。
7. `adb shell am force-stop me.huanlin.gbuca` 后重开 → 休息状态保持。

- [ ] **Step 3: README 补充功能说明**

在「上课提醒」那一行之后插入：

```markdown
- **今日休息**：今日页顶部一键静音当天全部提醒（精确闹钟 + Live Update 常驻倒计时），再次点击恢复；仅当日生效，跨零点自动失效
```

- [ ] **Step 4: 提交文档**

```bash
git add README.md
git commit -m "docs: README 补充「今日休息」"
```

- [ ] **Step 5: 更新实施计划勾选**

把本文件中所有 `- [ ]` 改为 `- [x]`，提交：

```bash
git add docs/plans/2026-09-08-rest-day-impl.md
git commit -m "docs: 今日休息实施计划勾选完成"
```

## Self-Review

- **Spec 覆盖**：「仅当日」判定（Task 1 `RestDay.isResting`）✓ 持久化（Task 2 `SettingsStore.restDay`）✓
  取消一切提醒（Task 3 `setResting` → `cancelAll()`）✓ 重排不冲掉休息（Task 2 `reschedule` 跳过）✓
  再次点击恢复（Task 3 → `rescheduleAsync()`）✓ 按钮位置与文案（Task 4 `RestBar`）✓
  休息中提示条（Task 4 `today_rest_hint`）✓ 小组件「今日休息」行（Task 5）✓
  跨零点自动失效（Task 1 + Task 4 `ON_RESUME` 重新判定）✓ 文案进 strings.xml（Task 4/5）✓
  README（Task 6）✓ 明确不做项（无对应任务，符合预期）✓
- **类型一致性**：`restDay: Long?` 在 Task 2 定义，Task 2/3/5 读取一致；
  `RestDay.isResting(Long?, LocalDate)` 在 Task 1 定义，Task 2（两处）与 Task 5 调用签名一致；
  `resting: StateFlow<Boolean>` / `setResting(Boolean)` / `refreshResting()` 在 Task 3 定义，
  Task 4 使用一致；`WidgetState.resting` 在 Task 5 定义并同任务内使用。
- **占位符扫描**：无 TBD/TODO；每个代码步骤含完整可编译代码。
