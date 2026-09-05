package me.huanlin.gbuca.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import me.huanlin.gbuca.GbuCaApp
import java.util.concurrent.TimeUnit

/**
 * 后台维护：每 12h 同步课程数据 + 重排闹钟。
 * 低频访问教务系统（防风控）；同步失败静默保留本地缓存。
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as GbuCaApp
        return try {
            val xnxq = app.settings.selectedXnxq ?: app.client.fallbackXnxq().third
            app.repo.sync(xnxq)
            app.reminderScheduler.reschedule()
            me.huanlin.gbuca.widget.TodayWidgetReceiver.refreshAll(applicationContext)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val NAME = "gbuca_periodic_sync"

        fun enqueue(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.NONE)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
