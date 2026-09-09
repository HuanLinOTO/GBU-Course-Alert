# 课表页真实时间绘制 + 详情页深色修复 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 课表页课块按教务显式时间（含 3 节连排压缩时间）插值绘制、左轴时间刻度移到分隔线上；课程详情页与登录/向导页补齐 M3 背景，修复深色模式双色拼接。

**Architecture:** `TimeGrid` 新增纯函数 `locate(time)` 把时刻映射到（节次，节内比例），WeekScreen 用它计算课块顶/底 y 坐标并按时间分道；左轴重绘为「线上时间刻度 + 行内淡显节次号」。详情页包 `Scaffold`，登录/向导页包 `Surface`。数据层（kbjclist/解析器/Room）不动。

**Tech Stack:** Kotlin + Jetpack Compose Material3；JUnit4 JVM 单测；Gradle（AGP + KSP）。

**Spec:** `docs/plans/2026-09-09-week-grid-time-and-detail-dark-design.md`

## Global Constraints

- 包名 `me.huanlin.gbuca`；注释与 UI 文案为中文，代码风格与现有一致（4 空格缩进）。
- JVM 单测不得依赖 Android 框架类；`TimeGrid` 是可变单例，测试必须先 `reset()`。
- 不修改 `ScheduleParser`、`CourseRepository`、`Dtos`、ICS/提醒/Widget 逻辑。
- 作息网格数据（`TimeGrid.DEFAULT`）保持不变；`Meeting.startTime/endTime` 是事实数据，渲染必须忠于它。
- 版本：versionCode 7 / versionName 0.0.7 不动；release 构建走现有签名（release.jks）。

---

### Task 1: `TimeGrid.locate` — 时刻 → (节次, 节内比例)

**Files:**
- Modify: `app/src/main/java/me/huanlin/gbuca/domain/time/TimeGrid.kt`
- Test: `app/src/test/java/me/huanlin/gbuca/TimeGridTest.kt`（新建）

**Interfaces:**
- Consumes: 现有 `TimeGrid.periods: List<Period>`（`index` 升序，含 `start/end: LocalTime`）。
- Produces: `fun locate(time: LocalTime): Pair<Int, Float>?` —— 返回 (节次序号, 节内比例 0f..1f)；`periods` 为空返回 null。Task 2 的 `meetingTopY/meetingBottomY` 依赖此签名。

- [x] **Step 1: 写失败测试**

新建 `app/src/test/java/me/huanlin/gbuca/TimeGridTest.kt`：

```kotlin
package me.huanlin.gbuca

import me.huanlin.gbuca.domain.time.TimeGrid
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalTime

/** TimeGrid.locate：时刻 → (节次, 节内比例)。单例可变，先 reset 保证用默认网格。 */
class TimeGridTest {

    @Before
    fun resetGrid() = TimeGrid.reset()

    @Test
    fun `早于首节归入首节比例0`() {
        assertEquals(1 to 0f, TimeGrid.locate(java.time.LocalTime.of(7, 30)))
    }

    @Test
    fun `节首比例为0`() {
        assertEquals(9 to 0f, TimeGrid.locate(java.time.LocalTime.of(14, 0)))
    }

    @Test
    fun `节内线性插值`() {
        val (idx, frac) = TimeGrid.locate(java.time.LocalTime.of(14, 30)) // 第9节 14:00-14:35
        assertEquals(9, idx)
        assertEquals(30f / 35f, frac, 1e-4f)
    }

    @Test
    fun `课间归入前一节末尾`() {
        // 15:20 落在第10节(15:15结束)与第11节(15:30开始)之间的大课间
        assertEquals(10 to 1f, TimeGrid.locate(java.time.LocalTime.of(15, 20)))
    }

    @Test
    fun `晚于末节归入末节末尾`() {
        assertEquals(18 to 1f, TimeGrid.locate(java.time.LocalTime.of(23, 0)))
    }

    @Test
    fun `末节结束时刻本身也归末节末尾`() {
        assertEquals(18 to 1f, TimeGrid.locate(java.time.LocalTime.of(21, 15)))
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `.\gradlew.bat test --tests "me.huanlin.gbuca.TimeGridTest"`（workdir: `D:\Projects\GBU-Course-Alert`，pwsh timeoutMs ≥ 600000）
Expected: 编译失败，`unresolved reference: locate`

- [x] **Step 3: 最小实现**

在 `TimeGrid.kt` 顶部 import 区加入 `java.time.temporal.ChronoUnit`；在 `fun period(index: Int)` 之后加入：

```kotlin
    /** 时刻 → (节次序号, 节内比例 0f..1f)。早于首节归首节 0f；课间归前一节 1f；晚于末节归末节 1f。 */
    fun locate(time: LocalTime): Pair<Int, Float>? {
        val sorted = periods.sortedBy { it.index }
        val first = sorted.firstOrNull() ?: return null
        if (!time.isAfter(first.start)) return first.index to 0f
        var last = first
        for (p in sorted) {
            if (p.start.isAfter(time)) break
            last = p
        }
        if (time.isAfter(last.end)) return last.index to 1f
        val total = ChronoUnit.SECONDS.between(last.start, last.end).coerceAtLeast(1)
        val frac = ChronoUnit.SECONDS.between(last.start, time).toFloat() / total
        return last.index to frac
    }
