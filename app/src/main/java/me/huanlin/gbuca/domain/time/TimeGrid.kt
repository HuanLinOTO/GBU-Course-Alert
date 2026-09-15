package me.huanlin.gbuca.domain.time

import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 作息时间网格：小节 35 分钟，同一大节内间隔 5 分钟，大节之间间隔 15 分钟。
 * 首节 8:00。可被 `queryYxkc` 响应中的 `kbjclist` 覆盖（仅周一/三/五网格）。
 *
 * 学校实行单双日两套作息（教务"上课节次时间查询"报表）：
 * - 周一/三/五：9 段 × 2 节（即 [DEFAULT]，kbjclist 返回的也是这套）；
 * - 周二/四：6 段 × 3 节连排（[DEFAULT_TT]，如第4-6节 10:10-12:05），教务接口不返回，内置。
 * kcxx 中的显式时间字符串永远是最高优先级，网格仅用于课表视图对齐展示与兜底。
 */
object TimeGrid {

    data class Period(
        val index: Int,
        val start: LocalTime,
        val end: LocalTime,
        val bigBlock: Int,
        val sxw: Int, // 1 上午 3 下午 5 晚上
    )

    data class BigBlock(
        val index: Int,
        val firstPeriod: Int,
        val lastPeriod: Int,
        val start: LocalTime,
        val end: LocalTime,
        val sxw: Int,
    ) {
        val label: String get() = "第$firstPeriod-${lastPeriod}节"
    }

    private fun build(spec: List<Triple<Int, String, String>>, bigOf: (Int) -> Int, sxwOf: (Int) -> Int): List<Period> =
        spec.map { (idx, s, e) -> Period(idx, LocalTime.parse(s), LocalTime.parse(e), bigOf(idx), sxwOf(idx)) }

    private fun bigBlockOfPeriod(p: Int): Int = (p + 1) / 2

    private fun sxwOfPeriod(p: Int): Int = when (p) {
        in 1..6 -> 1
        in 7..14 -> 3
        else -> 5
    }

    val DEFAULT: List<Period> = build(
        listOf(
            Triple(1, "08:00", "08:35"), Triple(2, "08:40", "09:15"),
            Triple(3, "09:30", "10:05"), Triple(4, "10:10", "10:45"),
            Triple(5, "11:00", "11:35"), Triple(6, "11:40", "12:15"),
            Triple(7, "12:30", "13:05"), Triple(8, "13:10", "13:45"),
            Triple(9, "14:00", "14:35"), Triple(10, "14:40", "15:15"),
            Triple(11, "15:30", "16:05"), Triple(12, "16:10", "16:45"),
            Triple(13, "17:00", "17:35"), Triple(14, "17:40", "18:15"),
            Triple(15, "18:30", "19:05"), Triple(16, "19:10", "19:45"),
            Triple(17, "20:00", "20:35"), Triple(18, "20:40", "21:15"),
        ),
        bigOf = ::bigBlockOfPeriod,
        sxwOf = ::sxwOfPeriod,
    )

    private fun bigBlockOfPeriodTT(p: Int): Int = (p + 2) / 3

    private fun sxwOfPeriodTT(p: Int): Int = when (p) {
        in 1..6 -> 1   // 段1-2 上午
        in 7..15 -> 3  // 段3 中午，段4-5 下午
        else -> 5      // 段6 晚上
    }

    /** 周二/四作息：6 段 × 3 节连排（段 115 分钟，段间 15 分钟），与周一/三/五网格节号相同但时间不同。 */
    val DEFAULT_TT: List<Period> = build(
        listOf(
            Triple(1, "08:00", "08:35"), Triple(2, "08:40", "09:15"), Triple(3, "09:20", "09:55"),
            Triple(4, "10:10", "10:45"), Triple(5, "10:50", "11:25"), Triple(6, "11:30", "12:05"),
            Triple(7, "12:20", "12:55"), Triple(8, "13:00", "13:35"), Triple(9, "13:40", "14:15"),
            Triple(10, "14:30", "15:05"), Triple(11, "15:10", "15:45"), Triple(12, "15:50", "16:25"),
            Triple(13, "16:40", "17:15"), Triple(14, "17:20", "17:55"), Triple(15, "18:00", "18:35"),
            Triple(16, "18:50", "19:25"), Triple(17, "19:30", "20:05"), Triple(18, "20:10", "20:45"),
        ),
        bigOf = ::bigBlockOfPeriodTT,
        sxwOf = ::sxwOfPeriodTT,
    )

