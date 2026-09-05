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
import me.huanlin.gbuca.R
import me.huanlin.gbuca.data.GbuException
import me.huanlin.gbuca.data.repo.CourseRepository
import me.huanlin.gbuca.domain.logic.ScheduleLogic
import me.huanlin.gbuca.domain.model.TermData
import me.huanlin.gbuca.domain.time.TimeGrid

class AppViewModel : ViewModel() {

    private val app = GbuCaApp.instance
    private val repo: CourseRepository = app.repo

    val settings = app.settings

    /** 设置项的可观察快照：SettingsStore 为普通持久化对象，UI 经由 StateFlow 响应变更。 */
    private val _remindersEnabled = MutableStateFlow(app.settings.remindersEnabled)
    val remindersEnabled: StateFlow<Boolean> = _remindersEnabled
    private val _reminderMinutes = MutableStateFlow(app.settings.reminderMinutes)
    val reminderMinutes: StateFlow<Int> = _reminderMinutes

    fun setRemindersEnabled(on: Boolean) {
        settings.remindersEnabled = on
        _remindersEnabled.value = on
        if (on) app.reminderScheduler.rescheduleAsync() else app.reminderScheduler.cancelAll()
    }

    fun setReminderMinutes(min: Int) {
        settings.reminderMinutes = min
        _reminderMinutes.value = min
        if (settings.remindersEnabled) app.reminderScheduler.rescheduleAsync()
    }

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
        /** 学期校准消息语义：true=成功（主色）、false=失败（错误色）、null=其他消息。 */
        val calibrateOk: Boolean? = null,
    )

    val ui = MutableStateFlow(UiState())

    fun sync() {
        viewModelScope.launch {
            ui.value = ui.value.copy(syncing = true, message = null, calibrateOk = null)
            val result = runCatching { repo.sync(xnxq) }
            ui.value = when {
                result.isSuccess -> {
                    me.huanlin.gbuca.widget.TodayWidgetReceiver.refreshAll(app)
                    ui.value.copy(
                        syncing = false,
                        message = app.getString(R.string.msg_synced_courses, result.getOrThrow().courseCount),
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
            ui.value = ui.value.copy(syncing = true, message = null, needWebLogin = false, calibrateOk = null)
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
        is GbuException.BadCredentials -> app.getString(R.string.error_login_failed, e.message)
        is GbuException.NeedCaptcha -> app.getString(R.string.error_need_captcha)
        is GbuException.NeedSms -> app.getString(R.string.error_need_sms)
        is GbuException.SessionExpired -> app.getString(R.string.error_session_expired)
        is GbuException.Network -> app.getString(R.string.error_network)
        else -> app.getString(R.string.error_sync_failed, e?.message ?: "?")
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
            ui.value = ui.value.copy(syncing = true, message = null, calibrateOk = null)
            val result = runCatching { repo.calibrateSemesterStart(xnxq, force = true) }
            val date = result.getOrNull()
            ui.value = ui.value.copy(
                syncing = false,
                message = when {
                    date != null -> app.getString(R.string.msg_calibrate_success, date)
                    result.isFailure -> friendlyError(result.exceptionOrNull())
                    else -> app.getString(R.string.msg_calibrate_unavailable)
                },
                calibrateOk = if (date != null) true else false,
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
