package me.huanlin.gbuca.domain.time

import java.time.LocalTime

/**
 * 作息时间网格：小节 35 分钟，同一大节内间隔 5 分钟，大节之间间隔 15 分钟。
 * 首节 8:00。可被 `queryYxkc` 响应中的 `kbjclist` 覆盖。
 * 注意：3 节连排课程的时间与大节网格不严格对齐，显式时间字符串永远是最高优先级，
 * 此网格仅用于课表视图对齐展示与兜底。
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

    @Volatile
    var periods: List<Period> = DEFAULT
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
