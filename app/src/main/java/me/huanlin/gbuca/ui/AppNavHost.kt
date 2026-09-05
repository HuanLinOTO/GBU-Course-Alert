package me.huanlin.gbuca.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import me.huanlin.gbuca.R
import me.huanlin.gbuca.reminder.ReminderScheduler

private const val PUSH_MS = 300

@Composable
fun AppNavHost(
    vm: AppViewModel,
    onOpenWebLogin: () -> Unit,
    reminderScheduler: ReminderScheduler,
) {
    val nav = rememberNavController()

    NavHost(
        navController = nav,
        startDestination = "tabs",
        enterTransition = {
            slideInHorizontally(tween(PUSH_MS)) { it } + fadeIn(tween(PUSH_MS))
        },
        exitTransition = {
            slideOutHorizontally(tween(PUSH_MS)) { -it }
        },
        popEnterTransition = {
            slideInHorizontally(tween(PUSH_MS)) { -it }
        },
        popExitTransition = {
            slideOutHorizontally(tween(PUSH_MS)) { it }
        },
    ) {
        composable("tabs") {
            TabsScreen(
                vm = vm,
                onOpenCourse = { rwh -> nav.navigate("course/$rwh") },
                onOpenWebLogin = onOpenWebLogin,
                reminderScheduler = reminderScheduler,
            )
        }
        composable("course/{rwh}") { entry ->
            val rwh = entry.arguments?.getString("rwh") ?: return@composable
            CourseDetailScreen(rwh = rwh, vm = vm)
        }
    }
}

/** 三个主页面：HorizontalPager 跟手横滑 + 底部导航联动。 */
@Composable
private fun TabsScreen(
    vm: AppViewModel,
    onOpenCourse: (String) -> Unit,
    onOpenWebLogin: () -> Unit,
    reminderScheduler: ReminderScheduler,
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0) { tabs.size }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, t ->
                    val label = stringResource(t.label)
                    NavigationBarItem(
                        selected = pagerState.targetPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        icon = { Icon(t.icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
            // 3 个页面全部常驻组合，滑动零重组卡顿
            beyondViewportPageCount = tabs.size - 1,
        ) { page ->
            when (page) {
                0 -> TodayScreen(
                    onOpenCourse = onOpenCourse,
                    onOpenWebLogin = onOpenWebLogin,
                    vm = vm,
                )
                1 -> WeekScreen(onOpenCourse = onOpenCourse, vm = vm)
                2 -> SettingsScreen(reminderScheduler = reminderScheduler, vm = vm)
            }
        }
    }
}

private val tabs = listOf(
    Tab(R.string.tab_today, Icons.Filled.DateRange),
    Tab(R.string.tab_week, Icons.AutoMirrored.Filled.List),
    Tab(R.string.tab_settings, Icons.Filled.Settings),
)

private data class Tab(val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)
