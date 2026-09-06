package me.huanlin.gbuca.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import me.huanlin.gbuca.R
import me.huanlin.gbuca.domain.logic.ScheduleLogic
import me.huanlin.gbuca.domain.logic.ScheduleLogic.ClassStatus
import me.huanlin.gbuca.domain.model.Meeting
import me.huanlin.gbuca.domain.parser.ScheduleParser
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onOpenCourse: (String) -> Unit,
    onOpenWebLogin: () -> Unit,
    vm: AppViewModel,
) {
    val termData by vm.termData.collectAsState()
    val ui by vm.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    var dayOffset by rememberSaveable { mutableIntStateOf(0) }
    val today = LocalDate.now()
    val date = today.plusDays(dayOffset.toLong())
    val isToday = dayOffset == 0
    val week = ScheduleLogic.weekOf(date, vm.semesterStartMonday)
    val dayList = ScheduleLogic.meetingsOn(date, week, termData.meetings)
    val status = if (isToday) ScheduleLogic.statusAt(LocalTime.now(), dayList) else null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.today_title)) },
                actions = {
                    if (ui.syncing) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    } else {
                        TextButton(onClick = { vm.sync() }) { Text(stringResource(R.string.today_sync)) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (ui.needWebLogin) {
                Card(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.today_risk_control_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.today_risk_control_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onOpenWebLogin) { Text(stringResource(R.string.today_open_web_login)) }
                    }
                }
            }
            val start = vm.semesterStartMonday
            DayPager(
                date = date,
                week = week,
                isToday = isToday,
                canGoPrev = start?.let { date > it } ?: true,
                canGoNext = start?.let { date < it.plusDays(30L * 7 - 1) } ?: true,
                onPrev = { dayOffset-- },
                onNext = { dayOffset++ },
                onBackToToday = { dayOffset = 0 },
            )
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (status != null) {
                    item { StatusCard(status, dayList) }
                }
                if (dayList.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                            Text(
                                when {
                                    week == null -> stringResource(R.string.today_not_started)
                                    isToday -> stringResource(R.string.today_no_class)
                                    else -> stringResource(R.string.today_no_class_day)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                val curIdx = (status as? ClassStatus.InClass)?.meeting
                    ?.let { c -> dayList.indexOfFirst { it == c } }?.takeIf { it >= 0 }
                val nextIdx = (status as? ClassStatus.Upcoming)?.meeting
                    ?.let { n -> dayList.indexOfFirst { it == n } }?.takeIf { it >= 0 }
                itemsIndexed(
                    dayList,
                    key = { _, m -> "${m.startTime}-${m.room}-${m.rwh}-${m.weeks.hashCode()}" },
                ) { i, m ->
                    val course = termData.courseByRwh[m.rwh]
                    val scale = when {
                        curIdx != null && i == curIdx -> 1.4f
                        nextIdx != null && i == nextIdx -> 1.25f
                        nextIdx != null && i > nextIdx -> (1.2f - 0.05f * (i - nextIdx)).coerceAtLeast(1f)
                        else -> 1f
                    }
                    MeetingCard(
                        meeting = m,
                        courseName = course?.name ?: "?",
                        scale = scale,
                        isCurrent = status is ClassStatus.InClass && (status as ClassStatus.InClass).meeting == m,
                        isNext = status is ClassStatus.Upcoming && (status as ClassStatus.Upcoming).meeting == m,
                        onClick = { onOpenCourse(m.rwh) },
                    )
                }
            }
        }
    }
}

/** 日分页器：‹ 日期 周次 ›，非今日时提供「回到今天」。 */
@Composable
private fun DayPager(
    date: LocalDate,
    week: Int?,
    isToday: Boolean,
    canGoPrev: Boolean,
    canGoNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onBackToToday: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPrev, enabled = canGoPrev) {
                Text("‹", style = MaterialTheme.typography.titleLarge)
            }
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isToday) {
                    Text(
                        stringResource(R.string.today_label_today),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    buildString {
                        append(date.format(DateTimeFormatter.ofPattern("M月d日")))
                        append(" ")
                        append(weekdayName(date.dayOfWeek.value))
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                week?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.week_title, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onNext, enabled = canGoNext) {
                Text("›", style = MaterialTheme.typography.titleLarge)
            }
        }
        if (!isToday) {
            TextButton(onClick = onBackToToday, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.today_back_to_today))
            }
        }
    }
}

