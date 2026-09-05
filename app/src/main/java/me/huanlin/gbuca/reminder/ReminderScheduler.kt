package me.huanlin.gbuca.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import me.huanlin.gbuca.GbuCaApp
import me.huanlin.gbuca.R
import me.huanlin.gbuca.domain.logic.ScheduleLogic
import me.huanlin.gbuca.domain.model.Meeting
import me.huanlin.gbuca.widget.TodayWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 精确闹钟编排：为未来 24h 内的课次安排提醒（提前 N 分钟）。
 * 已安排闹钟以 key（xnxq|epochDay|startTime|idx）记录，重排时先取消旧的。
 */
class ReminderScheduler(
    private val context: Context,
    private val settings: me.huanlin.gbuca.data.local.SettingsStore,
) {

    companion object {
        const val CHANNEL_ID = "class_reminders"
        private const val RC_BASE = 4200
    }    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun ensureChannel() {
        val ch = NotificationChannelCompat.Builder(
            CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH
        ).setName(context.getString(R.string.channel_reminders)).build()
        NotificationManagerCompat.from(context).createNotificationChannel(ch)
    }

    fun canScheduleExact(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        alarmManager.canScheduleExactAlarms()
    } else true

    fun requestExactPermission() {
        if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** 非阻塞版：后台线程执行重排。UI 调用请用此入口。 */
    fun rescheduleAsync(hours: Int = 24) {
        Thread {
            runCatching { reschedule(hours) }
        }.start()
    }

    /** 重排未来 [hours] 内的闹钟。返回安排数量。阻塞（含 DB 查询），勿在主线程调用。 */
    fun reschedule(hours: Int = 24): Int {
        val old = settings.scheduledAlarmKeys
        old.forEach { cancelByKey(it) }
        if (!settings.remindersEnabled) {
            settings.scheduledAlarmKeys = emptySet()
            return 0
        }
        val minutes = settings.reminderMinutes

        val now = LocalDateTime.now()
        val xnxq = currentXnxq()
        val meetings = GbuCaApp.instance.repo.let { repo ->
            kotlinx.coroutines.runBlocking { repo.meetingsByXnxq(xnxq) }
        }
        val startMonday = GbuCaApp.instance.settings.semesterStartMonday(xnxq = xnxq)

        val newKeys = mutableSetOf<String>()
        val horizon = now.plusHours(hours.toLong())
        val liveNotifierReady = settings.remindersEnabled
        for (dayOffset in 0..hours / 24 + 1) {
            val date = now.toLocalDate().plusDays(dayOffset.toLong())
            val week = ScheduleLogic.weekOf(date, startMonday) ?: continue
            val dayList = ScheduleLogic.meetingsOn(date, week, meetings)
            dayList.forEachIndexed { idx, m ->
                val classStart = LocalDateTime.of(date, m.startTime)
                val classEnd = LocalDateTime.of(date, m.endTime)
                // Live Update：候课倒计时 + 上课期间常驻进度通知
                if (liveNotifierReady && classStart.isBefore(horizon)) {
                    LiveUpdateNotifier.scheduleForClass(
                        context, courseNameOf(m), m.room ?: "", classStart, classEnd, now,
                        leadMinutes = minutes,
                    )
                }
                val at = classStart.minusMinutes(minutes.toLong())
                if (at.isBefore(now) || at.isAfter(horizon)) return@forEachIndexed
                val key = keyOf(date, m, idx)
                schedule(at, key, m)
                newKeys += key
            }
        }
        settings.scheduledAlarmKeys = newKeys
        return newKeys.size
    }

    private fun currentXnxq(): String =
        GbuCaApp.instance.settings.selectedXnxq ?: GbuCaApp.instance.client.fallbackXnxq().third

    private fun keyOf(date: LocalDate, m: Meeting, idx: Int) = "${date.toEpochDay()}|${m.startTime}|$idx"

    private fun pending(key: String, m: Meeting?, create: Boolean): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_REMIND
            putExtra(AlarmReceiver.EXTRA_KEY, key)
            m?.let {
                putExtra(AlarmReceiver.EXTRA_NAME, courseNameOf(it))
                putExtra(AlarmReceiver.EXTRA_ROOM, it.room ?: "")
                putExtra(AlarmReceiver.EXTRA_TIME, "${it.startTime}-${it.endTime}")
            }
        }
        val requestCode = key.hashCode()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun courseNameOf(m: Meeting): String =
        kotlinx.coroutines.runBlocking {
            GbuCaApp.instance.repo.courseByRwh(m.rwh)?.name ?: "课程"
        }

    private fun cancelByKey(key: String) {
        alarmManager.cancel(pending(key, null, false))
    }

    private fun schedule(at: LocalDateTime, key: String, m: Meeting) {
        ensureChannel()
        val pi = pending(key, m, true)
        val trigger = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        }
    }

    fun cancelAll() {
        settings.scheduledAlarmKeys.forEach { cancelByKey(it) }
        settings.scheduledAlarmKeys = emptySet()
        LiveUpdateNotifier.cancel(context)
    }
}

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REMIND = "me.huanlin.gbuca.action.REMIND"
        const val EXTRA_KEY = "key"
        const val EXTRA_NAME = "name"
        const val EXTRA_ROOM = "room"
        const val EXTRA_TIME = "time"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == LiveUpdateNotifier.ACTION_TICK) {
            LiveUpdateNotifier.onTick(
                context,
                intent.getStringExtra(LiveUpdateNotifier.EXTRA_NAME) ?: return,
                intent.getStringExtra(LiveUpdateNotifier.EXTRA_ROOM).orEmpty(),
                intent.getLongExtra(LiveUpdateNotifier.EXTRA_START, 0L),
                intent.getLongExtra(LiveUpdateNotifier.EXTRA_END, 0L),
                intent.getLongExtra(LiveUpdateNotifier.EXTRA_ANCHOR, 0L),
                intent.getIntExtra(LiveUpdateNotifier.EXTRA_SCALE, 1),
                intent.getLongExtra(LiveUpdateNotifier.EXTRA_WAIT_START, 0L),
            )
            return
        }
        if (intent.action != ACTION_REMIND) return
        val name = intent.getStringExtra(EXTRA_NAME) ?: return
        val room = intent.getStringExtra(EXTRA_ROOM).orEmpty()
        val time = intent.getStringExtra(EXTRA_TIME).orEmpty()

        val app = context.applicationContext as? GbuCaApp ?: return
        app.reminderScheduler.ensureChannel()

        val notif = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_class)
            .setContentTitle(context.getString(R.string.notif_class_title, name))
            .setContentText(
                if (room.isBlank()) time else context.getString(R.string.notif_class_text, time, room)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        NotificationManagerCompat.from(context).notify(name.hashCode(), notif)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as? GbuCaApp ?: return
        // goAsync：异步完成重排，避免 onReceive 返回后进程降级导致线程被杀
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                runCatching { app.reminderScheduler.reschedule() }
                runCatching { TodayWidgetReceiver.refreshAll(app) }
            } finally {
                pending.finish()
            }
        }
    }
}
