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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.huanlin.gbuca.domain.logic.ScheduleLogic
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailScreen(rwh: String, vm: AppViewModel) {
    val termData by vm.termData.collectAsState()
    val course = termData.courseByRwh[rwh]
    val meetings = termData.meetings.filter { it.rwh == rwh }
        .sortedWith(compareBy({ it.weekday }, { it.startTime }))

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(course?.name ?: "课程详情") })
        if (course == null) {
            Text("未找到课程", Modifier.padding(16.dp))
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
                Text("上课时间", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
            }
            items(
                meetings,
                key = { "${it.weekday}-${it.startTime}-${it.role}-${it.weeks.hashCode()}" },
            ) { m ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row {
                        Text(
                            ScheduleLogic.weekdayName(m.weekday),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "第${m.startPeriod}-${m.endPeriod}节 ${m.startTime}-${m.endTime}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (m.role == "课内实验") "课内实验" else "主讲",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(
                        "${ScheduleLogic.formatWeeks(m.weeks)} · ${m.room ?: "无地点"}",
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
                    Text("未能解析的原文", style = MaterialTheme.typography.titleSmall,
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
        "课程代码" to code, "课序号" to seq, "班级" to className,
        "课程性质" to nature, "课程类别" to category, "开课学院" to college,
        "学分" to credits.toString(), "学时" to hours.toString(),
        "选课时间" to enrollTime,
        "容量/已选" to if (capacity != null) "$capacity / ${enrolled ?: "-"}" else null,
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
