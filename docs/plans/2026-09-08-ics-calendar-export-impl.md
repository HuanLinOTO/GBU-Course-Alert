# 导出到日历（.ics）实施计划

> **For agentic workers:** 本计划按任务逐条执行，每个任务以「测试先行 → 实现 → 验证 → 提交」收尾。
> 步骤使用 `- [ ]` 复选框跟踪进度。

**Goal:** 把当前学期的课表导出为 iCalendar（`.ics`）文件，支持保存到文件与分享到日历 App。

**Architecture:** 纯 Kotlin 的两层（`IcsCalendar` 负责 RFC 5545 序列化，`CourseIcsExporter` 负责
课次→事件的业务映射）放在 `domain/export`，可直接 JVM 单测；Android 适配只有
`data/export/IcsExportManager`（读库、写 SAF Uri、构造 FileProvider 分享 Intent）。
UI 在设置页新增分组，`AppViewModel` 提供两个入口方法。

**Tech Stack:** Kotlin 2.4 · AndroidX Activity Result（`CreateDocument`）· FileProvider ·
java.time · JUnit 4。**不新增任何第三方依赖。**

**Spec:** `docs/plans/2026-09-08-ics-calendar-export-design.md`

## Global Constraints

- 不新增第三方依赖；不新增 Android 权限（SAF 与 FileProvider 均免权限）。
- 时区固定 `Asia/Shanghai`，附 `VTIMEZONE`（无 DST，恒定 `+0800`）。
- ICS 行尾 `CRLF`；单行 ≤ 75 octet（UTF-8 字节数），折行按码点推进，不切断多字节中文。
- TEXT 值转义：`\` → `\\`，`;` → `\;`，`,` → `\,`，换行 → `\n`。
- UID 必须对同一课次稳定（重复导入为更新而非新建）。
- 面向用户的文案一律走 `strings.xml`，不硬编码中文。
- 用户可见的失败提示永不出现 `?`（沿用 `error_sync_failed` 的兜底写法）。

**文件结构**

| 文件 | 责任 |
| --- | --- |
| `app/src/main/java/me/huanlin/gbuca/domain/export/IcsCalendar.kt`（新建） | RFC 5545 序列化：折行、转义、VTIMEZONE、VEVENT/VALARM |
| `app/src/main/java/me/huanlin/gbuca/domain/export/CourseIcsExporter.kt`（新建） | 课次 → 事件：日期换算、RRULE/RDATE、UID、SUMMARY/DESCRIPTION |
| `app/src/main/java/me/huanlin/gbuca/data/export/IcsExportManager.kt`（新建） | 读库组装、写 SAF Uri、FileProvider 分享 Intent |
| `app/src/main/res/xml/file_paths.xml`（新建） | FileProvider 路径（`cache/ics/`） |
| `app/src/main/AndroidManifest.xml`（修改） | 注册 FileProvider |
| `app/src/main/java/me/huanlin/gbuca/GbuCaApp.kt`（修改） | 构造 `icsExport` |
| `app/src/main/java/me/huanlin/gbuca/ui/AppViewModel.kt`（修改） | `exportIcs` / `shareIcs` / `showMessage` |
| `app/src/main/java/me/huanlin/gbuca/ui/SettingsScreen.kt`（修改） | 「导出到日历」分组 |
| `app/src/main/res/values/strings.xml`（修改） | 导出相关文案 |
| `app/src/test/java/me/huanlin/gbuca/IcsCalendarTest.kt`（新建） | 序列化单测 |
| `app/src/test/java/me/huanlin/gbuca/CourseIcsExporterTest.kt`（新建） | 映射单测 |

---

### Task 1: IcsCalendar（RFC 5545 序列化）

**Files:**
- Create: `app/src/main/java/me/huanlin/gbuca/domain/export/IcsCalendar.kt`
- Test: `app/src/test/java/me/huanlin/gbuca/IcsCalendarTest.kt`

**Interfaces:**
- Consumes: 无（纯 Kotlin，仅 `java.time`）。
- Produces:
  - `IcsCalendar.Event(uid, start: LocalDateTime, end: LocalDateTime, summary, location: String? = null, description: String? = null, rrule: String? = null, rdates: List<LocalDateTime> = emptyList(), alarmMinutes: Int? = null)`
  - `IcsCalendar.build(events: List<Event>, calendarName: String, stamp: Instant = Instant.now()): String`
  - `IcsCalendar.TIMEZONE = "Asia/Shanghai"`

- [x] **Step 1: 写失败测试** `app/src/test/java/me/huanlin/gbuca/IcsCalendarTest.kt`

```kotlin
package me.huanlin.gbuca

