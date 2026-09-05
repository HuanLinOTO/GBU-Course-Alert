package me.huanlin.gbuca

import me.huanlin.gbuca.domain.logic.ScheduleLogic
import me.huanlin.gbuca.domain.logic.ScheduleLogic.ClassStatus
import me.huanlin.gbuca.domain.model.Meeting
import me.huanlin.gbuca.domain.parser.ScheduleParser
import me.huanlin.gbuca.domain.time.TimeGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ScheduleParserTest {

    // ---- 真实 kcxx 样本（2026-2027-1，测试账号 26100070） ----

    private val math101 = """
        <p><b>主任务:</b> <a onclick="queryJsxx('226162029')">段金桥</a>
        <p><b>上课信息:</b>
        <p>1-16周,星期三第1-2节 8:00-9:15 B304</p>
        <p>1-16周,星期五第1-2节 8:00-9:15 B304</p>
        <p><b>课内实验:</b> <a>李丹丹</a>
        <p><b>上课信息:</b>
        <p>1-16周,星期五第3-4节 9:30-10:45 B303</p>
    """.trimIndent()

    private val phy101 = """
        <p><b>主任务:</b> <a onclick="">赵金奎</a> <a onclick="">程俊青</a>
        <p><b>上课信息:</b>
        <p>1-4周,星期四第4-6节 10:10-12:05 B304</p>
        <p>5-16周,星期四第4-6节 10:10-12:05 B304</p>
        <p><b>课内实验:</b> <a>何海燕</a> <a>刘天辉</a> <a>张兆伟</a> <a>王传寿</a>
        <p><b>上课信息:</b>
        <p>2,4周,星期三第9-12节 14:00-16:45 无地点</p>
        <p>6,8周,星期三第9-12节 14:00-16:45 无地点</p>
        <p>10,12周,星期三第9-12节 14:00-16:45 无地点</p>
        <p>14,16周,星期三第9-12节 14:00-16:45 无地点</p>
    """.trimIndent()

    private val tut02 = """
        <p><b>主任务:</b>
        <p><b>课内实验:</b> <a>程俊青</a>
        <p><b>上课信息:</b>
        <p>2-16双周,星期二第4-6节 10:10-12:05 B501</p>
    """.trimIndent()

    private val tut01 = """
        <a onclick="">徐志伟</a>
        <p><b>上课信息:</b>
        <p>1-16周,星期一第15-16节 18:30-19:45 无地点</p>
    """.trimIndent()

    private val cs101 = """
        <p><b>主任务:</b> <a>徐志伟</a> <a>万宗祺</a>
        <p><b>上课信息:</b>
        <p>1-8周,星期一第3-4节 9:30-10:45 B205</p>
        <p>9-16周,星期一第3-4节 9:30-10:45 B205</p>
        <p><b>课内实验:</b> <a>何青强</a>
        <p><b>上课信息:</b>
        <p>1-16周,星期三第3-6节 9:30-12:15 B407</p>
    """.trimIndent()

    private val ipc101 = "<a>单磊</a><p><b>上课信息:</b><p>1-16周,星期四第1-3节 8:00-9:55 B306"

    @Test
    fun `math101 parses 3 meetings with roles and teachers`() {
        val r = ScheduleParser.parse(math101, "M1")
        assertEquals(3, r.meetings.size)
        assertTrue(r.unparsedLines.isEmpty())

        val main = r.meetings.filter { it.role == ScheduleParser.ROLE_MAIN }
        val lab = r.meetings.filter { it.role == ScheduleParser.ROLE_LAB }
        assertEquals(2, main.size)
        assertEquals(1, lab.size)
        assertEquals(listOf("段金桥"), main[0].teachers)
        assertEquals(listOf("李丹丹"), lab[0].teachers)

        val wed = main[0]
        assertEquals(3, wed.weekday)
        assertEquals(1, wed.startPeriod)
        assertEquals(2, wed.endPeriod)
        assertEquals(LocalTime.of(8, 0), wed.startTime)
        assertEquals(LocalTime.of(9, 15), wed.endTime)
        assertEquals("B304", wed.room)
        assertEquals((1..16).toSet(), wed.weeks)
    }

    @Test
    fun `phy101 parses 2 main plus 4 lab meetings`() {
        val r = ScheduleParser.parse(phy101, "P1")
        assertEquals(6, r.meetings.size)
        val main = r.meetings.filter { it.role == ScheduleParser.ROLE_MAIN }
        val lab = r.meetings.filter { it.role == ScheduleParser.ROLE_LAB }
        assertEquals(2, main.size)
        assertEquals(4, lab.size)
        assertEquals(listOf("赵金奎", "程俊青"), main[0].teachers)
        assertEquals(listOf("何海燕", "刘天辉", "张兆伟", "王传寿"), lab[0].teachers)
        assertEquals(setOf(1, 2, 3, 4), main[0].weeks)
        assertEquals(setOf(5, 6), main[1].weeks.subset(5, 6))
        assertNull(lab[0].room)
        assertEquals(setOf(2, 4), lab[0].weeks)
        assertEquals(setOf(14, 16), lab[3].weeks)
        assertEquals(9, lab[0].startPeriod)
        assertEquals(12, lab[0].endPeriod)
    }

    private fun Set<Int>.subset(a: Int, b: Int): Set<Int> = filter { it in a..b }.toSet()

    @Test
    fun `tut02 odd main without meetings, lab on even weeks`() {
        val r = ScheduleParser.parse(tut02, "T2")
        assertEquals(1, r.meetings.size)
        val m = r.meetings[0]
        assertEquals(ScheduleParser.ROLE_LAB, m.role)
        assertEquals(listOf("程俊青"), m.teachers)
        assertEquals((2..16).filter { it % 2 == 0 }.toSet(), m.weeks)
        assertEquals(2, m.weekday)
        assertEquals("B501", m.room)
        assertEquals(LocalTime.of(10, 10), m.startTime)
        assertEquals(LocalTime.of(12, 5), m.endTime)
    }

    @Test
    fun `tut01 defaults to main role when label missing`() {
        val r = ScheduleParser.parse(tut01, "T1")
        assertEquals(1, r.meetings.size)
        val m = r.meetings[0]
        assertEquals(ScheduleParser.ROLE_MAIN, m.role)
        assertEquals(listOf("徐志伟"), m.teachers)
        assertNull(m.room)
        assertEquals(1, m.weekday)
        assertEquals(15, m.startPeriod)
        assertEquals(16, m.endPeriod)
        assertEquals(LocalTime.of(18, 30), m.startTime)
    }

    @Test
    fun `cs101 split terms and lab`() {
        val r = ScheduleParser.parse(cs101, "C1")
        assertEquals(3, r.meetings.size)
        val main = r.meetings.filter { it.role == ScheduleParser.ROLE_MAIN }
        assertEquals(setOf((1..8).toSet(), (9..16).toSet()), main.map { it.weeks }.toSet())
        val lab = r.meetings.last { it.role == ScheduleParser.ROLE_LAB }
        assertEquals(3, lab.startPeriod)
        assertEquals(6, lab.endPeriod)
        assertEquals("B407", lab.room)
    }

    @Test
    fun `three-period compressed times parsed literally`() {
        val r = ScheduleParser.parse(ipc101, "I1")
        assertEquals(1, r.meetings.size)
        val m = r.meetings[0]
        assertEquals(1, m.startPeriod)
        assertEquals(3, m.endPeriod)
        assertEquals(LocalTime.of(8, 0), m.startTime)
        assertEquals(LocalTime.of(9, 55), m.endTime) // 非大节网格的 10:05
    }

    @Test
    fun `week tokens`() {
        assertEquals((1..16).toSet(), ScheduleParser.parseWeeks("1-16"))
        assertEquals((2..16).filter { it % 2 == 0 }.toSet(), ScheduleParser.parseWeeks("2-16双"))
        assertEquals((1..15).filter { it % 2 == 1 }.toSet(), ScheduleParser.parseWeeks("1-15单"))
        assertEquals(setOf(2, 4), ScheduleParser.parseWeeks("2,4"))
        assertEquals(emptySet<Int>(), ScheduleParser.parseWeeks("abc"))
    }

    @Test
    fun `unparsable line is kept`() {
        val r = ScheduleParser.parse("<p>某行无法解析</p>", "X")
        assertEquals(0, r.meetings.size)
        assertEquals(listOf("某行无法解析"), r.unparsedLines)
    }
}

