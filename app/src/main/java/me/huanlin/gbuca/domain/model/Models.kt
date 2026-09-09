package me.huanlin.gbuca.domain.model

import java.time.LocalTime
import java.time.temporal.ChronoUnit

data class Course(
    val rwh: String,
    val xnxq: String,
    val name: String,
    val nameEn: String?,
    val code: String?,
    val seq: String?,
    val className: String?,
    val credits: Double,
    val hours: Double,
    val nature: String?,
    val category: String?,
    val college: String?,
    val enrollTime: String?,
    val capacity: Int?,
    val enrolled: Int?,
    val rawKcxx: String,
    val unparsed: List<String>,
)

data class Meeting(
    val rwh: String,
    val role: String,
    val teachers: List<String>,
    val weeks: Set<Int>,
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val room: String?,
    val rawText: String,
) {
    val durationMinutes: Long get() = ChronoUnit.MINUTES.between(startTime, endTime)

    /** 课次时段标识（星期-开始-角色）：课表页点击跳详情时定位并高亮对应课次。 */
    val slotKey: String get() = "$weekday-$startTime-$role"
}

data class TermData(
    val courses: List<Course>,
    val meetings: List<Meeting>,
) {
    val courseByRwh: Map<String, Course> by lazy { courses.associateBy { it.rwh } }
}