    @Volatile
    var periods: List<Period> = DEFAULT
        private set

    /** 周二/四网格。教务 `kbjclist` 不含此表，暂无服务器来源，始终为内置值。 */
    @Volatile
    var ttPeriods: List<Period> = DEFAULT_TT
        private set

    fun bigBlocks(): List<BigBlock> {
        val groups = periods.groupBy { it.bigBlock }
        return groups.keys.sorted().mapNotNull { b ->
            val list = groups[b].orEmpty().sortedBy { it.index }
            val first = list.firstOrNull() ?: return@mapNotNull null
            val last = list.lastOrNull() ?: return@mapNotNull null
            BigBlock(b, first.index, last.index, first.start, last.end, first.sxw)
        }
    }

    fun period(index: Int): Period? = periods.firstOrNull { it.index == index }

    /** 按星期取节次：周二/四用连排网格，其余用默认（周一/三/五）网格。weekday: 1=周一 … 7=周日。 */
    fun period(index: Int, weekday: Int): Period? =
        (if (weekday == 2 || weekday == 4) ttPeriods else periods).firstOrNull { it.index == index }

    /** 时刻 → (节次序号, 行内真实时间比例 0f..1f)。行跨度 = 本节开始→下一节开始（末节为→本节结束），
     *  节末时刻落在行内比例处而非下一节刻度线（如 12:15 → 第6行 35/50≈0.7）；课间同理按真实时间落位。
     *  早于首节归首节 0f；晚于末节结束归末节 1f。 */
    fun locate(time: LocalTime): Pair<Int, Float>? {
        val sorted = periods.sortedBy { it.index }
        val first = sorted.firstOrNull() ?: return null
        if (!time.isAfter(first.start)) return first.index to 0f
        for (i in sorted.indices) {
            val p = sorted[i]
            val spanEnd = sorted.getOrNull(i + 1)?.start ?: p.end
            if (time.isBefore(spanEnd)) {
                val span = ChronoUnit.SECONDS.between(p.start, spanEnd).coerceAtLeast(1)
                val frac = ChronoUnit.SECONDS.between(p.start, time).toFloat() / span
                return p.index to frac.coerceIn(0f, 1f)
            }
        }
        return sorted.last().index to 1f
    }

    fun update(kbjcItems: List<KbjcItem>) {
        if (kbjcItems.isEmpty()) return
        val parsed = kbjcItems.mapNotNull { it.toPeriod() }.sortedBy { it.index }
        if (parsed.isNotEmpty()) periods = parsed
    }

    fun reset() {
        periods = DEFAULT
    }

    /** kbjclist 中时间为 UTC 存储，需 +8h；提取 HH:mm 部分。 */
    data class KbjcItem(
        val xj: Int?,
        val kssj: String?,
        val jssj: String?,
        val dj: Int?,
        val sxw: Int?,
    ) {
        fun toPeriod(): Period? {
            val idx = xj ?: return null
            val start = parseShifted(kssj) ?: return null
            val end = parseShifted(jssj) ?: return null
            val big = dj ?: bigBlockOfPeriod(idx)
            return Period(idx, start, end, big, sxw ?: sxwOfPeriod(idx))
        }

        private fun parseShifted(s: String?): LocalTime? {
            if (s.isNullOrBlank()) return null
            val m = Regex("""(\d{1,2}):(\d{2})""").find(s) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            return LocalTime.of((h + 8) % 24, min)
        }
    }
}
