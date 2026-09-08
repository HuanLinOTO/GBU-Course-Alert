package me.huanlin.gbuca

import me.huanlin.gbuca.domain.export.CourseIcsExporter
import me.huanlin.gbuca.domain.export.IcsCalendar
import me.huanlin.gbuca.domain.model.Course
import me.huanlin.gbuca.domain.model.TermData
import me.huanlin.gbuca.domain.parser.ScheduleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** 用真实 kcxx 样本（2026-2027-1）走完整链路：解析 → 映射 → 序列化。 */
class IcsExportIntegrationTest {

    private val math101 =
        "<p><b>主任务:</b> <a>段金桥</a><p><b>上课信息:</b><p>1-16周,星期三第1-2节 8:00-9:15 B304</p>" +
            "<p>1-16周,星期五第1-2节 8:00-9:15 B304</p><p><b>课内实验:</b> <a>李丹丹</a>" +
            "<p><b>上课信息:</b><p>1-16周,星期五第3-4节 9:30-10:45 B303</p>"
    private val phy101 =
        "<p><b>主任务:</b> <a>赵金奎</a> <a>程俊青</a><p><b>上课信息:</b>" +
            "<p>1-4周,星期四第4-6节 10:10-12:05 B304</p><p>5-16周,星期四第4-6节 10:10-12:05 B304</p>" +
            "<p><b>课内实验:</b> <a>何海燕</a><p><b>上课信息:</b>" +
            "<p>2,4周,星期三第9-12节 14:00-16:45 无地点</p><p>6,8周,星期三第9-12节 14:00-16:45 无地点</p>"
    private val tut02 =
        "<p><b>主任务:</b><p><b>课内实验:</b> <a>程俊青</a><p><b>上课信息:</b>" +
            "<p>2-16双周,星期二第4-6节 10:10-12:05 B501</p>"
    private val ipc101 = "<a>单磊</a><p><b>上课信息:</b><p>1-16周,星期四第1-3节 8:00-9:55 B306"

    private fun course(rwh: String, name: String, code: String, credits: Double) = Course(
        rwh = rwh, xnxq = "2026-20271", name = name, nameEn = null, code = code, seq = "001C",
        className = "$name-01班", credits = credits, hours = 64.0, nature = "必修",
        category = "专业必修课", college = "理学院", enrollTime = null, capacity = 35,
        enrolled = 35, rawKcxx = "", unparsed = emptyList(),
    )

    private fun buildIcs(): String {
        val courses = listOf(
            course("R-MATH", "高等数学1", "MATH101", 5.0),
            course("R-PHY", "物理原理1", "PHY101", 4.0),
            course("R-TUT02", "物理课答疑I", "TUT02", 0.0),
            course("R-IPC", "思想道德与法治", "IPC101", 3.0),
        )
        val meetings = buildList {
            addAll(ScheduleParser.parse(math101, "R-MATH").meetings)
            addAll(ScheduleParser.parse(phy101, "R-PHY").meetings)
            addAll(ScheduleParser.parse(tut02, "R-TUT02").meetings)
            addAll(ScheduleParser.parse(ipc101, "R-IPC").meetings)
        }
        val events = CourseIcsExporter.export(
            xnxq = "2026-20271",
            data = TermData(courses, meetings),
            semesterStartMonday = LocalDate.of(2026, 8, 31),
            alarmMinutes = 15,
        )
        return IcsCalendar.build(
            events,
            CourseIcsExporter.calendarName("2026-20271"),
            Instant.parse("2026-09-08T09:34:00Z"),
        )
    }

    @Test
    fun `real samples produce nine events`() {
        assertEquals(9, buildIcs().split("BEGIN:VEVENT").size - 1)
    }

    @Test
    fun `every line is at most 75 octets and crlf terminated`() {
        val ics = buildIcs()
        assertTrue(ics.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertFalse(ics.contains(Regex("(?<!\r)\n")))
    }

    @Test
    fun `recurrence reflects week patterns`() {
        val ics = buildIcs()
        assertTrue(ics.contains("RRULE:FREQ=WEEKLY;INTERVAL=1;COUNT=16")) // 1-16 周
        assertTrue(ics.contains("RRULE:FREQ=WEEKLY;INTERVAL=1;COUNT=12")) // 5-16 周
        assertTrue(ics.contains("RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=8"))  // 2-16 双周
        assertTrue(ics.contains("RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=2"))  // 2,4 周 / 6,8 周
    }

    @Test
    fun `roomless labs omit location and escape commas`() {
        val ics = buildIcs()
        val labBlocks = ics.split("BEGIN:VEVENT").drop(1).map { it.substringBefore("END:VEVENT") }
        val physicsLabs = labBlocks.filter { it.contains("物理原理1 · 实验") }
        assertEquals(2, physicsLabs.size)
        assertTrue(physicsLabs.none { it.contains("LOCATION:") })
        assertTrue(physicsLabs.all { it.contains("2\\,4周") || it.contains("6\\,8周") })
    }

    @Test
    fun `first occurrences land on the expected weekdays`() {
        val ics = buildIcs()
        assertTrue(ics.contains("DTSTART;TZID=Asia/Shanghai:20260902T080000")) // 第1周周三
        assertTrue(ics.contains("DTSTART;TZID=Asia/Shanghai:20261001T101000")) // 第5周周四
        assertTrue(ics.contains("DTSTART;TZID=Asia/Shanghai:20260908T101000")) // 第2周周二（双周）
    }
}
