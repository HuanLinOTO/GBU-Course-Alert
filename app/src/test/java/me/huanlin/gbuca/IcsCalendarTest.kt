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
        assertTrue(text.contains("DTEND;TZID=Asia/Shanghai:20260902T091500\r\n"))
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
