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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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

    val today = LocalDate.now()
    val week = ScheduleLogic.weekOf(today, vm.semesterStartMonday)
    val dayList = ScheduleLogic.meetingsOn(today, week, termData.meetings)
    val status = ScheduleLogic.statusAt(LocalTime.now(), dayList)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val wd = weekdayName(today.dayOfWeek.value)
                    Column {
                        Text(stringResource(R.string.today_title))
                        Text(
                            text = buildString {
                                append(today.format(DateTimeFormatter.ofPattern("M月d日")))
                                append(" ")
                                append(wd)
                                week?.let { append(" · 第${it}周") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
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
        if (ui.needWebLogin) {
            Card(
                modifier = Modifier.padding(padding).padding(16.dp).fillMaxWidth(),
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
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { StatusCard(status, dayList) }
            if (dayList.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (week == null) stringResource(R.string.today_not_started) else stringResource(R.string.today_no_class),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(
                dayList,
                key = { "${it.startTime}-${it.room}-${it.rwh}-${it.weeks.hashCode()}" },
            ) { m ->
                val course = termData.courseByRwh[m.rwh]
                MeetingCard(
                    meeting = m,
                    courseName = course?.name ?: "?",
                    isCurrent = status is ClassStatus.InClass && (status as ClassStatus.InClass).meeting == m,
                    isNext = status is ClassStatus.Upcoming && (status as ClassStatus.Upcoming).meeting == m,
                    onClick = { onOpenCourse(m.rwh) },
                )
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

@Composable
private fun MeetingCard(
    meeting: Meeting,
    courseName: String,
    isCurrent: Boolean,
    isNext: Boolean,
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
        elevation = CardDefaults.cardElevation(defaultElevation = if (isCurrent || isNext) 2.dp else 0.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    meeting.startTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    meeting.endTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(14.dp))
            Box(
                Modifier
                    .size(width = 4.dp, height = 40.dp)
                    .background(roleColor(meeting), RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        courseName,
                        style = MaterialTheme.typography.titleSmall,
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
                    style = MaterialTheme.typography.bodySmall,
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
