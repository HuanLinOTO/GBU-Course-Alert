package me.huanlin.gbuca.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.huanlin.gbuca.R
import me.huanlin.gbuca.domain.logic.ScheduleLogic
import me.huanlin.gbuca.domain.model.Meeting
import me.huanlin.gbuca.domain.time.TimeGrid
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 课程主题色（按 rwh 稳定取色）。 */
private val palette = listOf(
    Color(0xFF1B6EF3), Color(0xFF1E8E3E), Color(0xFFE8710A), Color(0xFF9C27B0),
    Color(0xFF00897B), Color(0xFFD81B60), Color(0xFF5E35B1), Color(0xFF00838F),
    Color(0xFFC0CA33).copy(alpha = 0.9f), Color(0xFFF4511E),
)

fun courseColor(rwh: String): Color = palette[(rwh.hashCode().let { if (it < 0) -it else it }) % palette.size]

private val timeColWidth = 40.dp
private val periodRowH = 52.dp
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekScreen(onOpenCourse: (String) -> Unit, vm: AppViewModel) {
    val termData by vm.termData.collectAsState()
    var week by rememberSaveable { mutableIntStateOf(currentWeek(vm.semesterStartMonday)) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.week_title, week)) },
            actions = {
                androidx.compose.material3.TextButton(onClick = { if (week > 1) week-- }) { Text(stringResource(R.string.week_prev)) }
                val cw = currentWeek(vm.semesterStartMonday)
                if (cw != null) {
                    androidx.compose.material3.TextButton(onClick = { week = cw }) { Text(stringResource(R.string.week_this)) }
                }
                androidx.compose.material3.TextButton(onClick = { if (week < 30) week++ }) { Text(stringResource(R.string.week_next)) }
            },
        )
        WeekGrid(
            meetings = termData.meetings,
            week = week,
            semesterStartMonday = vm.semesterStartMonday,
            courseName = { termData.courseByRwh[it]?.name ?: "?" },
            onOpenCourse = onOpenCourse,
        )
    }
}

fun currentWeek(startMonday: LocalDate): Int =
    ScheduleLogic.weekOf(LocalDate.now(), startMonday) ?: 1

