package me.huanlin.gbuca.domain.logic

import me.huanlin.gbuca.domain.model.Meeting
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

object ScheduleLogic {

    /** 学期第 1 周周一 → 日期所在教学周。不在学期内返回 null。 */
    fun weekOf(date: LocalDate, semesterStartMonday: LocalDate): Int? {
        if (semesterStartMonday.dayOfWeek != DayOfWeek.MONDAY) return null
        val days = ChronoUnit.DAYS.between(semesterStartMonday, date)
        if (days < 0) return null
        val week = (days / 7).toInt() + 1
        return if (week in 1..30) week else null
    }

    /** 指定日期（教学周 week）应上的课次，按开始时间排序。 */
    fun meetingsOn(date: LocalDate, week: Int?, meetings: List<Meeting>): List<Meeting> {
        if (week == null) return emptyList()
        val wd = date.dayOfWeek.value
        return meetings.filter { it.weekday == wd && week in it.weeks }
            .sortedWith(compareBy({ it.startTime }, { it.endPeriod }))
    }

    sealed interface ClassStatus {
        data class InClass(val meeting: Meeting, val endsInMinutes: Long) : ClassStatus
        data class Upcoming(val meeting: Meeting, val startsInMinutes: Long) : ClassStatus
        data object Finished : ClassStatus
        data object Free : ClassStatus
    }

    fun statusAt(now: LocalTime, list: List<Meeting>): ClassStatus {
        val current = list.firstOrNull { !now.isBefore(it.startTime) && now.isBefore(it.endTime) }
        if (current != null) {
            return ClassStatus.InClass(current, ChronoUnit.MINUTES.between(now, current.endTime))
        }
        val next = list.firstOrNull { it.startTime.isAfter(now) }
        return when {
            next != null -> ClassStatus.Upcoming(next, ChronoUnit.MINUTES.between(now, next.startTime))
            list.isEmpty() -> ClassStatus.Free
            else -> ClassStatus.Finished
        }
    }

    /** 与本节之间隔：与前一节课结束时间的间隔（分钟），无前课返回 null。 */
    fun gapAfterPrevious(meeting: Meeting, dayList: List<Meeting>): Long? {
        val prevEnd = dayList
            .filter { it !== meeting && !it.endTime.isAfter(meeting.startTime) }
            .maxByOrNull { it.endTime }?.endTime ?: return null
        return ChronoUnit.MINUTES.between(prevEnd, meeting.startTime)
    }

    /** 周集合 → 紧凑文本：`1-16周` / `2-16双周` / `2,4周` / `1-4,8周` */
    fun formatWeeks(weeks: Set<Int>): String {
        if (weeks.isEmpty()) return ""
        val sorted = weeks.sorted()
        if (sorted.size >= 3) {
            val allOdd = sorted.all { it % 2 == 1 }
            val allEven = sorted.all { it % 2 == 0 }
            val contiguous = sorted.last() - sorted.first() == (sorted.size - 1) * 2
            if (contiguous && (allOdd || allEven)) {
                val suffix = if (allOdd) "单周" else "双周"
                return "${sorted.first()}-${sorted.last()}$suffix"
            }
        }
        val sb = StringBuilder()
        var start = sorted.first()
        var prev = start
        for (i in 1 until sorted.size) {
            val cur = sorted[i]
            if (cur != prev + 1) {
                if (sb.isNotEmpty()) sb.append(',')
                sb.append(if (start == prev) "$start" else "$start-$prev")
                start = cur
            }
            prev = cur
        }
        if (sb.isNotEmpty()) sb.append(',')
        sb.append(if (start == prev) "$start" else "$start-$prev")
        sb.append("周")
        return sb.toString()
    }

    fun weekdayName(weekday: Int): String = when (weekday) {
        1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
        5 -> "周五"; 6 -> "周六"; 7 -> "周日"; else -> "?"
    }
}
