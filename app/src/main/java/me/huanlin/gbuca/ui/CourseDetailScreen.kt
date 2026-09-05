package me.huanlin.gbuca.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.huanlin.gbuca.R
import me.huanlin.gbuca.domain.logic.ScheduleLogic
import me.huanlin.gbuca.domain.parser.ScheduleParser
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailScreen(rwh: String, vm: AppViewModel) {
    val termData by vm.termData.collectAsState()
    val course = termData.courseByRwh[rwh]
    val titleFallback = stringResource(R.string.detail_title_fallback)
    val noRoom = stringResource(R.string.no_room)
    val meetings = termData.meetings.filter { it.rwh == rwh }
        .sortedWith(compareBy({ it.weekday }, { it.startTime }))

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(course?.name ?: titleFallback) })
        if (course == null) {
            Text(stringResource(R.string.detail_not_found), Modifier.padding(16.dp))
            return
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column {
                    course.nameEn?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    InfoGrid(course.code, course.seq, course.className, course.nature, course.category,
                        course.college, course.credits, course.hours, course.enrollTime,
                        course.capacity, course.enrolled)
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.detail_schedule_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
            }
            items(
                meetings,
                key = { "${it.weekday}-${it.startTime}-${it.role}-${it.weeks.hashCode()}" },
            ) { m ->
                val wd = weekdayName(m.weekday)
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row {
                        Text(
                            wd,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                R.string.today_periods, m.startPeriod, m.endPeriod,
                            ) + " ${m.startTime}-${m.endTime}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (m.role == ScheduleParser.ROLE_LAB) stringResource(R.string.badge_lab)
                            else stringResource(R.string.detail_role_main),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(
                        "${ScheduleLogic.formatWeeks(m.weeks)} · ${m.room ?: noRoom}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (m.teachers.isNotEmpty()) {
                        Text(
                            m.teachers.joinToString("、"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(Modifier.padding(top = 6.dp))
                }
            }
            if (course.unparsed.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.detail_unparsed), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error)
                    course.unparsed.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoGrid(
    code: String?, seq: String?, className: String?, nature: String?, category: String?,
    college: String?, credits: Double, hours: Double, enrollTime: String?,
    capacity: Int?, enrolled: Int?,
) {
    val rows = listOf(
        stringResource(R.string.detail_label_code) to code,
        stringResource(R.string.detail_label_seq) to seq,
        stringResource(R.string.detail_label_class) to className,
        stringResource(R.string.detail_label_nature) to nature,
        stringResource(R.string.detail_label_category) to category,
        stringResource(R.string.detail_label_college) to college,
        stringResource(R.string.detail_label_credits) to credits.toString(),
        stringResource(R.string.detail_label_hours) to hours.toString(),
        stringResource(R.string.detail_label_enroll_time) to enrollTime,
        stringResource(R.string.detail_label_capacity) to if (capacity != null) "$capacity / ${enrolled ?: "-"}" else null,
    ).filter { it.second != null }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { (k, v) ->
                    Row(Modifier.weight(1f)) {
                        Text(k, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text(v ?: "-", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
