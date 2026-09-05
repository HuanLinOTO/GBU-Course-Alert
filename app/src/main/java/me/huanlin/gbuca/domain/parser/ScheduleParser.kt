package me.huanlin.gbuca.domain.parser

import me.huanlin.gbuca.domain.model.Meeting
import java.time.LocalTime

/**
 * 解析教务系统 `kcxx`（上课信息）HTML 为课次列表。
 *
 * 输入形如：
 * ```html
 * <p><b>主任务:</b> <a onclick="queryJsxx('226162029')">段金桥</a>
 * <p><b>上课信息:</b>
 *   <p>1-16周,星期三第1-2节 8:00-9:15 B304</p>
 * <p><b>课内实验:</b> <a>李丹丹</a>
 * <p><b>上课信息:</b>
 *   <p>2-16双周,星期五第3-4节 9:30-10:45 无地点</p>
 * ```
 */
object ScheduleParser {

    const val ROLE_MAIN = "主任务"
    const val ROLE_LAB = "课内实验"

    data class Result(
        val meetings: List<Meeting>,
        val unparsedLines: List<String>,
    )

    private val WHITESPACE = Regex("""\s+""")

    /** `1-16周,星期三第1-2节 8:00-9:15 B304`（周次 token 可带 单/双 后缀） */
    private val MEETING_LINE = Regex(
        """^(.+?)周[,，]星期([一二三四五六日天])第(\d+)(?:-(\d+))?节\s*(\d{1,2}:\d{1,2})-(\d{1,2}:\d{1,2})(?:\s+(.+))?$"""
    )

    /** 角色标签行，如 `主任务: 段金桥`。排除含逗号的行以免误吞课次行。 */
    private val LABEL = Regex("""^([^:：，,]{1,12})[:：]\s*(.*)$""")

    private val RANGE = Regex("""^(\d+)(?:-(\d+))?$""")

    private val WEEKDAY = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '日' to 7, '天' to 7,
    )

    fun parse(kcxxHtml: String, rwh: String): Result {
        val meetings = mutableListOf<Meeting>()
        val unparsed = mutableListOf<String>()
        var role = ROLE_MAIN
        var teachers = mutableListOf<String>()

        for (raw in toLines(kcxxHtml)) {
            val line = raw.trim()
            if (line.isEmpty() || line == "无") continue
            val isAnchor = isAnchorLine(line)
            val text = stripAnchorMark(line)

            val m = MEETING_LINE.matchEntire(text)
            if (m != null) {
                val weeks = parseWeeks(m.groupValues[1])
                val weekday = WEEKDAY[m.groupValues[2][0]]
                val startPeriod = m.groupValues[3].toIntOrNull()
                val endPeriod = (if (m.groupValues[4].isEmpty()) m.groupValues[3] else m.groupValues[4]).toIntOrNull()
                val startTime = parseTime(m.groupValues[5])
                val endTime = parseTime(m.groupValues[6])
                val roomRaw = m.groupValues[7].trim()
                val room = if (roomRaw.isEmpty() || roomRaw == "无地点") null else roomRaw

                if (weekday == null || startPeriod == null || endPeriod == null ||
                    startTime == null || endTime == null || weeks.isEmpty()
                ) {
                    unparsed += text
                } else {
                    meetings += Meeting(
                        rwh = rwh,
                        role = role,
                        teachers = teachers.toList(),
                        weeks = weeks,
                        weekday = weekday,
                        startPeriod = startPeriod,
                        endPeriod = endPeriod,
                        startTime = startTime,
                        endTime = endTime,
                        room = room,
                        rawText = text,
                    )
                }
                continue
            }

            val label = LABEL.matchEntire(text)
            if (label != null) {
                val name = label.groupValues[1].trim()
                if (name == "上课信息") continue
                role = when (name) {
                    ROLE_MAIN -> ROLE_MAIN
                    ROLE_LAB -> ROLE_LAB
                    else -> name
                }
                teachers = mutableListOf()
                val rest = label.groupValues[2].trim()
                if (rest.isNotEmpty() && rest != "无") teachers += rest.split(WHITESPACE)
                continue
            }

            if (isAnchor) {
                // <a> 内文本 = 教师名（可多人同段）
                teachers += text.split(WHITESPACE)
            } else {
                // 裸文本：仅当形如中文姓名（2-4 字无标点）时视作教师，否则归入未解析
                if (text.length in 2..4 && text.all { it.code > 0x2E7F }) {
                    teachers += text
                } else {
                    unparsed += text
                }
            }
        }
        return Result(meetings, unparsed)
    }

    /** `1-16` / `2,4` / `2-16双` / `1-15单` → 周集合 */
    fun parseWeeks(token0: String): Set<Int> {
        var parity = 0 // 0: 不限 1: 单周 2: 双周
        var t = token0.trim()
        if (t.endsWith("单")) {
            parity = 1; t = t.dropLast(1)
        } else if (t.endsWith("双")) {
            parity = 2; t = t.dropLast(1)
        }
        val weeks = sortedSetOf<Int>()
        for (seg in t.split(',', '，')) {
            val s = seg.trim()
            if (s.isEmpty()) continue
            val r = RANGE.find(s) ?: return emptySet()
            val a = r.groupValues[1].toIntOrNull() ?: return emptySet()
            val b = r.groupValues[2].toIntOrNull() ?: a
            val lo = minOf(a, b)
            val hi = maxOf(a, b)
            if (lo < 1 || hi > 40 || hi - lo > 39) return emptySet()
            weeks += lo..hi
        }
        val filtered = when (parity) {
            1 -> weeks.filter { it % 2 == 1 }
            2 -> weeks.filter { it % 2 == 0 }
            else -> weeks.toList()
        }
        return filtered.toSet()
    }

    private fun parseTime(s: String): LocalTime? {
        val i = s.indexOf(':')
        if (i <= 0) return null
        val h = s.substring(0, i).toIntOrNull() ?: return null
        val m = s.substring(i + 1).toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return LocalTime.of(h, m)
    }

    private val ANCHOR = Regex("""<a\b[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private const val ANCHOR_MARK = '\u0001'

    /** 真实 kcxx 中 `<p>` 普遍未闭合（HTML 自动闭合），因此 `<p>` 与 `</p>` 均视为断行。 */
    private fun toLines(html: String): List<String> {
        val marked = ANCHOR.replace(html) { m -> "$ANCHOR_MARK${m.groupValues[1]}$ANCHOR_MARK" }
        return marked
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</?p\b[^>]*>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<[^>]*>"""), " ")
            .replace(Regex("&nbsp;?", RegexOption.IGNORE_CASE), " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace('\u00A0', ' ')
            .lines()
    }

    private fun isAnchorLine(line: String): Boolean = ANCHOR_MARK in line

    private fun stripAnchorMark(line: String): String =
        line.replace(ANCHOR_MARK.toString(), "").trim()
}
