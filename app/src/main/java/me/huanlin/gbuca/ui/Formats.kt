package me.huanlin.gbuca.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import me.huanlin.gbuca.R

/** 星期文案（来自资源），weekday: 1=周一 … 7=周日。 */
@Composable
fun weekdayName(weekday: Int): String =
    stringArrayResource(R.array.weekdays).getOrElse(weekday - 1) { "?" }
