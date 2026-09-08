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
                // OOBE 门控：服务器地址未配置（首次使用或升级后）先走配置向导
                var needsSetup by rememberSaveable {
                    mutableStateOf(app.settings.jwxtHost.isBlank() || app.settings.iaaaHost.isBlank())
                }
                var loggedIn by rememberSaveable { mutableStateOf(app.creds.username != null) }
                when {
                    needsSetup -> SetupScreen(vm) { needsSetup = false }
                    loggedIn -> AppNavHost(
                        vm = vm,
                        onOpenWebLogin = { WebLoginActivity.start(this) },
                        reminderScheduler = app.reminderScheduler,
                    )
                    else -> LoginScreen(
                        vm = vm,
                        onOpenWebLogin = { WebLoginActivity.start(this) },
                        onLoggedIn = { loggedIn = true },
                    )
                }
            }
        }
    }
}
