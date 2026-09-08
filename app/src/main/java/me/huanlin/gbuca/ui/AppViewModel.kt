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
        if (!serverConfigured) {
            ui.value = ui.value.copy(syncing = false, message = app.getString(R.string.error_server_unset))
            return
        }
        viewModelScope.launch {
            ui.value = ui.value.copy(syncing = true, message = null, calibrateOk = null)
            val result = runCatchingNonCancellation { repo.sync(xnxq) }
            val e = result.exceptionOrNull()
            ui.value = if (e == null) {
                me.huanlin.gbuca.widget.TodayWidgetReceiver.refreshAll(app)
                ui.value.copy(
                    syncing = false,
                    message = app.getString(R.string.msg_synced_courses, result.getOrThrow().courseCount),
                )
            } else {
                ui.value.copy(
                    syncing = false,
                    message = friendlyError(e),
                    needWebLogin = e is GbuException.NeedCaptcha || e is GbuException.NeedSms,
                )
            }
            app.reminderScheduler.rescheduleAsync()
        }
    }

    /** 首次登录：先认证，成功才保存凭据并同步课表。成功时不产生任何提示消息。 */
    fun login(u: String, p: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            ui.value = ui.value.copy(syncing = true, message = null, needWebLogin = false, calibrateOk = null)
            val result = runCatchingNonCancellation {
                app.client.login(u, p)
                app.creds.save(u, p)
                repo.sync(xnxq)
            }
            val e = result.exceptionOrNull()
            ui.value = ui.value.copy(
                syncing = false,
                // 仅失败时提示：成功时若仍调 friendlyError(null) 会渲染成「同步失败：?」
                message = e?.let { friendlyError(it) },
                needWebLogin = e is GbuException.NeedCaptcha || e is GbuException.NeedSms,
            )
            if (e == null) {
                me.huanlin.gbuca.widget.TodayWidgetReceiver.refreshAll(app)
                app.reminderScheduler.rescheduleAsync()
                onSuccess()
            }
        }
    }

    /** runCatching，但协程取消原样抛出：取消不是业务失败，不能被当成错误提示。 */
    private inline fun <T> runCatchingNonCancellation(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Result.failure(t)
        }

    /** 异常 → 用户可读文案；永不为空、永不出现 "?"。 */
    private fun friendlyError(e: Throwable): String = when (e) {
        is GbuException.BadCredentials -> app.getString(R.string.error_login_failed, e.message0)
        is GbuException.NeedCaptcha -> app.getString(R.string.error_need_captcha)
        is GbuException.NeedSms -> app.getString(R.string.error_need_sms)
        is GbuException.SessionExpired -> app.getString(R.string.error_session_expired)
        is GbuException.Network -> app.getString(R.string.error_network)
        is java.io.IOException -> app.getString(R.string.error_network)
        else -> app.getString(
            R.string.error_sync_failed,
            e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName,
        )
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

    /** 手动从教务系统校准「第 1 周周一」（清除手动设置并强制生效；无会话先自动登录）。 */
    fun calibrateSemesterStartFromServer() {
        if (!serverConfigured) {
            ui.value = ui.value.copy(message = app.getString(R.string.error_server_unset))
            return
        }
        viewModelScope.launch {
            ui.value = ui.value.copy(syncing = true, message = null, calibrateOk = null)
            val result = runCatchingNonCancellation { repo.calibrateSemesterStart(xnxq, force = true) }
            val date = result.getOrNull()
            val e = result.exceptionOrNull()
            ui.value = ui.value.copy(
                syncing = false,
                message = when {
                    date != null -> app.getString(R.string.msg_calibrate_success, date)
                    e != null -> friendlyError(e)
                    else -> app.getString(R.string.msg_calibrate_unavailable)
                },
                calibrateOk = if (date != null) true else false,
                needWebLogin = e is GbuException.NeedCaptcha || e is GbuException.NeedSms,
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

    // ---- 服务器地址（OOBE / 设置页） ----

    val jwxtHost: String get() = settings.jwxtHost
    val iaaaHost: String get() = settings.iaaaHost

    /** OOBE 是否已完成（两个地址均已配置）；未完成时不做任何网络访问。 */
    val serverConfigured: Boolean
        get() = settings.jwxtHost.isNotBlank() && settings.iaaaHost.isNotBlank()

    /** OOBE 完成：保存服务器地址（调用方已用 HostNormalizer 校验）。 */
    fun completeSetup(jwxtHost: String, iaaaHost: String, onDone: () -> Unit) {
        settings.jwxtHost = jwxtHost
        settings.iaaaHost = iaaaHost
        onDone()
    }

    /** 设置页修改服务器地址：清空旧域会话，随后自动重登并同步。 */
    fun saveHosts(jwxtHost: String, iaaaHost: String) {
        val changed = settings.jwxtHost != jwxtHost || settings.iaaaHost != iaaaHost
        settings.jwxtHost = jwxtHost
        settings.iaaaHost = iaaaHost
        if (changed) app.cookieJar.clear()
        sync()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel() as T
        }
    }
}