@Composable
private fun StatusCard(status: ClassStatus, dayList: List<Meeting>) {
    val (bg, fg) = when (status) {
        is ClassStatus.InClass -> Color(0xFF1E8E3E) to Color.White
        is ClassStatus.Upcoming -> MaterialTheme.colorScheme.primary to Color.White
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val noRoom = stringResource(R.string.no_room)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            when (status) {
                is ClassStatus.InClass -> {
                    Text(stringResource(R.string.today_in_class), style = MaterialTheme.typography.labelLarge)
                    Text(
                        stringResource(R.string.today_ends_in, status.endsInMinutes),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                is ClassStatus.Upcoming -> {
                    Text(stringResource(R.string.today_next_class), style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (status.startsInMinutes >= 60) stringResource(R.string.today_starts_in_hours, status.startsInMinutes / 60)
                        else stringResource(R.string.today_starts_in_minutes, status.startsInMinutes),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${status.meeting.startTime} · ${status.meeting.room ?: noRoom}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is ClassStatus.Finished -> Text(stringResource(R.string.today_finished), style = MaterialTheme.typography.titleMedium)
                is ClassStatus.Free -> Text(stringResource(R.string.today_free), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** 按倍率缩放字号与行高。 */
private fun TextStyle.scaled(scale: Float) = copy(
    fontSize = if (fontSize.isSpecified) fontSize * scale else fontSize,
    lineHeight = if (lineHeight.isSpecified) lineHeight * scale else lineHeight,
)

@Composable
private fun MeetingCard(
    meeting: Meeting,
    courseName: String,
    isCurrent: Boolean,
    isNext: Boolean,
    scale: Float = 1f,
    onClick: () -> Unit,
) {
    val labBadge = stringResource(R.string.badge_lab)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isCurrent -> MaterialTheme.colorScheme.secondaryContainer
                isNext -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        shape = RoundedCornerShape((12 * scale).dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isCurrent || isNext) 2.dp else 0.dp),
    ) {
        Row(Modifier.padding((14 * scale).dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    meeting.startTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                    style = MaterialTheme.typography.titleMedium.scaled(scale),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    meeting.endTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                    style = MaterialTheme.typography.bodySmall.scaled(scale),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(14.dp))
            Box(
                Modifier
                    .size(width = (4 * scale).dp, height = (40 * scale).dp)
                    .background(roleColor(meeting), RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        courseName,
                        style = MaterialTheme.typography.titleSmall.scaled(scale),
                        fontWeight = if (isCurrent || isNext) FontWeight.Bold else FontWeight.Medium,
                    )
                    if (meeting.role == ScheduleParser.ROLE_LAB) {
                        Spacer(Modifier.width(6.dp))
                        RoleBadge(labBadge, Color(0xFF9C27B0))
                    }
                }
                Text(
                    buildString {
                        append(stringResource(R.string.today_periods, meeting.startPeriod, meeting.endPeriod))
                        meeting.room?.let { append(" · $it") }
                        if (meeting.teachers.isNotEmpty()) append(" · ${meeting.teachers.joinToString(" ")}")
                    },
                    style = MaterialTheme.typography.bodySmall.scaled(scale),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isNext) NextBadge()
            if (isCurrent) NowBadge()
        }
    }
}

@Composable
fun roleColor(meeting: Meeting): Color = if (meeting.role == ScheduleParser.ROLE_LAB)
    Color(0xFF9C27B0) else MaterialTheme.colorScheme.primary

@Composable
private fun RoleBadge(text: String, color: Color) {
    Box(
        Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun NextBadge() {
    Box(Modifier.background(Color(0xFF1B6EF3), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(stringResource(R.string.badge_next), color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun NowBadge() {
    Box(Modifier.background(Color(0xFF1E8E3E), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(stringResource(R.string.badge_now), color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
