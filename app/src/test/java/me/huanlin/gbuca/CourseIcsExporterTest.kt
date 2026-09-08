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
            listOf(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 23), LocalDate.of(2026, 10, 28)),
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
        assertTrue(e.description.contains("教师：段金桥"))
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