import me.huanlin.gbuca.domain.export.IcsCalendar
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class IcsCalendarTest {

    private val stamp: Instant = Instant.parse("2026-09-08T00:00:00Z")

    private fun event(
        summary: String = "高等数学1",
        location: String? = "B304",
        rrule: String? = null,
        rdates: List<LocalDateTime> = emptyList(),
        alarmMinutes: Int? = null,
    ) = IcsCalendar.Event(
        uid = "abc@gbuca",
        start = LocalDateTime.of(2026, 9, 2, 8, 0),
        end = LocalDateTime.of(2026, 9, 2, 9, 15),
        summary = summary,
        location = location,
        description = "第 1-2 节 08:00-09:15",
        rrule = rrule,
        rdates = rdates,
        alarmMinutes = alarmMinutes,
    )

    private fun build(vararg events: IcsCalendar.Event, name: String = "GBU课表 2026-20271"): String =
        IcsCalendar.build(events.toList(), name, stamp)

    @Test
    fun `every line ends with crlf`() {
        val text = build(event())
        assertTrue(text.endsWith("END:VCALENDAR\r\n"))
        assertFalse(text.contains(Regex("(?<!\r)\n")))
    }

    @Test
    fun `folds long chinese lines at 75 octets without splitting characters`() {
        val summary = "高等数学1（双语）· 微积分与线性代数基础强化训练课程 · 第 1-16 周 · 主任务 · 段金桥"
        val text = build(event(summary = summary))
        assertTrue(text.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertTrue(text.replace("\r\n ", "").contains(summary))
    }

    @Test
    fun `escapes special characters in text values`() {
        assertTrue(build(event(summary = "A,B;C\\D\nE")).contains("SUMMARY:A\\,B\\;C\\\\D\\nE\r\n"))
    }

    @Test
    fun `includes timezone calendar name and utc stamp`() {
        val text = build(event(), name = "GBU课表 2026-20271")
        assertTrue(text.contains("BEGIN:VTIMEZONE\r\n"))
        assertTrue(text.contains("TZID:Asia/Shanghai\r\n"))
        assertTrue(text.contains("TZOFFSETTO:+0800\r\n"))
        assertTrue(text.contains("X-WR-CALNAME:GBU课表 2026-20271\r\n"))
        assertTrue(text.contains("DTSTAMP:20260908T000000Z\r\n"))
        assertTrue(text.contains("DTSTART;TZID=Asia/Shanghai:20260902T080000\r\n"))
    }

    @Test
    fun `emits rrule and rdate only when present`() {
        val a = build(event(rrule = "FREQ=WEEKLY;INTERVAL=2;COUNT=8"))
        assertTrue(a.contains("RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=8\r\n"))
        assertFalse(a.contains("RDATE"))

        val b = build(event(rdates = listOf(LocalDateTime.of(2026, 9, 16, 8, 0))))
        assertTrue(b.contains("RDATE;TZID=Asia/Shanghai:20260916T080000\r\n"))
        assertFalse(b.contains("RRULE:"))
    }

    @Test
    fun `valarm appears only when alarm minutes positive`() {
        assertFalse(build(event()).contains("BEGIN:VALARM"))
        assertFalse(build(event(alarmMinutes = 0)).contains("BEGIN:VALARM"))
        val text = build(event(alarmMinutes = 15))
        assertTrue(text.contains("BEGIN:VALARM\r\n"))
        assertTrue(text.contains("TRIGGER:-PT15M\r\n"))
        assertTrue(text.contains("ACTION:DISPLAY\r\n"))
    }

    @Test
    fun `omits location when blank`() {
        assertFalse(build(event(location = null)).contains("LOCATION:"))
        assertFalse(build(event(location = "  ")).contains("LOCATION:"))
    }
}
```

- [x] **Step 2: 运行测试确认失败**

Run: `gradlew.bat :app:testDebugUnitTest --tests "me.huanlin.gbuca.IcsCalendarTest"`
Expected: 编译失败 —— `Unresolved reference: IcsCalendar`

- [x] **Step 3: 实现**

`app/src/main/java/me/huanlin/gbuca/domain/export/IcsCalendar.kt`：

```kotlin
package me.huanlin.gbuca.domain.export

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * RFC 5545 iCalendar 序列化器（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 只实现导出所需子集：VCALENDAR / VTIMEZONE / VEVENT / VALARM。
 * 行尾 CRLF，单行 ≤ 75 octet（按 UTF-8 码点折行，不切断中文），TEXT 值按 RFC 5545 §3.3.11 转义。
 */
object IcsCalendar {

    /** 固定时区：课次时间均以中国标准时间表达（无 DST）。 */
    const val TIMEZONE = "Asia/Shanghai"

    private const val MAX_OCTETS = 75

    /** 一条日历事件。`rrule` 与 `rdates` 都为空 = 单次事件。 */
    data class Event(
        val uid: String,
        val start: LocalDateTime,
        val end: LocalDateTime,
        val summary: String,
        val location: String? = null,
        val description: String? = null,
        /** 不含 `RRULE:` 前缀，如 `FREQ=WEEKLY;INTERVAL=1;COUNT=16`。 */
        val rrule: String? = null,
        /** 除首场外的额外发生时间（RDATE）；为空则不输出 RDATE。 */
        val rdates: List<LocalDateTime> = emptyList(),
        /** 提前提醒分钟数；null 或 ≤ 0 时不输出 VALARM。 */
        val alarmMinutes: Int? = null,
    )

    private val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    private val UTC: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun build(
        events: List<Event>,
        calendarName: String,
        stamp: Instant = Instant.now(),
    ): String {
        val sb = StringBuilder()
        line(sb, "BEGIN:VCALENDAR")
        line(sb, "VERSION:2.0")
        line(sb, "PRODID:-//GBU Course Alert//ICS Export//CN")
        line(sb, "CALSCALE:GREGORIAN")
        line(sb, "METHOD:PUBLISH")
        line(sb, "X-WR-CALNAME:${escape(calendarName)}")
        line(sb, "X-WR-TIMEZONE:$TIMEZONE")
        timezone(sb)
        val dtstamp = UTC.format(stamp)
        events.forEach { event(sb, it, dtstamp) }
        line(sb, "END:VCALENDAR")
        return sb.toString()
    }

    private fun timezone(sb: StringBuilder) {
        line(sb, "BEGIN:VTIMEZONE")
        line(sb, "TZID:$TIMEZONE")
        line(sb, "BEGIN:STANDARD")
        line(sb, "DTSTART:19700101T000000")
        line(sb, "TZOFFSETFROM:+0800")
        line(sb, "TZOFFSETTO:+0800")
        line(sb, "TZNAME:CST")
        line(sb, "END:STANDARD")
        line(sb, "END:VTIMEZONE")
    }

    private fun event(sb: StringBuilder, e: Event, dtstamp: String) {
        line(sb, "BEGIN:VEVENT")
        line(sb, "UID:${escape(e.uid)}")
        line(sb, "DTSTAMP:$dtstamp")
        line(sb, "DTSTART;TZID=$TIMEZONE:${LOCAL.format(e.start)}")
        line(sb, "DTEND;TZID=$TIMEZONE:${LOCAL.format(e.end)}")
        line(sb, "SUMMARY:${escape(e.summary)}")
        e.location?.takeIf { it.isNotBlank() }?.let { line(sb, "LOCATION:${escape(it)}") }
        e.description?.takeIf { it.isNotBlank() }?.let { line(sb, "DESCRIPTION:${escape(it)}") }
        e.rrule?.takeIf { it.isNotBlank() }?.let { line(sb, "RRULE:$it") }
        if (e.rdates.isNotEmpty()) {
            line(sb, "RDATE;TZID=$TIMEZONE:${e.rdates.joinToString(",") { LOCAL.format(it) }}")
        }
        val alarm = e.alarmMinutes
        if (alarm != null && alarm > 0) {
            line(sb, "BEGIN:VALARM")
            line(sb, "ACTION:DISPLAY")
            line(sb, "DESCRIPTION:${escape(e.summary)}")
            line(sb, "TRIGGER:-PT${alarm}M")
            line(sb, "END:VALARM")
        }
        line(sb, "END:VEVENT")
    }

    /** RFC 5545 §3.3.11 TEXT 转义。 */
    private fun escape(value: String): String = buildString {
        for (ch in value) when (ch) {
            '\\' -> append("\\\\")
            ';' -> append("\\;")
            ',' -> append("\\,")
            '\n' -> append("\\n")
            '\r' -> Unit
            else -> append(ch)
        }
    }

    /** 写一行并折行：首行 ≤75 octet，续行以单个空格开头且同样 ≤75 octet。 */
    private fun line(sb: StringBuilder, raw: String) {
        if (raw.toByteArray(Charsets.UTF_8).size <= MAX_OCTETS) {
            sb.append(raw).append("\r\n")
            return
        }
        val buf = StringBuilder()
        var octets = 0
        var continuation = false
        var i = 0
        while (i < raw.length) {
            val next = raw.offsetByCodePoints(i, 1)
            val chunk = raw.substring(i, next)
            val len = chunk.toByteArray(Charsets.UTF_8).size
            if (octets + len > if (continuation) MAX_OCTETS - 1 else MAX_OCTETS) {
                sb.append(buf).append("\r\n ")
                buf.setLength(0)
                octets = 0
                continuation = true
            }
            buf.append(chunk)
            octets += len
            i = next
        }
        sb.append(buf).append("\r\n")
    }
}
```

- [x] **Step 4: 运行测试确认通过**

Run: `gradlew.bat :app:testDebugUnitTest --tests "me.huanlin.gbuca.IcsCalendarTest"`
Expected: PASS（7 个用例）

- [x] **Step 5: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/domain/export/IcsCalendar.kt app/src/test/java/me/huanlin/gbuca/IcsCalendarTest.kt
git commit -m "feat(export): IcsCalendar — RFC 5545 序列化（折行/转义/VTIMEZONE）"
```

---

### Task 2: CourseIcsExporter（课次 → 事件）

**Files:**
- Create: `app/src/main/java/me/huanlin/gbuca/domain/export/CourseIcsExporter.kt`
- Test: `app/src/test/java/me/huanlin/gbuca/CourseIcsExporterTest.kt`

**Interfaces:**
- Consumes: `IcsCalendar.Event`（Task 1）、`TermData`/`Course`/`Meeting`、`ScheduleLogic.formatWeeks`、`ScheduleParser.ROLE_MAIN`/`ROLE_LAB`。
- Produces:
  - `CourseIcsExporter.export(xnxq: String, data: TermData, semesterStartMonday: LocalDate, alarmMinutes: Int? = null): List<IcsCalendar.Event>`
  - `CourseIcsExporter.fileName(xnxq: String): String` → `GBU课表-{xnxq}.ics`
  - `CourseIcsExporter.calendarName(xnxq: String): String` → `GBU课表 {xnxq}`
  - `CourseIcsExporter.recurrence(start0: LocalDateTime, dates: List<LocalDate>): Pair<String?, List<LocalDateTime>>`（public，供测试）

- [x] **Step 1: 写失败测试** `app/src/test/java/me/huanlin/gbuca/CourseIcsExporterTest.kt`

```kotlin
package me.huanlin.gbuca

import me.huanlin.gbuca.domain.export.CourseIcsExporter
import me.huanlin.gbuca.domain.model.Course
import me.huanlin.gbuca.domain.model.Meeting
import me.huanlin.gbuca.domain.model.TermData
import me.huanlin.gbuca.domain.parser.ScheduleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class CourseIcsExporterTest {

    private val startMonday = LocalDate.of(2026, 8, 31)

    private fun course(rwh: String = "R1", name: String = "高等数学1") = Course(
        rwh = rwh, xnxq = "2026-20271", name = name, nameEn = null, code = "MATH101",
        seq = "001C", className = "高等数学1-01班", credits = 5.0, hours = 64.0,
        nature = "必修", category = "专业必修课", college = "理学院", enrollTime = null,
        capacity = 35, enrolled = 35, rawKcxx = "", unparsed = emptyList(),
    )

    private fun meeting(
        weeks: Set<Int> = (1..16).toSet(),
        weekday: Int = 3,
        role: String = ScheduleParser.ROLE_MAIN,
        room: String? = "B304",
    ) = Meeting(
        rwh = "R1", role = role, teachers = listOf("段金桥"), weeks = weeks,
        weekday = weekday, startPeriod = 1, endPeriod = 2,
        startTime = LocalTime.of(8, 0), endTime = LocalTime.of(9, 15),
        room = room, rawText = "1-16周,星期三第1-2节 8:00-9:15 B304",
    )

    private fun export(vararg meetings: Meeting, alarmMinutes: Int? = 15) = CourseIcsExporter.export(
        xnxq = "2026-20271",
        data = TermData(listOf(course()), meetings.toList()),
        semesterStartMonday = startMonday,
        alarmMinutes = alarmMinutes,
    )

    @Test
    fun `first week wednesday maps to the correct date`() {
        val e = export(meeting()).single()
        assertEquals(LocalDate.of(2026, 9, 2), e.start.toLocalDate())
        assertEquals(LocalTime.of(8, 0), e.start.toLocalTime())
        assertEquals(LocalTime.of(9, 15), e.end.toLocalTime())
    }

    @Test
    fun `uniform weekly weeks become rrule`() {
        val e = export(meeting(weeks = (1..16).toSet())).single()
        assertEquals("FREQ=WEEKLY;INTERVAL=1;COUNT=16", e.rrule)
        assertTrue(e.rdates.isEmpty())
    }

    @Test
    fun `biweekly weeks become interval two rrule`() {
        val e = export(meeting(weeks = (2..16 step 2).toSet())).single()
        assertEquals("FREQ=WEEKLY;INTERVAL=2;COUNT=8", e.rrule)
    }

    @Test
    fun `irregular weeks become rdates`() {
        val e = export(meeting(weeks = setOf(1, 2, 4, 9))).single()
        assertNull(e.rrule)
        assertEquals(
            listOf(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 23), LocalDate.of(2026, 10, 21)),
            e.rdates.map { it.toLocalDate() },
        )
    }

    @Test
    fun `single occurrence has neither rrule nor rdates`() {
        val e = export(meeting(weeks = setOf(5))).single()
        assertNull(e.rrule)
        assertTrue(e.rdates.isEmpty())
    }

    @Test
    fun `uid is stable across exports and distinct across week sets`() {
        val a1 = export(meeting(weeks = (1..4).toSet())).single().uid
        val a2 = export(meeting(weeks = (1..4).toSet())).single().uid
        val b = export(meeting(weeks = (5..16).toSet())).single().uid
        assertEquals(a1, a2)
        assertNotEquals(a1, b)
        assertTrue(a1.endsWith("@gbuca"))
    }

    @Test
    fun `lab role and missing room are handled`() {
        val e = export(meeting(role = ScheduleParser.ROLE_LAB, room = null)).single()
        assertEquals("高等数学1 · 实验", e.summary)
        assertNull(e.location)
        assertTrue(e.description!!.contains("类型：课内实验"))
        assertTrue(e.description!!.contains("教师：段金桥"))
    }

    @Test
    fun `alarm follows reminder minutes`() {
        assertEquals(15, export(meeting()).single().alarmMinutes)
        assertNull(export(meeting(), alarmMinutes = null).single().alarmMinutes)
        assertNull(export(meeting(), alarmMinutes = 0).single().alarmMinutes)
    }

    @Test
    fun `empty term exports nothing`() {
        assertTrue(export().isEmpty())
    }

    @Test
    fun `file name and calendar name carry the term`() {
        assertEquals("GBU课表-2026-20271.ics", CourseIcsExporter.fileName("2026-20271"))
        assertEquals("GBU课表 2026-20271", CourseIcsExporter.calendarName("2026-20271"))
    }
}
```

- [x] **Step 2: 运行测试确认失败**

Run: `gradlew.bat :app:testDebugUnitTest --tests "me.huanlin.gbuca.CourseIcsExporterTest"`
Expected: 编译失败 —— `Unresolved reference: CourseIcsExporter`

- [x] **Step 3: 实现**

`app/src/main/java/me/huanlin/gbuca/domain/export/CourseIcsExporter.kt`：

```kotlin
package me.huanlin.gbuca.domain.export

import me.huanlin.gbuca.domain.logic.ScheduleLogic
import me.huanlin.gbuca.domain.model.Course
import me.huanlin.gbuca.domain.model.Meeting
import me.huanlin.gbuca.domain.model.TermData
import me.huanlin.gbuca.domain.parser.ScheduleParser
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 课表 → iCalendar 事件映射（纯 Kotlin）。
 *
 * 每个 [Meeting] 生成一条 VEVENT：周次集合映射到真实日期，恒定周间隔用 RRULE，不规则周次用 RDATE。
 * 真实日期 = 学期第 1 周周一 + (week - 1) * 7 + (weekday - 1) 天。
 */
object CourseIcsExporter {

    /** 导出整个学期。无课次时返回空列表。 */
    fun export(
        xnxq: String,
        data: TermData,
        semesterStartMonday: LocalDate,
        alarmMinutes: Int? = null,
    ): List<IcsCalendar.Event> {
        val out = mutableListOf<IcsCalendar.Event>()
        for (m in data.meetings) {
            if (m.weeks.isEmpty()) continue
            val dates = m.weeks.sorted().map { week ->
                semesterStartMonday.plusWeeks((week - 1).toLong()).plusDays((m.weekday - 1).toLong())
            }
            val start0 = LocalDateTime.of(dates.first(), m.startTime)
            val end0 = LocalDateTime.of(dates.first(), m.endTime)
            val course = data.courseByRwh[m.rwh]
            val (rrule, rdates) = recurrence(start0, dates)
            out += IcsCalendar.Event(
                uid = uidOf(xnxq, m),
                start = start0,
                end = end0,
                summary = summaryOf(course, m),
                location = m.room?.takeIf { it.isNotBlank() },
                description = descriptionOf(course, m),
                rrule = rrule,
                rdates = rdates,
                alarmMinutes = alarmMinutes?.takeIf { it > 0 },
            )
        }
        return out.sortedWith(compareBy({ it.start }, { it.summary }))
    }

    fun fileName(xnxq: String): String = "GBU课表-$xnxq.ics"

    fun calendarName(xnxq: String): String = "GBU课表 $xnxq"

    /** 恒定周间隔 → RRULE；不规则 → RDATE（除首场）；单次 → 两者都不写。 */
    fun recurrence(
        start0: LocalDateTime,
        dates: List<LocalDate>,
    ): Pair<String?, List<LocalDateTime>> {
        if (dates.size <= 1) return null to emptyList()
        val steps = dates.zipWithNext { a, b -> ChronoUnit.WEEKS.between(a, b) }
        return if (steps.distinct().size == 1 && steps.first() >= 1) {
            "FREQ=WEEKLY;INTERVAL=${steps.first()};COUNT=${dates.size}" to emptyList()
        } else {
            null to dates.drop(1).map { LocalDateTime.of(it, start0.toLocalTime()) }
        }
    }

    private fun summaryOf(course: Course?, m: Meeting): String {
        val name = course?.name?.takeIf { it.isNotBlank() } ?: m.rwh
        return if (m.role == ScheduleParser.ROLE_LAB) "$name · 实验" else name
    }

    private fun descriptionOf(course: Course?, m: Meeting): String = buildString {
        append("第 ").append(m.startPeriod).append('-').append(m.endPeriod).append(" 节 ")
        append(m.startTime).append('-').append(m.endTime)
        ScheduleLogic.formatWeeks(m.weeks).takeIf { it.isNotEmpty() }
            ?.let { append('\n').append(it) }
        if (m.teachers.isNotEmpty()) append("\n教师：").append(m.teachers.joinToString("、"))
        course?.code?.takeIf { it.isNotBlank() }?.let { append("\n课程代码：").append(it) }
        course?.seq?.takeIf { it.isNotBlank() }?.let { append("\n课序号：").append(it) }
        course?.let { append("\n学分：").append(it.credits) }
        course?.className?.takeIf { it.isNotBlank() }?.let { append("\n班级：").append(it) }
        if (m.role != ScheduleParser.ROLE_MAIN) append("\n类型：").append(m.role)
    }

    /** 稳定 UID：同一课次重复导入为更新而非新建。 */
    private fun uidOf(xnxq: String, m: Meeting): String {
        val key = buildString {
            append(xnxq).append('|').append(m.rwh).append('|').append(m.role).append('|')
            append(m.weekday).append('|').append(m.startPeriod).append('-').append(m.endPeriod).append('|')
            append(m.startTime).append('-').append(m.endTime).append('|')
            append(m.weeks.sorted().joinToString(","))
        }
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32) + "@gbuca"
    }
}
```

- [x] **Step 4: 运行测试确认通过**

Run: `gradlew.bat :app:testDebugUnitTest --tests "me.huanlin.gbuca.CourseIcsExporterTest"`
Expected: PASS（10 个用例）

- [x] **Step 5: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/domain/export/CourseIcsExporter.kt app/src/test/java/me/huanlin/gbuca/CourseIcsExporterTest.kt
git commit -m "feat(export): CourseIcsExporter — 周次→RRULE/RDATE、稳定 UID、VALARM 分钟数"
```

---

### Task 3: IcsExportManager + FileProvider

**Files:**
- Create: `app/src/main/java/me/huanlin/gbuca/data/export/IcsExportManager.kt`
- Create: `app/src/main/res/xml/file_paths.xml`
- Modify: `app/src/main/AndroidManifest.xml`（`</application>` 前插入 `<provider>`）
- Modify: `app/src/main/java/me/huanlin/gbuca/GbuCaApp.kt`

**Interfaces:**
- Consumes: `CourseIcsExporter`、`IcsCalendar`、`CourseRepository`、`SettingsStore`。
- Produces:
  - `IcsExportManager(context, repo, settings)`
  - `suspend fun build(xnxq: String): String?`（无课 → null）
  - `fun suggestedFileName(xnxq: String): String`
  - `fun writeToUri(uri: Uri, content: String)`
  - `fun shareIntent(content: String, fileName: String): Intent`
  - `GbuCaApp.icsExport: IcsExportManager`

- [x] **Step 1: 实现 IcsExportManager**

```kotlin
package me.huanlin.gbuca.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import me.huanlin.gbuca.data.local.SettingsStore
import me.huanlin.gbuca.data.repo.CourseRepository
import me.huanlin.gbuca.domain.export.CourseIcsExporter
import me.huanlin.gbuca.domain.export.IcsCalendar
import me.huanlin.gbuca.domain.model.TermData
import java.io.File
import java.io.IOException

/**
 * ICS 导出的 Android 适配层：读库组装内容、写 SAF Uri、构造 FileProvider 分享 Intent。
 */
class IcsExportManager(
    private val context: Context,
    private val repo: CourseRepository,
    private val settings: SettingsStore,
) {

    /** 当前学期课表 → ICS 文本；无课返回 null。 */
    suspend fun build(xnxq: String): String? {
        val meetings = repo.meetingsByXnxq(xnxq)
        if (meetings.isEmpty()) return null
        val courses = meetings.mapNotNull { repo.courseByRwh(it.rwh) }.distinctBy { it.rwh }
        val events = CourseIcsExporter.export(
            xnxq = xnxq,
            data = TermData(courses = courses, meetings = meetings),
            semesterStartMonday = settings.semesterStartMonday(xnxq),
            alarmMinutes = if (settings.remindersEnabled) settings.reminderMinutes else null,
        )
        if (events.isEmpty()) return null
        return IcsCalendar.build(events, CourseIcsExporter.calendarName(xnxq))
    }

    fun suggestedFileName(xnxq: String): String = CourseIcsExporter.fileName(xnxq)

    /** 写入 SAF 返回的 Uri；失败抛 IOException / SecurityException，由调用方提示。 */
    fun writeToUri(uri: Uri, content: String) {
        val stream = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("openOutputStream returned null")
        stream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }

    /** 写 cacheDir 并经 FileProvider 生成分享 Intent（调用方 createChooser + startActivity）。 */
    fun shareIntent(content: String, fileName: String): Intent {
        val dir = File(context.cacheDir, "ics").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/calendar"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
```

- [x] **Step 2: 新建 `app/src/main/res/xml/file_paths.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="ics" path="ics/" />
</paths>
```

- [x] **Step 3: 注册 FileProvider**

在 `AndroidManifest.xml` 的 `</application>` 之前插入：

```xml
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
```

- [x] **Step 4: 在 `GbuCaApp` 中构造**

- 新增字段：`lateinit var icsExport: IcsExportManager` + `private set`
- `onCreate` 中 `repo = ...` 之后追加：
  `icsExport = IcsExportManager(this, repo, settings)`
- 新增 import：`me.huanlin.gbuca.data.export.IcsExportManager`

- [x] **Step 5: 编译验证**

Run: `gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [x] **Step 6: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/data/export/IcsExportManager.kt app/src/main/res/xml/file_paths.xml app/src/main/AndroidManifest.xml app/src/main/java/me/huanlin/gbuca/GbuCaApp.kt
git commit -m "feat(export): IcsExportManager + FileProvider（SAF 写文件 / 分享 Intent）"
```

---

### Task 4: 设置页「导出到日历」分组

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/AppViewModel.kt`
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/SettingsScreen.kt`

**Interfaces:**
- Consumes: `GbuCaApp.icsExport`（Task 3）。
- Produces:
  - `AppViewModel.suggestedIcsFileName: String`
  - `AppViewModel.exportIcs(uri: Uri)`
  - `AppViewModel.shareIcs(onIntent: (Intent) -> Unit)`
  - `AppViewModel.showMessage(message: String)`

- [x] **Step 1: strings.xml 新增文案**（追加到「设置」区块）

```xml
    <string name="settings_section_export">导出到日历</string>
    <string name="settings_export_hint">把当前学期课表导出为 .ics 文件，可导入系统日历（Google 日历、Apple 日历、Outlook 等）。\n重复课次以「每周重复」事件表示，可整条编辑或删除；事件内提醒跟随上方「提前提醒时间」。</string>
    <string name="settings_export_file">导出 .ics 文件</string>
    <string name="settings_export_share">分享到日历 App</string>
    <string name="msg_export_ok">已导出当前学期课表</string>
    <string name="msg_export_empty">当前学期还没有课程，请先同步</string>
    <string name="msg_export_failed">导出失败：%1$s</string>
    <string name="msg_export_no_app">没有找到可处理日历的应用，请改用「导出 .ics 文件」</string>
```

- [x] **Step 2: AppViewModel 新增方法**（放在「服务器地址」区块之前）

```kotlin
    // ---- 导出到日历 ----

    val suggestedIcsFileName: String get() = app.icsExport.suggestedFileName(xnxq)

    /** 导出当前学期到用户通过 SAF 选择的 Uri。 */
    fun exportIcs(uri: android.net.Uri) {
        viewModelScope.launch {
            ui.value = ui.value.copy(message = null, calibrateOk = null)
            val result = runCatchingNonCancellation {
                val content = app.icsExport.build(xnxq) ?: throw NoCourses
                app.icsExport.writeToUri(uri, content)
            }
            val e = result.exceptionOrNull()
            ui.value = ui.value.copy(
                message = when {
                    e == null -> app.getString(R.string.msg_export_ok)
                    e === NoCourses -> app.getString(R.string.msg_export_empty)
                    else -> exportFailed(e)
                },
            )
        }
    }

    /** 构造分享 Intent 并交回 UI 层启动（UI 负责处理 ActivityNotFoundException）。 */
    fun shareIcs(onIntent: (android.content.Intent) -> Unit) {
        viewModelScope.launch {
            ui.value = ui.value.copy(message = null, calibrateOk = null)
            val result = runCatchingNonCancellation {
                val content = app.icsExport.build(xnxq) ?: throw NoCourses
                app.icsExport.shareIntent(content, app.icsExport.suggestedFileName(xnxq))
            }
            val intent = result.getOrNull()
            if (intent != null) {
                onIntent(intent)
            } else {
                val e = result.exceptionOrNull()
                ui.value = ui.value.copy(
                    message = if (e === NoCourses) app.getString(R.string.msg_export_empty)
                    else exportFailed(e ?: IllegalStateException("unknown")),
                )
            }
        }
    }

    fun showMessage(message: String) {
        ui.value = ui.value.copy(message = message)
    }

    /** 导出失败的兜底文案：永不出现 "?"。 */
    private fun exportFailed(e: Throwable): String = app.getString(
        R.string.msg_export_failed,
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName,
    )

    private object NoCourses : Exception()
```

- [x] **Step 3: SettingsScreen 新增分组**

在「学期配置」分组之后、「关于」分组之前插入：

```kotlin
        // ---- 导出到日历 ----
        SectionTitle(stringResource(R.string.settings_section_export))
        SettingsCard {
            Text(
                stringResource(R.string.settings_export_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                            context.startActivity(
                                Intent.createChooser(
                                    intent,
                                    context.getString(R.string.settings_export_share),
                                )
                            )
                        }.isSuccess
                        if (!ok) vm.showMessage(context.getString(R.string.msg_export_no_app))
                    }
                }) {
                    Text(stringResource(R.string.settings_export_share))
                }
            }
        }