class TimeGridTest {
    @Test
    fun `default grid`() {
        val p1 = TimeGrid.period(1)
        val p18 = TimeGrid.period(18)
        assertNotNull(p1)
        assertNotNull(p18)
        assertEquals(LocalTime.of(8, 0), p1!!.start)
        assertEquals(LocalTime.of(21, 15), p18!!.end)
        val blocks = TimeGrid.bigBlocks()
        assertEquals(9, blocks.size)
        assertEquals(LocalTime.of(9, 30), blocks[1].start)
        assertEquals(LocalTime.of(10, 45), blocks[1].end)
    }

    @Test
    fun `kbjclist utc plus 8h`() {
        TimeGrid.update(
            listOf(
                TimeGrid.KbjcItem(1, "1970-01-01 00:00:00.0", "1970-01-01 00:35:00.0", 1, 1),
                TimeGrid.KbjcItem(2, "00:40", "01:15", 1, 1),
            )
        )
        assertEquals(LocalTime.of(8, 0), TimeGrid.period(1)!!.start)
        assertEquals(LocalTime.of(9, 15), TimeGrid.period(2)!!.end)
        TimeGrid.reset()
    }
}

class ScheduleLogicTest {
    private val start = LocalDate.of(2026, 8, 31) // 周一

    @Test
    fun `weekOf`() {
        assertEquals(1, ScheduleLogic.weekOf(LocalDate.of(2026, 8, 31), start))
        assertEquals(1, ScheduleLogic.weekOf(LocalDate.of(2026, 9, 4), start))
        assertEquals(2, ScheduleLogic.weekOf(LocalDate.of(2026, 9, 7), start))
        assertNull(ScheduleLogic.weekOf(LocalDate.of(2026, 8, 30), start))
        assertNull(ScheduleLogic.weekOf(LocalDate.of(2027, 6, 1), start))
    }

