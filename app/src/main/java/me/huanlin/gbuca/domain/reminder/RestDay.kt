package me.huanlin.gbuca.domain.reminder

import java.time.LocalDate

/**
 * 「今日休息」判定：休息只对存储的那一天生效，跨零点自动失效。
 * 存储值为 epochDay，null 表示未休息。
 */
object RestDay {

    /** 存储的休息日与今天相同即处于休息状态；null / 其他日期均不生效。 */
    fun isResting(restEpochDay: Long?, today: LocalDate): Boolean =
        restEpochDay != null && restEpochDay == today.toEpochDay()
}