```

新增 import：`androidx.activity.result.contract.ActivityResultContracts`（`Intent`、`Row`、`Arrangement`、
`OutlinedButton`、`rememberLauncherForActivityResult` 已存在）。

- [x] **Step 4: 编译 + 单测**

Run: `gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [x] **Step 5: 提交**

```bash
git add app/src/main/res/values/strings.xml app/src/main/java/me/huanlin/gbuca/ui/AppViewModel.kt app/src/main/java/me/huanlin/gbuca/ui/SettingsScreen.kt
git commit -m "feat(export): 设置页「导出到日历」分组（SAF 保存 + 分享到日历）"
```

---

### Task 5: 全量验证与文档

**Files:**
- Modify: `README.md`（功能列表新增一条）

- [x] **Step 1: 全量单测 + 调试构建**

Run: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL，所有单测通过（含既有 `ScheduleParserTest`、`HostNormalizerTest`）

- [x] **Step 2: README 功能列表新增**

在「后台同步」之前插入一行：

```markdown
- **导出到日历**：当前学期课表导出为 `.ics`（重复课次用 RRULE/RDATE，事件内提醒跟随设置），可保存到文件或直接分享给日历 App
```

- [x] **Step 3: 提交**

```bash
git add README.md
git commit -m "docs: README 补充「导出到日历」功能"
```

## Self-Review

- **Spec 覆盖**：事件形态（Task 2 RRULE/RDATE）✓ 交付方式（Task 3 + Task 4）✓
  VALARM 跟随设置（Task 3 `alarmMinutes`）✓ 仅当前学期（Task 4 `xnxq`）✓
  时区（Task 1 VTIMEZONE）✓ 不做项未实现 ✓
- **类型一致性**：`IcsCalendar.Event` 字段名在 Task 1/2/3 一致；
  `CourseIcsExporter.export/fileName/calendarName` 在 Task 2/3/4 一致；
  `IcsExportManager.build/writeToUri/shareIntent/suggestedFileName` 在 Task 3/4 一致。
- **占位符扫描**：无 TBD/TODO；每个代码步骤含完整代码。