    private fun m(weeks: Set<Int>, weekday: Int, s: String, e: String) = Meeting(
        rwh = "x", role = "主任务", teachers = emptyList(), weeks = weeks, weekday = weekday,
        startPeriod = 1, endPeriod = 2, startTime = LocalTime.parse(s), endTime = LocalTime.parse(e),
        room = null, rawText = "",
    )

    @Test
    fun `meetingsOn filters week and weekday`() {
        val meetings = listOf(
            m((1..16).toSet(), 5, "11:00", "12:15"),
            m(setOf(2, 4), 5, "14:00", "16:45"),
            m((1..16).toSet(), 3, "8:00".padStart(5, '0'), "09:15"),
        )
        val fri = LocalDate.of(2026, 9, 4) // 第1周周五
        val got = ScheduleLogic.meetingsOn(fri, 1, meetings)
        assertEquals(1, got.size)
        assertEquals("11:00", got[0].startTime.toString())
        val friW2 = LocalDate.of(2026, 9, 11)
        assertEquals(2, ScheduleLogic.meetingsOn(friW2, 2, meetings).size)
    }

    @Test
    fun `statusAt`() {
        val list = listOf(
            m((1..16).toSet(), 1, "08:00", "09:15"),
            m((1..16).toSet(), 1, "09:30", "10:45"),
        )
        assertTrue(ScheduleLogic.statusAt(LocalTime.of(8, 30), list) is ClassStatus.InClass)
        assertTrue(ScheduleLogic.statusAt(LocalTime.of(9, 20), list) is ClassStatus.Upcoming)
        assertTrue(ScheduleLogic.statusAt(LocalTime.of(11, 0), list) is ClassStatus.Finished)
        assertTrue(ScheduleLogic.statusAt(LocalTime.of(11, 0), emptyList()) is ClassStatus.Free)
    }

    @Test
    fun `formatWeeks`() {
        assertEquals("1-16周", ScheduleLogic.formatWeeks((1..16).toSet()))
        assertEquals("2-16双周", ScheduleLogic.formatWeeks((2..16).filter { it % 2 == 0 }.toSet()))
        assertEquals("1-15单周", ScheduleLogic.formatWeeks((1..15).filter { it % 2 == 1 }.toSet()))
        assertEquals("2,4周", ScheduleLogic.formatWeeks(setOf(2, 4)))
        assertEquals("1-4,8周", ScheduleLogic.formatWeeks(setOf(1, 2, 3, 4, 8)))
    }

    @Test
    fun `gapAfterPrevious`() {
        val a = m((1..16).toSet(), 1, "08:00", "09:15")
        val b = m((1..16).toSet(), 1, "09:30", "10:45")
        assertEquals(15L, ScheduleLogic.gapAfterPrevious(b, listOf(a, b)))
        assertNull(ScheduleLogic.gapAfterPrevious(a, listOf(a, b)))
    }
}
