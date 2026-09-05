package me.huanlin.gbuca.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.huanlin.gbuca.GbuCaApp
import me.huanlin.gbuca.data.GbuException
import me.huanlin.gbuca.data.repo.CourseRepository
import me.huanlin.gbuca.domain.logic.ScheduleLogic
import me.huanlin.gbuca.domain.model.TermData
import me.huanlin.gbuca.domain.time.TimeGrid

class AppViewModel : ViewModel() {

    private val app = GbuCaApp.instance
    private val repo: CourseRepository = app.repo

    val settings = app.settings

    /** 当前选中的 xnxq；null = 自动（当前学期）。 */
    val selectedXnxq: StateFlow<String?> = MutableStateFlow(app.settings.selectedXnxq)

    val xnxq: String = app.settings.selectedXnxq ?: app.client.fallbackXnxq().third

    val termData: StateFlow<TermData> =
        repo.observeTermData(xnxq).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TermData(emptyList(), emptyList()))

    val semesterStartMonday get() = settings.semesterStartMonday(xnxq)

    data class UiState(
        val syncing: Boolean = false,
        val message: String? = null,
        val needWebLogin: Boolean = false,
    )

    val ui = MutableStateFlow(UiState())

    fun sync() {
        viewModelScope.launch {
            ui.value = ui.value.copy(syncing = true, message = null)
            val result = runCatching { repo.sync(xnxq) }
            ui.value = when {
                result.isSuccess -> {
                    me.huanlin.gbuca.widget.TodayWidgetReceiver.refreshAll(app)
                    ui.value.copy(
                        syncing = false,
                        message = "已同步 ${result.getOrThrow().courseCount} 门课程",
                    )
                }
                else -> ui.value.copy(
                    syncing = false,
                    message = friendlyError(result.exceptionOrNull()),
                    needWebLogin = result.exceptionOrNull() is GbuException.NeedCaptcha ||
                        result.exceptionOrNull() is GbuException.NeedSms,
                )
            }
            app.reminderScheduler.rescheduleAsync()
        }
    }

    /** 首次登录：先认证，成功才保存凭据并同步课表。 */
    fun login(u: String, p: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            ui.value = ui.value.copy(syncing = true, message = null, needWebLogin = false)
            val result = runCatching {
                app.client.login(u, p)
                app.creds.save(u, p)
                repo.sync(xnxq)
            }
            val e = result.exceptionOrNull()
            ui.value = ui.value.copy(
                syncing = false,
                message = friendlyError(e),
                needWebLogin = e is GbuException.NeedCaptcha || e is GbuException.NeedSms,
            )
            if (result.isSuccess) {
                me.huanlin.gbuca.widget.TodayWidgetReceiver.refreshAll(app)
                app.reminderScheduler.rescheduleAsync()
                onSuccess()
            }
        }
    }

    private fun friendlyError(e: Throwable?): String = when (e) {
        is GbuException.BadCredentials -> "登录失败：${e.message}"
        is GbuException.NeedCaptcha -> "需要验证码，请使用网页登录"
        is GbuException.NeedSms -> "需要短信验证码，请使用网页登录"
        is GbuException.SessionExpired -> "会话已过期，请重新同步"
        is GbuException.Network -> "网络错误，请检查连接"
        else -> "同步失败：${e?.message ?: "未知错误"}"
    }

    fun saveCredentials(u: String, p: String) {
        app.creds.save(u, p)
    }

    fun clearMessage() {
        ui.value = ui.value.copy(message = null)
    }

    fun setSemesterStartMonday(date: java.time.LocalDate) {
        settings.setSemesterStartMonday(xnxq, date)
        app.reminderScheduler.rescheduleAsync()
    }

    /** 手动从教务系统校准「第 1 周周一」（清除手动设置并强制生效）。 */
    fun calibrateSemesterStartFromServer() {
        viewModelScope.launch {
            ui.value = ui.value.copy(syncing = true, message = null)
            val result = runCatching { repo.calibrateSemesterStart(xnxq, force = true) }
            val date = result.getOrNull()
            ui.value = ui.value.copy(
                syncing = false,
                message = when {
                    date != null -> "校准成功：第 1 周周一 = $date"
                    result.isFailure -> friendlyError(result.exceptionOrNull())
                    else -> "无法校准：教务系统未返回该学期的周次信息"
                },
            )
            if (date != null) {
                app.reminderScheduler.rescheduleAsync()
                me.huanlin.gbuca.widget.TodayWidgetReceiver.refreshAll(app)
            }
        }
    }

    fun updateGridFromData() {
        // TimeGrid 在同步时已由 kbjclist 更新
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel() as T
        }
    }
}
