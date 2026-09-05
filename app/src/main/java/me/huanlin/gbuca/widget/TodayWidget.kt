package me.huanlin.gbuca.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.huanlin.gbuca.GbuCaApp
import me.huanlin.gbuca.domain.logic.ScheduleLogic
import me.huanlin.gbuca.domain.model.Meeting
import me.huanlin.gbuca.ui.MainActivity
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class WidgetState(
    val date: LocalDate,
    val week: Int?,
    val items: List<WidgetItem>,
) {
    data class WidgetItem(
        val name: String,
        val time: String,
        val room: String?,
        val isNext: Boolean,
        val inProgress: Boolean,
    )
}

class TodayWidget : GlanceAppWidget() {

    override val sizeMode = androidx.glance.appwidget.SizeMode.Responsive(
        setOf(androidx.compose.ui.unit.DpSize(180.dp, 110.dp), androidx.compose.ui.unit.DpSize(270.dp, 110.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = loadState()
        provideContent {
            GlanceTheme {
                WidgetContent(state)
            }
        }
    }

    companion object {
        suspend fun loadState(): WidgetState = withContext(Dispatchers.IO) {
            val app = GbuCaApp.instance
            val now = LocalDate.now()
            val xnxq = app.settings.selectedXnxq ?: app.client.fallbackXnxq().third
            val startMonday = app.settings.semesterStartMonday(xnxq)
            val week = ScheduleLogic.weekOf(now, startMonday)
            val meetings = app.repo.meetingsByXnxq(xnxq)
            val today = ScheduleLogic.meetingsOn(now, week, meetings)
            val nowTime = LocalTime.now()
            val courseNames = today.associate { it.rwh to (app.repo.courseByRwh(it.rwh)?.name ?: "?") }

            WidgetState(
                date = now,
                week = week,
                items = today.map { m ->
                    WidgetState.WidgetItem(
                        name = courseNames[m.rwh] ?: "?",
                        time = "${m.startTime.format(DateTimeFormatter.ofPattern("HH:mm"))}–${m.endTime.format(DateTimeFormatter.ofPattern("HH:mm"))}",
                        room = m.room,
                        isNext = !m.startTime.isBefore(nowTime) &&
                            today.filter { !it.startTime.isBefore(nowTime) }.minByOrNull { it.startTime } == m,
                        inProgress = !nowTime.isBefore(m.startTime) && nowTime.isBefore(m.endTime),
                    )
                },
            )
        }
    }
}

@Composable
private fun WidgetContent(state: WidgetState) {
    Column(
        modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity<MainActivity>())
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth().padding(8.dp)) {
            Text(
                text = if (state.week != null) "第${state.week}周 · ${weekdayLabel(state.date)}" else weekdayLabel(state.date),
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = "${state.items.size} 节课",
                style = TextStyle(fontSize = 12.sp),
            )
        }
        if (state.items.isEmpty()) {
            Column(
                modifier = GlanceModifier.fillMaxSize().padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("今天没有课 🎉", style = TextStyle(fontSize = 14.sp))
            }
        } else {
            Column(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 10.dp)) {
                state.items.take(4).forEach { item ->
                    Row(
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.time,
                            style = TextStyle(
                                fontSize = 11.sp,
                                fontWeight = if (item.isNext || item.inProgress) FontWeight.Bold else FontWeight.Normal,
                                color = if (item.isNext) ColorProvider(Color(0xFF1B6EF3))
                                else if (item.inProgress) ColorProvider(Color(0xFF1E8E3E))
                                else GlanceTheme.colors.onSurface,
                            ),
                        )
                        Spacer(GlanceModifier.defaultWeight())
                        Text(
                            text = buildString {
                                append(item.name)
                                item.room?.let { append(" · $it") }
                            },
                            maxLines = 1,
                            style = TextStyle(
                                fontSize = 12.sp,
                                fontWeight = if (item.isNext || item.inProgress) FontWeight.Bold else FontWeight.Normal,
                            ),
                        )
                    }
                }
                if (state.items.size > 4) {
                    Text(
                        "还有 ${state.items.size - 4} 节…",
                        style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                        modifier = GlanceModifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

private fun weekdayLabel(d: LocalDate): String = when (d.dayOfWeek.value) {
    1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"; 5 -> "周五"; 6 -> "周六"; else -> "周日"
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()

    companion object {
        suspend fun refreshAll(context: Context) {
            runCatching {
                androidx.glance.appwidget.GlanceAppWidgetManager(context)
                    .getGlanceIds(TodayWidget::class.java)
                    .forEach { id -> TodayWidget().update(context, id) }
            }
        }
    }
}
