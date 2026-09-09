package me.huanlin.gbuca

import me.huanlin.gbuca.domain.time.TimeGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.time.LocalTime

/** TimeGrid.locate：时刻 → (节次, 节内比例)。单例可变，测试前先 reset 保证用默认网格。 */
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
        val (idx, frac) = checkNotNull(TimeGrid.locate(LocalTime.of(14, 30))) // 第9节 14:00-14:35
        assertEquals(9, idx)
        assertEquals(30f / 35f, frac, 1e-4f)
    }

    @Test
    fun `课间归入前一节末尾`() {
        // 15:20 在第10节(15:15 结束)与第11节(15:30 开始)之间的大课间
        assertEquals(10 to 1f, TimeGrid.locate(LocalTime.of(15, 20)))
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
}
