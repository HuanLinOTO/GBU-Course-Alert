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
