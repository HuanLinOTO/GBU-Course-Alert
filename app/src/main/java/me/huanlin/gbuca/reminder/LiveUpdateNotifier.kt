package me.huanlin.gbuca.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import me.huanlin.gbuca.R
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Android 16 Live Updates：上课期间的常驻进度通知（ProgressStyle）。
 * 每分钟由 AlarmReceiver 触发一次刷新，下课自动清除。
 */
class LiveUpdateNotifier {

    companion object {
        const val CHANNEL_ID = "live_updates"
        const val ACTION_TICK = "me.huanlin.gbuca.action.LIVE_TICK"
        const val EXTRA_NAME = "live_name"
        const val EXTRA_ROOM = "live_room"
        const val EXTRA_START = "live_start"
        const val EXTRA_END = "live_end"
        const val EXTRA_ANCHOR = "live_anchor"
        const val EXTRA_SCALE = "live_scale"
        const val EXTRA_WAIT_START = "live_wait_start"
        private const val NOTIF_ID = 4713
        private const val TICK_RC = 9981
        private const val ACTION_RC = 4714
        private val ACCENT = 0xFF1B6EF3.toInt()
        private val GREEN = 0xFF1E8E3E.toInt()
        private val ORANGE = 0xFFE8710A.toInt()
        private val ICON_COLORS = intArrayOf(
            0xFF1B6EF3.toInt(), 0xFF1E8E3E.toInt(), 0xFFE8710A.toInt(), 0xFF9C27B0.toInt(),
            0xFF00897B.toInt(), 0xFFD81B60.toInt(), 0xFF5E35B1.toInt(), 0xFF00838F.toInt(),
        )

        /** 测试流程的时间倍率。 */
        private const val TEST_SCALE = 12

        /** 进度条最后一段（绿色「快下课」）的时长。 */
        private const val LAST_STAGE_MS = 10 * 60_000L

        /** 课长超过该值才显示绿色冲刺段 + 里程碑，避免短课拥挤。 */
        private const val MILESTONE_MIN_TOTAL_MS = 20 * 60_000L

        /** 进程内只做一次通道检查；tick 高频调用时必须是 no-op，否则删通道会导致通知销毁重建。 */
        private var channelReady = false

        fun ensureChannel(context: Context) {
            if (channelReady) return
            val nm = NotificationManagerCompat.from(context)
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            // 旧通道为 IMPORTANCE_LOW（状态栏无图标、被折叠进静默区）：仅一次性迁移为 DEFAULT
            if (existing != null && existing.importance >= NotificationManagerCompat.IMPORTANCE_DEFAULT) {
                channelReady = true
                return
            }
            if (existing != null) nm.deleteNotificationChannel(CHANNEL_ID)
            val ch = NotificationChannelCompat.Builder(
                CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT
            ).setName(context.getString(R.string.channel_live)).build()
            nm.createNotificationChannel(ch)
            channelReady = true
        }

        /** 测试按钮：12 倍速完整流程演示 —— 虚拟 15 分钟候课（实际 75 秒）+ 虚拟 45 分钟上课（实际 3.75 分钟）。 */
        fun showTest(context: Context) {
            val now = System.currentTimeMillis()
            val scale = TEST_SCALE
            val startMs = now + 15 * 60_000L
            val endMs = startMs + 45 * 60_000L
            val name = "演示课程"
            val room = "G203"
            post(context, name, room, startMs, endMs, anchorMs = now, scale = scale, waitStartMs = now)
            scheduleTick(context, name, room, startMs, endMs, now + tickIntervalMs(scale), anchorMs = now, scale = scale, waitStartMs = now)
        }

        /** 每次进度跳步对应的虚拟时长（15 秒），除以倍率得真实 tick 间隔。 */
        private const val PROGRESS_STEP_VIRTUAL_MS = 15_000L

        private fun tickIntervalMs(scale: Int) = (PROGRESS_STEP_VIRTUAL_MS / scale).coerceAtLeast(1_000L)

        fun cancel(context: Context) {
            NotificationManagerCompat.from(context).cancel(NOTIF_ID)
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(tickPending(context, null, null, 0L, 0L, 0L, 1, 0L, false))
        }

        /**
         * 为一节课安排实时通知：开课前 [leadMinutes] 分钟出现候课倒计时，
         * 开课转为进度条；已开课则立即显示。首 tick 由精确闹钟驱动。
         */
        fun scheduleForClass(
            context: Context,
            name: String,
            room: String,
            start: LocalDateTime,
            end: LocalDateTime,
            now: LocalDateTime,
            leadMinutes: Int = 0,
        ) {
            if (!end.isAfter(now)) return
            val startMs = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endMs = end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (start.isAfter(now)) {
                val firstTick = maxOf(startMs - leadMinutes * 60_000L, System.currentTimeMillis())
                scheduleTick(context, name, room, startMs, endMs, firstTick, anchorMs = startMs, scale = 1, waitStartMs = firstTick)
            } else {
                val t = System.currentTimeMillis()
                post(context, name, room, startMs, endMs, anchorMs = startMs, scale = 1, waitStartMs = t)
                scheduleTick(context, name, room, startMs, endMs, t + tickIntervalMs(1), anchorMs = startMs, scale = 1, waitStartMs = t)
            }
        }

        /** 每分钟（虚拟时间）tick：刷新进度并续排下一次；到虚拟下课时刻则清除。 */
        fun onTick(
            context: Context,
            name: String,
            room: String,
            startMs: Long,
            endMs: Long,
            anchorMs: Long,
            scale: Int,
            waitStartMs: Long,
        ) {
            val now = anchorMs + (System.currentTimeMillis() - anchorMs) * scale
            if (now >= endMs) {
                cancel(context)
                return
            }
            post(context, name, room, startMs, endMs, anchorMs, scale, waitStartMs)
            scheduleTick(context, name, room, startMs, endMs, System.currentTimeMillis() + tickIntervalMs(scale), anchorMs, scale, waitStartMs)
        }

        private fun post(
            context: Context,
            name: String,
            room: String,
            startMs: Long,
            endMs: Long,
            anchorMs: Long,
            scale: Int,
            waitStartMs: Long,
        ) {
            ensureChannel(context)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat_CheckSelfPermission(context) != PackageManager.PERMISSION_GRANTED
            ) return

            // 虚拟时钟：anchor 时刻对应虚拟 anchor，之后按 scale 倍速流动（scale=1 即真实时间）
            val now = anchorMs + (System.currentTimeMillis() - anchorMs) * scale
            val total = (endMs - startMs).coerceAtLeast(1L)
            val inClass = now >= startMs
            val elapsed = if (inClass) (now - startMs).coerceIn(0L, total) else 0L
            val pct = (elapsed * 100 / total).toInt()

            val style = NotificationCompat.ProgressStyle()
                .setProgressTrackerIcon(IconCompat.createWithResource(context, R.drawable.ic_stat_class))
            val segs = mutableListOf<NotificationCompat.ProgressStyle.Segment>()
            val points = mutableListOf<NotificationCompat.ProgressStyle.Point>()
            when {
                !inClass -> {
                    // 候课条：等待进度（橙色），从卡片出现填到开课，开课后切换为蓝色课程进度
                    if (waitStartMs > 0L && waitStartMs < startMs) {
                        val waitTotal = startMs - waitStartMs
                        val waitElapsed = (now - waitStartMs).coerceIn(0L, waitTotal)
                        style.setProgress(waitElapsed.toInt())
                        segs += NotificationCompat.ProgressStyle.Segment(waitElapsed.toInt()).setColor(ORANGE)
                        segs += NotificationCompat.ProgressStyle.Segment((waitTotal - waitElapsed).toInt().coerceAtLeast(1))
                    } else {
                        style.setProgress(0)
                        segs += NotificationCompat.ProgressStyle.Segment(total.toInt())
                    }
                }
                total > MILESTONE_MIN_TOTAL_MS -> {
                    style.setProgress(elapsed.toInt())
                    val milestone = total - LAST_STAGE_MS
                    if (elapsed < milestone) {
                        if (elapsed > 0L) {
                            segs += NotificationCompat.ProgressStyle.Segment(elapsed.toInt()).setColor(ACCENT)
                        }
                        segs += NotificationCompat.ProgressStyle.Segment((milestone - elapsed).toInt())
                        segs += NotificationCompat.ProgressStyle.Segment(LAST_STAGE_MS.toInt()).setColor(GREEN)
                        points += NotificationCompat.ProgressStyle.Point(milestone.toInt()).setColor(ORANGE)
                    } else {
                        segs += NotificationCompat.ProgressStyle.Segment(elapsed.toInt()).setColor(ACCENT)
                        segs += NotificationCompat.ProgressStyle.Segment((total - elapsed).toInt().coerceAtLeast(1)).setColor(GREEN)
                    }
                }
                else -> {
                    style.setProgress(elapsed.toInt())
                    if (elapsed > 0L) {
                        segs += NotificationCompat.ProgressStyle.Segment(elapsed.toInt()).setColor(ACCENT)
                    }
                    segs += NotificationCompat.ProgressStyle.Segment((total - elapsed).toInt().coerceAtLeast(1))
                }
            }
            style.setProgressSegments(segs)
            if (points.isNotEmpty()) style.setProgressPoints(points)

            val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            val endText = Instant.ofEpochMilli(endMs).atZone(ZoneId.systemDefault()).toLocalTime().format(fmt)
            val startText = Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault()).toLocalTime().format(fmt)
            val remainMin = ((endMs - now) / 60_000L).coerceAtLeast(0L)
            val untilStartMin = ((startMs - now).coerceAtLeast(0L) + 59_999L) / 60_000L
            val text = if (inClass) {
                buildString {
                    append("至 $endText")
                    if (room.isNotBlank()) append(" · $room")
                    append(" · 已进行 $pct%")
                }
            } else {
                buildString {
                    append("$startText 开课")
                    if (room.isNotBlank()) append(" · $room")
                    append(" · ${untilStartMin}分钟后开始")
                }
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_class)
                .setLargeIcon(largeIcon(name))
                .setColor(ACCENT)
                .setContentTitle(if (inClass) "上课中 · $name" else "即将上课 · $name")
                .setContentText(text)
                .setStyle(style)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setRequestPromotedOngoing(true)
            if (scale == 1) {
                // 系统驱动的平滑倒计时：状态栏 chip 与通知头部每秒自动跳动，不依赖 re-post
                builder.setWhen(if (inClass) endMs else startMs)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
            } else {
                // 12 倍速测试：虚拟时钟与系统时钟不一致，只能靠 re-post 文本
                builder.setShortCriticalText(if (inClass) "剩${remainMin}分" else "${untilStartMin}分后")
            }
            val notif = builder
                .addAction(NotificationCompat.Action(R.drawable.ic_stat_class, "打开课表", mainPendingIntent(context)))
                .build()
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
        }

        private fun scheduleTick(
            context: Context,
            name: String,
            room: String,
            startMs: Long,
            endMs: Long,
            atMillis: Long,
            anchorMs: Long,
            scale: Int,
            waitStartMs: Long,
        ) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, tickPending(context, name, room, startMs, endMs, anchorMs, scale, waitStartMs, true))
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, tickPending(context, name, room, startMs, endMs, anchorMs, scale, waitStartMs, true))
            }
        }

        private fun tickPending(
            context: Context,
            name: String?,
            room: String?,
            startMs: Long,
            endMs: Long,
            anchorMs: Long,
            scale: Int,
            waitStartMs: Long,
            create: Boolean,
        ): PendingIntent {
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = ACTION_TICK
                name?.let { putExtra(EXTRA_NAME, it) }
                room?.let { putExtra(EXTRA_ROOM, it) }
                putExtra(EXTRA_START, startMs)
                putExtra(EXTRA_END, endMs)
                putExtra(EXTRA_ANCHOR, anchorMs)
                putExtra(EXTRA_SCALE, scale)
                putExtra(EXTRA_WAIT_START, waitStartMs)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, TICK_RC, intent, flags)
        }

        private fun mainPendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, ACTION_RC,
                Intent(context, me.huanlin.gbuca.ui.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /** 课程首字彩色圆标，颜色按课程名稳定取色。 */
        private fun largeIcon(name: String): Bitmap {
            val px = 192
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ICON_COLORS[(name.hashCode().let { if (it < 0) -it else it }) % ICON_COLORS.size]
            }
            canvas.drawCircle(px / 2f, px / 2f, px / 2f, fill)
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = px * 0.44f
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }
            val fm = tp.fontMetrics
            canvas.drawText(
                name.firstOrNull()?.toString() ?: "课",
                px / 2f,
                px / 2f - (fm.ascent + fm.descent) / 2f,
                tp,
            )
            return bmp
        }

        private fun ContextCompat_CheckSelfPermission(context: Context): Int =
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            )
    }
}
