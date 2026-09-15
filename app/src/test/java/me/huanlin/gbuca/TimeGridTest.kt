package me.huanlin.gbuca

import me.huanlin.gbuca.domain.time.TimeGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.time.LocalTime

/** TimeGrid.locate：时刻 → (节次, 行内真实时间比例)。单例可变，测试前先 reset 保证用默认网格。 */
class TimeGridTest {

    @Before
    fun resetGrid() = TimeGrid.reset()

    @Test
    fun `早于首节归入首节比例0`() {
        assertEquals(1 to 0f, TimeGrid.locate(LocalTime.of(7, 30)))
    }

    @Test
    fun `节首比例为0`() {
        assertEquals(9 to 0f, TimeGrid.locate(LocalTime.of(14, 0)))
    }

    @Test
    fun `节内线性插值`() {
        val (idx, frac) = checkNotNull(TimeGrid.locate(LocalTime.of(14, 30))) // 第9行跨度 14:00→14:40
        assertEquals(9, idx)
        assertEquals(30f / 40f, frac, 1e-4f)
    }

    @Test
    fun `节末时刻停在本节行内比例`() {
        // 12:15 是第6节结束，但第6行跨度到 12:30（下一节开始），35/50=0.7，不贴 12:30 刻度线
        assertEquals(6 to 0.7f, TimeGrid.locate(LocalTime.of(12, 15)))
    }

    @Test
    fun `课间按真实时间落在前行内`() {
        // 15:20 在第10节(15:15结束)与第11节(15:30开始)之间：行跨度 14:40→15:30，40/50=0.8
        assertEquals(10 to 0.8f, TimeGrid.locate(LocalTime.of(15, 20)))
    }

    @Test
    fun `晚于末节归入末节末尾`() {
        assertEquals(18 to 1f, TimeGrid.locate(LocalTime.of(23, 0)))
    }

    @Test
    fun `末节结束时刻本身也归末节末尾`() {
        assertEquals(18 to 1f, TimeGrid.locate(LocalTime.of(21, 15)))
    }

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

    /** 周二/四连排网格（官方作息：6 段 × 3 节，如第4-6节 10:10-12:05）。 */
    @Test
    fun `tt grid for tuesday thursday`() {
        assertEquals(LocalTime.of(10, 10), TimeGrid.period(4, 2)!!.start)
        assertEquals(LocalTime.of(12, 5), TimeGrid.period(6, 4)!!.end)
        assertEquals(LocalTime.of(9, 55), TimeGrid.period(3, 2)!!.end)
        assertEquals(LocalTime.of(14, 30), TimeGrid.period(10, 2)!!.start)
        assertEquals(LocalTime.of(20, 45), TimeGrid.period(18, 4)!!.end)
        assertEquals(18, TimeGrid.DEFAULT_TT.size)
        assertEquals(6, TimeGrid.DEFAULT_TT.groupBy { it.bigBlock }.size)
    }

    /** 周一/三/五及周末仍用默认（kbjclist）网格。 */
    @Test
    fun `non tuesday thursday uses default grid`() {
        assertEquals(LocalTime.of(12, 15), TimeGrid.period(6, 1)!!.end)
        assertEquals(LocalTime.of(12, 15), TimeGrid.period(6, 3)!!.end)
        assertEquals(LocalTime.of(12, 15), TimeGrid.period(6, 5)!!.end)
        assertEquals(LocalTime.of(12, 15), TimeGrid.period(6, 6)!!.end)
        assertEquals(LocalTime.of(12, 15), TimeGrid.period(6, 7)!!.end)
    }

    /** kbjclist 覆盖只作用于周一三五网格，不影响内置周二四网格。 */
    @Test
    fun `kbjclist update leaves tt grid untouched`() {
        TimeGrid.update(listOf(TimeGrid.KbjcItem(1, "00:00", "00:45", 1, 1)))
        assertEquals(LocalTime.of(8, 45), TimeGrid.period(1)!!.end)
        assertEquals(LocalTime.of(12, 5), TimeGrid.period(6, 2)!!.end)
        TimeGrid.reset()
    }
}