```

- [x] **Step 4: 运行确认通过**

Run: `.\gradlew.bat test --tests "me.huanlin.gbuca.TimeGridTest"`
Expected: 全部 PASS

- [x] **Step 5: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/domain/time/TimeGrid.kt app/src/test/java/me/huanlin/gbuca/TimeGridTest.kt
git commit -m "feat(time): TimeGrid.locate 时刻→节次+节内比例（课表按真实时间绘制的基础）"
```

---

### Task 2: WeekScreen 课块按真实时间绘制 + 分道按时间判断

**Files:**
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/WeekScreen.kt`（`assignLanes` 全替换；`DayColumn` 内 chip 位置改用新 helper；文件尾部加两个私有函数）

**Interfaces:**
- Consumes: `TimeGrid.locate(time: LocalTime): Pair<Int, Float>?`（Task 1）。
- Produces: 私有 `meetingTopY(m: Meeting): Dp`、`meetingBottomY(m: Meeting): Dp`（Task 3 无依赖，仅本文件使用）。

- [x] **Step 1: 替换 `assignLanes`（第 175-183 行）**

```kotlin
/** 重叠的课次分到不同竖道，互不重叠的共用同一道。按真实时间判断重叠。 */
private fun assignLanes(list: List<Meeting>): List<List<Meeting>> {
    val lanes = mutableListOf<MutableList<Meeting>>()
    for (m in list.sortedWith(compareBy({ it.startTime }, { it.endTime }))) {
        val lane = lanes.firstOrNull { l -> l.all { it.endTime <= m.startTime } }
        if (lane != null) lane.add(m) else lanes.add(mutableListOf(m))
    }
    return lanes
}
```

- [x] **Step 2: 文件尾部（`MeetingChip` 之后）加入 y 坐标 helper**

```kotlin
/** 课块顶/底 y 坐标：优先按真实时间在节次网格中插值（压缩课块可越过节次线）；网格缺失时回退节次索引。 */
private fun meetingTopY(m: Meeting): Dp {
    val loc = TimeGrid.locate(m.startTime)
    return if (loc != null) periodRowH * ((loc.first - 1) + loc.second)
    else periodRowH * (m.startPeriod - 1)
}

private fun meetingBottomY(m: Meeting): Dp {
    val loc = TimeGrid.locate(m.endTime)
    return if (loc != null) periodRowH * ((loc.first - 1) + loc.second)
    else periodRowH * m.endPeriod
}
```

- [x] **Step 3: `DayColumn` 内课块定位改用 helper（原第 209-212 行）**

原：

```kotlin
                    modifier = Modifier
                        .offset(x = laneW * li, y = periodRowH * (m.startPeriod - 1) + 1.dp)
                        .width(laneW - 2.dp)
                        .height(periodRowH * (m.endPeriod - m.startPeriod + 1) - 2.dp),
```

改为：

```kotlin
                    modifier = Modifier
                        .offset(x = laneW * li, y = meetingTopY(m) + 1.dp)
                        .width(laneW - 2.dp)
                        .height(meetingBottomY(m) - meetingTopY(m) - 2.dp),
```

- [x] **Step 4: 编译 + 全量测试**

Run: `.\gradlew.bat test`
Expected: BUILD SUCCESSFUL（全部既有测试 + Task 1 新测试通过）

- [x] **Step 5: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/ui/WeekScreen.kt
git commit -m "fix(ui): 课表课块按教务显式时间插值绘制，重叠分道改按时间判断"
```

