package me.huanlin.gbuca.domain.export

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * RFC 5545 iCalendar 序列化器（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 只实现导出所需子集：VCALENDAR / VTIMEZONE / VEVENT / VALARM。
 * 行尾 CRLF，单行 ≤ 75 octet（按 UTF-8 码点折行，不切断中文），TEXT 值按 RFC 5545 §3.3.11 转义。
 */
object IcsCalendar {

    /** 固定时区：课次时间均以中国标准时间表达（无 DST）。 */
    const val TIMEZONE = "Asia/Shanghai"

    private const val MAX_OCTETS = 75

    /** 一条日历事件。`rrule` 与 `rdates` 都为空 = 单次事件。 */
    data class Event(
        val uid: String,
        val start: LocalDateTime,
        val end: LocalDateTime,
        val summary: String,
        val location: String? = null,
        val description: String? = null,
        /** 不含 `RRULE:` 前缀，如 `FREQ=WEEKLY;INTERVAL=1;COUNT=16`。 */
        val rrule: String? = null,
        /** 除首场外的额外发生时间（RDATE）；为空则不输出 RDATE。 */
        val rdates: List<LocalDateTime> = emptyList(),
        /** 提前提醒分钟数；null 或 ≤ 0 时不输出 VALARM。 */
        val alarmMinutes: Int? = null,
    )

    private val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    private val UTC: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun build(
        events: List<Event>,
        calendarName: String,
        stamp: Instant = Instant.now(),
    ): String {
        val sb = StringBuilder()
        line(sb, "BEGIN:VCALENDAR")
        line(sb, "VERSION:2.0")
        line(sb, "PRODID:-//GBU Course Alert//ICS Export//CN")
        line(sb, "CALSCALE:GREGORIAN")
        line(sb, "METHOD:PUBLISH")
        line(sb, "X-WR-CALNAME:${escape(calendarName)}")
        line(sb, "X-WR-TIMEZONE:$TIMEZONE")
        timezone(sb)
        val dtstamp = UTC.format(stamp)
        events.forEach { event(sb, it, dtstamp) }
        line(sb, "END:VCALENDAR")
        return sb.toString()
    }

    private fun timezone(sb: StringBuilder) {
        line(sb, "BEGIN:VTIMEZONE")
        line(sb, "TZID:$TIMEZONE")
        line(sb, "BEGIN:STANDARD")
        line(sb, "DTSTART:19700101T000000")
        line(sb, "TZOFFSETFROM:+0800")
        line(sb, "TZOFFSETTO:+0800")
        line(sb, "TZNAME:CST")
        line(sb, "END:STANDARD")
        line(sb, "END:VTIMEZONE")
    }

    private fun event(sb: StringBuilder, e: Event, dtstamp: String) {
        line(sb, "BEGIN:VEVENT")
        line(sb, "UID:${escape(e.uid)}")
        line(sb, "DTSTAMP:$dtstamp")
        line(sb, "DTSTART;TZID=$TIMEZONE:${LOCAL.format(e.start)}")
        line(sb, "DTEND;TZID=$TIMEZONE:${LOCAL.format(e.end)}")
        line(sb, "SUMMARY:${escape(e.summary)}")
        e.location?.takeIf { it.isNotBlank() }?.let { line(sb, "LOCATION:${escape(it)}") }
        e.description?.takeIf { it.isNotBlank() }?.let { line(sb, "DESCRIPTION:${escape(it)}") }
        e.rrule?.takeIf { it.isNotBlank() }?.let { line(sb, "RRULE:$it") }
        if (e.rdates.isNotEmpty()) {
            line(sb, "RDATE;TZID=$TIMEZONE:${e.rdates.joinToString(",") { LOCAL.format(it) }}")
        }
        val alarm = e.alarmMinutes
        if (alarm != null && alarm > 0) {
            line(sb, "BEGIN:VALARM")
            line(sb, "ACTION:DISPLAY")
            line(sb, "DESCRIPTION:${escape(e.summary)}")
            line(sb, "TRIGGER:-PT${alarm}M")
            line(sb, "END:VALARM")
        }
        line(sb, "END:VEVENT")
    }

    /** RFC 5545 §3.3.11 TEXT 转义。 */
    private fun escape(value: String): String = buildString {
        for (ch in value) when (ch) {
            '\\' -> append("\\\\")
            ';' -> append("\\;")
            ',' -> append("\\,")
            '\n' -> append("\\n")
            '\r' -> Unit
            else -> append(ch)
        }
    }

    /** 写一行并折行：首行 ≤75 octet，续行以单个空格开头且同样 ≤75 octet。 */
    private fun line(sb: StringBuilder, raw: String) {
        if (raw.toByteArray(Charsets.UTF_8).size <= MAX_OCTETS) {
            sb.append(raw).append("\r\n")
            return
        }
        val buf = StringBuilder()
        var octets = 0
        var continuation = false
        var i = 0
        while (i < raw.length) {
            val next = raw.offsetByCodePoints(i, 1)
            val chunk = raw.substring(i, next)
            val len = chunk.toByteArray(Charsets.UTF_8).size
            if (octets + len > if (continuation) MAX_OCTETS - 1 else MAX_OCTETS) {
                sb.append(buf).append("\r\n ")
                buf.setLength(0)
                octets = 0
                continuation = true
            }
            buf.append(chunk)
            octets += len
            i = next
        }
        sb.append(buf).append("\r\n")
    }
}
