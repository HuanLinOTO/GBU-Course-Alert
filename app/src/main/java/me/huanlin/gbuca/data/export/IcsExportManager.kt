package me.huanlin.gbuca.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import me.huanlin.gbuca.data.local.SettingsStore
import me.huanlin.gbuca.data.repo.CourseRepository
import me.huanlin.gbuca.domain.export.CourseIcsExporter
import me.huanlin.gbuca.domain.export.IcsCalendar
import me.huanlin.gbuca.domain.model.TermData
import java.io.File
import java.io.IOException

/**
 * ICS 导出的 Android 适配层：读库组装内容、写 SAF Uri、构造 FileProvider 分享 Intent。
 */
class IcsExportManager(
    private val context: Context,
    private val repo: CourseRepository,
    private val settings: SettingsStore,
) {

    /** 当前学期课表 → ICS 文本；无课返回 null。 */
    suspend fun build(xnxq: String): String? {
        val meetings = repo.meetingsByXnxq(xnxq)
        if (meetings.isEmpty()) return null
        val courses = meetings.mapNotNull { repo.courseByRwh(it.rwh) }.distinctBy { it.rwh }
        val events = CourseIcsExporter.export(
            xnxq = xnxq,
            data = TermData(courses = courses, meetings = meetings),
            semesterStartMonday = settings.semesterStartMonday(xnxq),
            alarmMinutes = if (settings.remindersEnabled) settings.reminderMinutes else null,
        )
        if (events.isEmpty()) return null
        return IcsCalendar.build(events, CourseIcsExporter.calendarName(xnxq))
    }

    fun suggestedFileName(xnxq: String): String = CourseIcsExporter.fileName(xnxq)

    /** 写入 SAF 返回的 Uri；失败抛 IOException / SecurityException，由调用方提示。 */
    fun writeToUri(uri: Uri, content: String) {
        val stream = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("openOutputStream returned null")
        stream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }

    /** 写 cacheDir 并经 FileProvider 生成分享 Intent（调用方 createChooser + startActivity）。 */
    fun shareIntent(content: String, fileName: String): Intent {
        val dir = File(context.cacheDir, "ics").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/calendar"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