---

### Task 3: WeekScreen 左轴重绘（时间刻度上移到分隔线）

**Files:**
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/WeekScreen.kt`（`WeekGrid` 内 `// 网格主体（纵向滚动）` 起的整个 `Column` 块，原第 128-172 行）

**Interfaces:**
- Consumes: Task 2 无新增接口；仅改 UI 结构。`timeColWidth/periodRowH/timeFmt` 沿用。

- [x] **Step 1: 整体替换网格主体块**

将 `// 网格主体（纵向滚动）` 注释开始的整个 `Column(Modifier.weight(1f)...) { ... }` 替换为：

```kotlin
        // 网格主体（纵向滚动；顶部内边距给首条时间刻度留位）
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(periodRowH * n)) {
                // 小节分隔线
                periods.forEach { p ->
                    Box(
                        Modifier
                            .offset(y = periodRowH * (p.index - 1))
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    )
                }
                Row(Modifier.fillMaxWidth().height(periodRowH * n)) {
                    // 左轴：节次号淡显在行内（时间改放到分隔线上，见下方刻度层）
                    Column(Modifier.width(timeColWidth).fillMaxHeight()) {
                        periods.forEach { p ->
                            Box(
                                Modifier.fillMaxWidth().height(periodRowH),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "${p.index}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                )
                            }
                        }
                    }
                    // 日列，平分剩余宽度（周末无课时隐藏）
                    visibleDays.forEach { wd ->
                        DayColumn(
                            meetings = dayMeetings[wd].orEmpty(),
                            isToday = isCurrentWeek && wd == today.dayOfWeek.value,
                            courseName = courseName,
                            onOpenCourse = onOpenCourse,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
                // 时间刻度层：每条分隔线上居中放该节开始时间（surface 底遮住线段，形似刻度）
                val tickH = 16.dp
                periods.forEach { p ->
                    Box(
                        Modifier
                            .offset(y = periodRowH * (p.index - 1) - tickH / 2)
                            .width(timeColWidth)
                            .height(tickH)
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            p.start.format(timeFmt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // 底部：结束线 + 末节结束时间刻度
            Box(Modifier.fillMaxWidth().height(20.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                )
                Box(
                    Modifier
                        .offset(y = -7.dp)
                        .width(timeColWidth)
                        .height(14.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        periods.last().end.format(timeFmt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
```

说明：刻度盒不透明 surface 底绘制在分隔线之上（Box 内声明顺序即绘制顺序），形成"线被打断 + 刻度文字"效果；首行刻度中心恰在 y=0，靠 8dp 顶部内边距避免被滚动视口裁剪；末节结束刻度放在网格下方的 20dp 条内、中心对齐结束线。

- [x] **Step 2: 编译 + 测试**

Run: `.\gradlew.bat test`
Expected: BUILD SUCCESSFUL

- [x] **Step 3: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/ui/WeekScreen.kt
git commit -m "feat(ui): 课表左轴时间刻度移至分隔线，节次号淡显行内"
```

---

### Task 4: CourseDetailScreen 包 Scaffold（深色双色拼接修复）

**Files:**
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/CourseDetailScreen.kt`

**Interfaces:**
- Consumes: 无。Produces: 无（纯 UI 包装）。

- [x] **Step 1: import 区加入 `androidx.compose.material3.Scaffold`（保持其余不动；`PaddingValues` 维持现有的全限定写法亦可）**

- [x] **Step 2: 函数体替换（原第 45-127 行的 `Column(Modifier.fillMaxSize()) { ... }` 整体改写）**

将：

```kotlin
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(course?.name ?: titleFallback) })
        if (course == null) {
            Text(stringResource(R.string.detail_not_found), Modifier.padding(16.dp))
            return
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ... （items 原样保留）
        }
    }
```

改为（注意 lambda 内不能用非局部 `return`，改为 if/else）：

```kotlin
    Scaffold(
        topBar = { TopAppBar(title = { Text(course?.name ?: titleFallback) }) },
    ) { padding ->
        if (course == null) {
            Text(stringResource(R.string.detail_not_found), Modifier.padding(16.dp))
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ... （原 items 逐字保留，不改动）
            }
        }
    }
```

- [x] **Step 3: 编译 + 测试**

