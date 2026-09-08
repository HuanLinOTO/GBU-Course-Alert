package me.huanlin.gbuca.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import me.huanlin.gbuca.GbuCaApp
import me.huanlin.gbuca.domain.oobe.OobeStep
import me.huanlin.gbuca.sync.SyncWorker
import me.huanlin.gbuca.ui.theme.GbuCaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SyncWorker.enqueue(this)
        val app = GbuCaApp.instance
        setContent {
            GbuCaTheme {
                val vm: AppViewModel = viewModel(factory = AppViewModel.Factory)
                // null = 不在向导中；非 null = 向导入口步（老用户已配置+已登录时为 null）
                var oobeStart by rememberSaveable {
                    mutableStateOf(if (vm.needsOobe) vm.oobeStartStep() else null)
                }
                var loggedIn by rememberSaveable { mutableStateOf(app.creds.username != null) }
                when (val start = oobeStart) {
                    null -> if (loggedIn) {
                        AppNavHost(
                            vm = vm,
                            onOpenWebLogin = { WebLoginActivity.start(this) },
                            onRerunOobe = { oobeStart = OobeStep.Address },
                            reminderScheduler = app.reminderScheduler,
                        )
                    } else {
                        LoginScreen(
                            vm = vm,
                            onOpenWebLogin = { WebLoginActivity.start(this) },
                            onLoggedIn = { loggedIn = true },
                        )
                    }
                    else -> SetupFlowScreen(
                        vm = vm,
                        reminderScheduler = app.reminderScheduler,
                        startStep = start,
                        onOpenWebLogin = { WebLoginActivity.start(this) },
                        onFinished = {
                            oobeStart = null
                            loggedIn = app.creds.username != null
                        },
                    )
                }
            }
        }
    }
}
