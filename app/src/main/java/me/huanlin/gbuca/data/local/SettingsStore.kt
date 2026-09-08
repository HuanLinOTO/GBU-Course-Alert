package me.huanlin.gbuca.data.local

import android.content.Context
import androidx.core.content.edit
import me.huanlin.gbuca.domain.reminder.RestDay
import java.time.DayOfWeek
import java.time.LocalDate

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("gbuca_settings", Context.MODE_PRIVATE)

    /** 学期第 1 周周一，按 xnxq 存储。解析顺序：用户手动设置 > 教务系统校准值 > 按校历默认推算。 */
    fun semesterStartMonday(xnxq: String): LocalDate {
        prefs.getString("sem_start_$xnxq", null)?.let {
            runCatching { return LocalDate.parse(it) }
        }
        prefs.getString("sem_start_server_$xnxq", null)?.let {
            runCatching { return LocalDate.parse(it) }
        }
        return defaultStartMonday(xnxq)
    }

    fun setSemesterStartMonday(xnxq: String, date: LocalDate) {
        prefs.edit { putString("sem_start_$xnxq", date.toString()) }
    }

    /** 清除用户手动设置（用于「从教务系统校准」强制覆盖）。 */
    fun clearSemesterStartCustomized(xnxq: String) {
        prefs.edit { remove("sem_start_$xnxq") }
    }

    /** 教务系统 getXnxqByRq 校准出的第 1 周周一（优先级低于用户手动设置）。 */
    fun setServerSemesterStartMonday(xnxq: String, date: LocalDate) {
        prefs.edit { putString("sem_start_server_$xnxq", date.toString()) }
    }

    fun isSemesterStartCustomized(xnxq: String): Boolean =
        prefs.contains("sem_start_$xnxq")

    /** 用户选择的 xnxq；null = 自动。 */
    var selectedXnxq: String?
        get() = prefs.getString("selected_xnxq", null)
        set(v) = prefs.edit { putString("selected_xnxq", v) }

    /** 教务系统主机名（裸域名，OOBE 填写）；空 = 未配置。 */
    var jwxtHost: String
        get() = prefs.getString("jwxt_host", null)?.trim().orEmpty()
        set(v) = prefs.edit { putString("jwxt_host", v.trim()) }

    /** 统一认证（iAAA）主机名（裸域名，OOBE 填写）；空 = 未配置。 */
    var iaaaHost: String
        get() = prefs.getString("iaaa_host", null)?.trim().orEmpty()
        set(v) = prefs.edit { putString("iaaa_host", v.trim()) }

    /** OOBE 是否已完整走过（含权限与提醒步骤）；老安装由门控判定为已完成，不写该标记。 */
    var oobeDone: Boolean
        get() = prefs.getBoolean("oobe_done", false)
        set(v) = prefs.edit { putBoolean("oobe_done", v) }

    var reminderMinutes: Int
        get() = prefs.getInt("reminder_minutes", 15)
        set(v) = prefs.edit { putInt("reminder_minutes", v.coerceIn(0, 120)) }

    var remindersEnabled: Boolean
        get() = prefs.getBoolean("reminders_enabled", true)
        set(v) = prefs.edit { putBoolean("reminders_enabled", v) }

    /** 「今日休息」的日期（epochDay）；null = 未休息。跨零点自动失效。 */
    var restDay: Long?
        get() = prefs.getLong(KEY_REST_DAY, NO_REST).takeIf { it != NO_REST }
        set(v) = prefs.edit {
            if (v == null) remove(KEY_REST_DAY) else putLong(KEY_REST_DAY, v)
        }

    /** 休息是否对今天生效（跨零点自动为 false）。 */
    fun isRestingToday(): Boolean = RestDay.isResting(restDay, LocalDate.now())

    var lastSyncAt: Long
        get() = prefs.getLong("last_sync_at", 0L)
        set(v) = prefs.edit { putLong("last_sync_at", v) }

    /** "xnxq|epochDay|idx" 列表，用于重排/取消闹钟。 */
    var scheduledAlarmKeys: Set<String>
        get() = prefs.getStringSet("scheduled_alarms", emptySet()) ?: emptySet()
        set(v) = prefs.edit { putStringSet("scheduled_alarms", v) }

    companion object {
        private const val KEY_REST_DAY = "rest_day"

        /** SharedPreferences 没有 null long：用哨兵值表示「未休息」。 */
        private const val NO_REST = Long.MIN_VALUE

        /** "2026-20271" → 2026-08-31（9月1日当周周一）；"2026-20272" → 次年3月1日当周周一。 */
        fun defaultStartMonday(xnxq: String): LocalDate {
            val m = Regex("""^(\d{4})-\d{4}([12])$""").find(xnxq) ?: return LocalDate.now().with(DayOfWeek.MONDAY)
            val year = m.groupValues[1].toInt()
            val term = m.groupValues[2].toInt()
            val anchor = if (term == 1) LocalDate.of(year, 9, 1) else LocalDate.of(year + 1, 3, 1)
            return anchor.with(DayOfWeek.MONDAY)
        }
    }
}