Run: `.\gradlew.bat test`
Expected: BUILD SUCCESSFUL

- [x] **Step 4: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/ui/CourseDetailScreen.kt
git commit -m "fix(ui): 课程详情页包 Scaffold，统一 M3 surface 背景修复深色模式"
```

---

### Task 5: LoginScreen / SetupFlowScreen 根层包 Surface

**Files:**
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/LoginScreen.kt`（约第 60 行起根 `Column`）
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/SetupFlowScreen.kt`（约第 96 行起根 `Column`）

**Interfaces:**
- Consumes: 无。Produces: 无。

- [x] **Step 1: 两文件 import 区各加入 `androidx.compose.material3.Surface`**

- [x] **Step 2: LoginScreen — 在 `val focus = LocalFocusManager.current` 与根 `Column(` 之间插入 `Surface(Modifier.fillMaxSize()) {`，并在函数末尾（根 `Column` 闭合 `}` 之后、函数 `}` 之前）补一个闭合 `}`。**

改后骨架：

```kotlin
    val focus = LocalFocusManager.current

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ... 原内容不动 ...
        }
    }
}
```

- [x] **Step 3: SetupFlowScreen — 同样处理：在 `fun go(...)` 定义之后、根 `Column(` 之前插入 `Surface(Modifier.fillMaxSize()) {`，函数末尾补闭合 `}`。**

- [x] **Step 4: 编译 + 测试**

Run: `.\gradlew.bat test`
Expected: BUILD SUCCESSFUL

- [x] **Step 5: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/ui/LoginScreen.kt app/src/main/java/me/huanlin/gbuca/ui/SetupFlowScreen.kt
git commit -m "fix(ui): 登录页与 OOBE 向导根层包 Surface，修复深色模式背景"
```

---

### Task 6: 全量验证（构建 → 安装 → 真机截图核对）

**Files:**
- 无代码改动；产出验证结论与必要的视觉微调（若左轴刻度被裁剪/重叠，回到 Task 3 调整内边距）。

- [x] **Step 1: 全量测试**

Run: `.\gradlew.bat test`
Expected: BUILD SUCCESSFUL

- [x] **Step 2: 构建 release APK**

Run: `.\gradlew.bat assembleRelease`（pwsh timeoutMs ≥ 900000）
Expected: `app/build/outputs/apk/release/app-release.apk` 生成

- [x] **Step 3: 安装到设备（保留数据；versionCode 7 > 已装 6）**

Run: `adb install -r app\build\outputs\apk\release\app-release.apk`
Expected: `Success`

- [x] **Step 4: 深色模式截图核对课表页**

设备当前即为深色模式。启动应用 → 课表 tab：
- 核对：左轴时间刻度位于分隔线上、节次号淡显；
- 核对：周四「线性代数」课块顶端越过 14:40 刻度线约 1/3 行高（真实 14:30 开始）、底端止于 16:25（不到 16:45 线）；
- 核对：周四「思想道德与法治」（08:00-09:55）底端止于 09:55 与 10:05 线之间；「物理原理1」（10:10-12:05）底端止于 12:05。

- [x] **Step 5: 深色模式截图核对课程详情页**

点开任一课程：顶栏与正文背景同色（无深藏青/浅灰拼接）。

- [x] **Step 6: 浅色模式抽查**

`adb shell cmd uimode night no` → 重复 Step 4/5 目视核对；完成后 `adb shell cmd uimode night yes` 恢复深色（设备原本处于深色）。

- [x] **Step 7: 提交收尾（若有微调）**

```bash
git add -A app/src
git commit -m "polish(ui): 真机核对后的课表刻度内边距微调"
```

---

## Self-Review 记录

- 规格覆盖：设计 §1→Task 1；§2→Task 2；§3→Task 3；§4→Task 2（assignLanes）；§5→Task 4/5；测试→Task 1/6；验证→Task 6。无缺口。
- 占位符扫描：无 TBD/TODO；所有代码步骤含完整代码或逐字保留指令（「原 items 逐字保留」指明不改动的边界）。
- 类型一致性：`locate` 返回 `Pair<Int, Float>`；`meetingTopY/meetingBottomY` 返回 `androidx.compose.ui.unit.Dp`（`Dp * Float`、`Dp - Dp` 均为既有运算）；Task 2/3 均引用同一组 `periodRowH/timeColWidth/timeFmt` 既有常量。
