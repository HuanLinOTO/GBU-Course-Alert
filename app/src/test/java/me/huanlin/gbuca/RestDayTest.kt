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
