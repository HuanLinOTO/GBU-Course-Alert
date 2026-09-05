package me.huanlin.gbuca.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.huanlin.gbuca.data.GbuException
import me.huanlin.gbuca.data.local.CredentialStore
import me.huanlin.gbuca.data.local.SettingsStore
import me.huanlin.gbuca.data.local.room.AppDatabase
import me.huanlin.gbuca.data.local.room.CourseEntity
import me.huanlin.gbuca.data.local.room.MeetingEntity
import me.huanlin.gbuca.data.remote.GbuClient
import me.huanlin.gbuca.data.remote.YxkcItem
import me.huanlin.gbuca.domain.model.Course
import me.huanlin.gbuca.domain.model.Meeting
import me.huanlin.gbuca.domain.model.TermData
import me.huanlin.gbuca.domain.parser.ScheduleParser
import me.huanlin.gbuca.domain.time.TimeGrid
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class CourseRepository(
    private val client: GbuClient,
    private val db: AppDatabase,
    private val creds: CredentialStore,
    private val settings: SettingsStore,
) {

    private val json = Json { ignoreUnknownKeys = true }

    fun observeTermData(xnxq: String): Flow<TermData> =
        combine(
            db.courseDao().observeByXnxq(xnxq),
            db.meetingDao().observeByXnxq(xnxq),
        ) { courses, meetings ->
            TermData(
                courses = courses.map { it.toDomain() },
                meetings = meetings.map { it.toDomain() },
            )
        }

    suspend fun courseByRwh(rwh: String): Course? = db.courseDao().byRwh(rwh)?.toDomain()

    suspend fun meetingsByXnxq(xnxq: String): List<Meeting> =
        db.meetingDao().byXnxq(xnxq).map { it.toDomain() }

    suspend fun storedXnxqList(): List<String> = db.courseDao().allXnxq()

    /** 同步：无会话则先登录；拉取中过期则重登一次并重试。 */
    suspend fun sync(xnxq: String): SyncResult = withContext(Dispatchers.IO) {
        if (!client.hasSession) loginWithStoredCredentials()
        val result = fetchAndStore(xnxq)
        // 非致命：从教务系统校准「第 1 周周一」，失败则保留现有值
        runCatching { calibrateSemesterStart(xnxq) }
        result
    }

    /**
     * 用教务系统的日期→周次接口校准第 1 周周一。
     * zc 语义：未开学 = 0，第 1 周 = 1。以按校历推算的默认锚点为起点，最多探测 3 次：
     * zc==1 命中；zc==0 未开学则后移一周；zc>=2 则直接回推 (zc-1) 周。
     * [force] 时清除用户手动设置并强制校准（设置页按钮）；自动校准遇手动设置则跳过。
     * 返回校准出的周一日期；无法校准返回 null。
     */
    suspend fun calibrateSemesterStart(xnxq: String, force: Boolean = false): LocalDate? {
        if (force) settings.clearSemesterStartCustomized(xnxq)
        else if (settings.isSemesterStartCustomized(xnxq)) return null
        val m = Regex("""^(\d{4}-\d{4})([12])$""").find(xnxq) ?: return null
        val (xn, xq) = m.destructured
        var cand = SettingsStore.defaultStartMonday(xnxq)
        repeat(3) {
            val r = runCatching { client.queryXnxqZc(cand) }.getOrNull() ?: return null
            val (rxn, rxq, zc) = r
            if ("$rxn$rxq" != xnxq) return null   // 日期落在别的学期，无法校准
            when {
                zc == 1 -> { settings.setServerSemesterStartMonday(xnxq, cand); return cand }
                zc == 0 -> cand = cand.plusWeeks(1)
                else -> {
                    val d = cand.minusWeeks((zc - 1).toLong())
                    settings.setServerSemesterStartMonday(xnxq, d)
                    return d
                }
            }
        }
        return null
    }

    private suspend fun loginWithStoredCredentials() {
        val u = creds.username ?: throw GbuException.BadCredentials("未保存登录凭据")
        val p = creds.password ?: throw GbuException.BadCredentials("未保存登录凭据")
        client.login(u, p)
    }

    private suspend fun fetchAndStore(xnxq: String): SyncResult {
        val m = Regex("""^(\d{4}-\d{4})([12])$""").find(xnxq)
            ?: throw GbuException.ApiError("学期格式异常: $xnxq")
        val (xn, xq) = m.destructured

        val all = mutableListOf<YxkcItem>()
        var unparsedAll = mutableListOf<String>()
        var pageNum = 1
        while (true) {
            val resp = try {
                client.queryYxkc(xn, xq, xnxq, pageNum)
            } catch (e: GbuException.SessionExpired) {
                loginWithStoredCredentials()
                client.queryYxkc(xn, xq, xnxq, pageNum)
            }
            if (pageNum == 1) {
                TimeGrid.update(resp.kbjclist.map { TimeGrid.KbjcItem(it.xj, it.kssj, it.jssj, it.dj, it.sxw) })
            }
            all += resp.yxkcList
            val total = resp.page?.total ?: resp.yxkcList.size.toLong()
            if (resp.yxkcList.isEmpty() || all.size >= total || pageNum >= 30) break
            pageNum++
        }

        val now = System.currentTimeMillis()
        val courseEntities = all.map { it.toEntity(xnxq, now) }
        val meetingEntities = mutableListOf<MeetingEntity>()
        for (item in all) {
            val parsed = ScheduleParser.parse(item.kcxx, item.rwh)
            unparsedAll += parsed.unparsedLines
            meetingEntities += parsed.meetings.map { it.toEntity(xnxq) }
        }

        db.courseDao().deleteByXnxq(xnxq)
        db.meetingDao().deleteByXnxq(xnxq)
        db.courseDao().insertAll(courseEntities)
        db.meetingDao().insertAll(meetingEntities)

        settings.lastSyncAt = now
        return SyncResult(all.size, meetingEntities.size, unparsedAll)
    }

    suspend fun logout() {
        settings.scheduledAlarmKeys = emptySet()
        creds.clear()
    }

    data class SyncResult(val courseCount: Int, val meetingCount: Int, val unparsed: List<String>)

    // ---- 实体映射 ----

    private fun CourseEntity.toDomain() = Course(
        rwh = rwh, xnxq = xnxq, name = name, nameEn = nameEn, code = code, seq = seq,
        className = className, credits = credits, hours = hours, nature = nature, category = category,
        college = college, enrollTime = enrollTime, capacity = capacity, enrolled = enrolled,
        rawKcxx = rawKcxx,
        unparsed = runCatching { json.decodeFromString<List<String>>(unparsed) }.getOrDefault(emptyList()),
    )

    private fun MeetingEntity.toDomain() = Meeting(
        rwh = rwh,
        role = role,
        teachers = teachers.split(' ').filter { it.isNotBlank() },
        weeks = weeks.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet(),
        weekday = weekday,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        startTime = LocalTime.parse(startTime),
        endTime = LocalTime.parse(endTime),
        room = room,
        rawText = rawText,
    )

    private fun YxkcItem.toEntity(xnxq: String, syncedAt: Long): CourseEntity {
        val parsed = ScheduleParser.parse(kcxx, rwh)
        return CourseEntity(
            rwh = rwh, xnxq = xnxq, name = kcmc, nameEn = kcmcEn, code = kcdm, seq = kxh,
            className = rwmc, credits = xf, hours = xs, nature = kcxzmc, category = kclbmc,
            college = kkyxmc, enrollTime = xksj, capacity = dnrl, enrolled = dnyxrs,
            rawKcxx = kcxx,
            unparsed = json.encodeToString(parsed.unparsedLines),
            syncedAt = syncedAt,
        )
    }

    private fun Meeting.toEntity(xnxq: String) = MeetingEntity(
        rwh = rwh, xnxq = xnxq, role = role,
        teachers = teachers.joinToString(" "),
        weeks = weeks.sorted().joinToString(","),
        weekday = weekday, startPeriod = startPeriod, endPeriod = endPeriod,
        startTime = startTime.format(DateTimeFormatter.ofPattern("HH:mm")),
        endTime = endTime.format(DateTimeFormatter.ofPattern("HH:mm")),
        room = room, rawText = rawText,
    )
}