@Composable
private fun WeekGrid(
    meetings: List<Meeting>,
    week: Int,
    semesterStartMonday: LocalDate,
    courseName: (String) -> String,
    onOpenCourse: (String) -> Unit,
) {
    val periods = TimeGrid.periods
    val n = periods.size
    val dayMeetings = (1..7).associateWith { wd ->
        meetings.filter { it.weekday == wd && week in it.weeks && it.startPeriod in 1..n }
            .sortedWith(compareBy({ it.startPeriod }, { it.endPeriod }))
    }
    // 周末没课时不显示对应列
    val visibleDays = (1..7).filter { it <= 5 || dayMeetings[it].orEmpty().isNotEmpty() }
    val today = LocalDate.now()
    val isCurrentWeek = ScheduleLogic.weekOf(today, semesterStartMonday) == week

    Column(Modifier.fillMaxSize()) {
        // 表头：星期（与下方日列等宽对齐）
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(timeColWidth))
            visibleDays.forEach { wd ->
                val isToday = isCurrentWeek && wd == today.dayOfWeek.value
                Text(
                    weekdayName(wd),
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        // 网格主体（纵向滚动；顶部内边距给首条时间刻度留位）
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(periodRowH * n)) {
                // 小节分隔线
                periods.forEach { p ->
                    Box(
                        Modifier
                            .offset(y = periodRowH * (p.index - 1))
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    )
                }
                Row(Modifier.fillMaxWidth().height(periodRowH * n)) {
                    // 左轴：节次号淡显在行内（时间改放到分隔线上，见下方刻度层）
                    Column(Modifier.width(timeColWidth).fillMaxHeight()) {
                        periods.forEach { p ->
                            Box(
                                Modifier.fillMaxWidth().height(periodRowH),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "${p.index}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                )
                            }
                        }
                    }
                    // 日列，平分剩余宽度（周末无课时隐藏）
                    visibleDays.forEach { wd ->
                        DayColumn(
                            meetings = dayMeetings[wd].orEmpty(),
                            isToday = isCurrentWeek && wd == today.dayOfWeek.value,
                            courseName = courseName,
                            onOpenCourse = onOpenCourse,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
                // 时间刻度层：每条分隔线上居中放该节开始时间（surface 底遮住线段，形似刻度）
                val tickH = 16.dp
                periods.forEach { p ->
                    Box(
                        Modifier
                            .offset(y = periodRowH * (p.index - 1) - tickH / 2)
                            .width(timeColWidth)
                            .height(tickH)
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            p.start.format(timeFmt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // 底部：结束线 + 末节结束时间刻度
            Box(Modifier.fillMaxWidth().height(20.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                )
                Box(
                    Modifier
                        .offset(y = -7.dp)
                        .width(timeColWidth)
                        .height(14.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        periods.last().end.format(timeFmt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 重叠的课次分到不同竖道，互不重叠的共用同一道。按真实时间判断重叠。 */
private fun assignLanes(list: List<Meeting>): List<List<Meeting>> {
    val lanes = mutableListOf<MutableList<Meeting>>()
    for (m in list.sortedWith(compareBy({ it.startTime }, { it.endTime }))) {
        val lane = lanes.firstOrNull { l -> l.all { it.endTime <= m.startTime } }
        if (lane != null) lane.add(m) else lanes.add(mutableListOf(m))
    }
    return lanes
}

@Composable
private fun DayColumn(
    meetings: List<Meeting>,
    isToday: Boolean,
    courseName: (String) -> String,
    onOpenCourse: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        if (isToday) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)),
            )
        }
        val lanes = assignLanes(meetings)
        val laneW = maxWidth / maxOf(1, lanes.size)
        lanes.forEachIndexed { li, lane ->
            lane.forEach { m ->
                MeetingChip(
                    meeting = m,
                    name = courseName(m.rwh),
                    onClick = { onOpenCourse(m.rwh) },
                    modifier = Modifier
                        .offset(x = laneW * li, y = meetingTopY(m) + 1.dp)
                        .width(laneW - 2.dp)
                        .height(meetingBottomY(m) - meetingTopY(m) - 2.dp),
                )
            }
        }
    }
}

@Composable
private fun MeetingChip(
    meeting: Meeting,
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = courseColor(meeting.rwh)
    val isLab = meeting.role == "课内实验"
    // 实验课实底色与普通半透明课块区分；底/字色随深浅色切换，深色下用暗紫底 + 浅紫字
    val labDark = isSystemInDarkTheme()
    val bg = if (isLab) {
        if (labDark) Color(0xFF3A2C4F) else Color(0xFFF3E5F5)
    } else color.copy(alpha = 0.13f)
    val fg = if (isLab) {
        if (labDark) Color(0xFFD6BDF2) else Color(0xFF7B1FA2)
    } else color
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(6.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 3.dp, vertical = 2.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = fg,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    meeting.room?.let { append(it); append(' ') }
                    append(meeting.startTime.format(timeFmt))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 课块顶/底 y 坐标：优先按真实时间在节次网格中插值（压缩课块可越过节次线）；网格缺失时回退节次索引。 */
private fun meetingTopY(m: Meeting): Dp {
    val loc = TimeGrid.locate(m.startTime)
    return if (loc != null) periodRowH * ((loc.first - 1) + loc.second)
    else periodRowH * (m.startPeriod - 1)
}

private fun meetingBottomY(m: Meeting): Dp {
    val loc = TimeGrid.locate(m.endTime)
    return if (loc != null) periodRowH * ((loc.first - 1) + loc.second)
    else periodRowH * m.endPeriod
}
