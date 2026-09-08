package me.huanlin.gbuca.ui

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.huanlin.gbuca.BuildConfig
import me.huanlin.gbuca.GbuCaApp
import me.huanlin.gbuca.R
import me.huanlin.gbuca.data.GbuDiagnostics
import me.huanlin.gbuca.data.GbuException
import me.huanlin.gbuca.data.repo.CourseRepository
import me.huanlin.gbuca.domain.logic.ScheduleLogic
import me.huanlin.gbuca.domain.model.TermData
import me.huanlin.gbuca.domain.oobe.OobeFlow
import me.huanlin.gbuca.domain.oobe.OobeStep
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

    /** 「今日休息」是否对今天生效；跨零点或从后台返回时由 [refreshResting] 重新判定。 */
    private val _resting = MutableStateFlow(settings.isRestingToday())
    val resting: StateFlow<Boolean> = _resting

    /**
     * 切换「今日休息」：开启即取消当天全部提醒（精确闹钟 + Live Update 常驻倒计时）；
     * 关闭即按当前设置重排。仅对当日生效，跨零点自动失效。
     */
    fun setResting(on: Boolean) {
        settings.restDay = if (on) java.time.LocalDate.now().toEpochDay() else null
        _resting.value = on
        if (on) app.reminderScheduler.cancelAll() else app.reminderScheduler.rescheduleAsync()
        viewModelScope.launch { me.huanlin.gbuca.widget.TodayWidgetReceiver.refreshAll(app) }
    }

    /** 重新判定休息状态（跨零点后 / 从后台回到前台）。 */
    fun refreshResting() {
        _resting.value = settings.isRestingToday()
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
        /** 失败时的技术细节（可复制上报）；成功消息或非接口失败为 null。 */
        val errorDetail: String? = null,
        /** 消息语义：true=成功（主色）、false=失败（错误色）、null=中性（错误色）。 */
        val messageOk: Boolean? = null,
        val needWebLogin: Boolean = false,
        /** 导出到日历的独立提示（不与同步/校准消息串台）。 */
        val exportMessage: String? = null,
        val exportOk: Boolean? = null,
    )

    val ui = MutableStateFlow(UiState())

    fun sync() {
        if (!serverConfigured) {
            ui.value = ui.value.copy(syncing = false, message = app.getString(R.string.error_server_unset))
            return
        }
        viewModelScope.launch {
            ui.value = ui.value.copy(syncing = true, message = null, errorDetail = null, messageOk = null)
            val result = runCatchingNonCancellation { repo.sync(xnxq) }
            val e = result.exceptionOrNull()
            ui.value = if (e == null) {
                me.huanlin.gbuca.widget.TodayWidgetReceiver.refreshAll(app)
                ui.value.copy(
                    syncing = false,
                    message = app.getString(R.string.msg_synced_courses, result.getOrThrow().courseCount),
                    messageOk = true,
                )
            } else {
                ui.value.copy(
                    syncing = false,
                    message = friendlyError(e),
                    errorDetail = errorDetailOf(e),
                    messageOk = false,
                    needWebLogin = e is GbuException.NeedCaptcha || e is GbuException.NeedSms,
                )
            }
            app.reminderScheduler.rescheduleAsync()
        }
    }

    /** 首次登录：先认证，成功才保存凭据并同步课表。成功时不产生任何提示消息。 */
    fun login(u: String, p: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            ui.value = ui.value.copy(
                syncing = true,
                message = null,
                errorDetail = null,
                messageOk = null,
                needWebLogin = false,
            )
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
                errorDetail = errorDetailOf(e),
                messageOk = if (e == null) null else false,
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
        // summary 自带环节与最可能原因，不再套「同步失败：」前缀
        is GbuException.ApiError -> e.summary
        is java.io.IOException -> app.getString(R.string.error_network)
        else -> app.getString(
            R.string.error_sync_failed,
            e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName,
        )
    }

    /** 失败详情（含版本与时间），供「复制错误详情」；非 ApiError 返回 null。 */
    private fun errorDetailOf(e: Throwable?): String? =
        (e as? GbuException.ApiError)?.let {
            GbuDiagnostics.reportOf(
                message = it.summary,
                detail = it.detail,
                versionName = BuildConfig.VERSION_NAME,
                timestampMillis = System.currentTimeMillis(),
            )
        }

    /**
     * 设置页「保存并登录」：先用新凭据登录，成功才覆盖已存凭据，再同步。
     * 失败时凭据与会话保持原样 —— 旧会话仍可用，App 不会被打坏。
     */
    fun saveCredentialsAndLogin(u: String, p: String) {
        viewModelScope.launch {
            ui.value = ui.value.copy(syncing = true, message = null, errorDetail = null, messageOk = null)
            val auth = runCatchingNonCancellation {
                app.client.login(u, p)
                app.creds.save(u, p)
            }
            val authError = auth.exceptionOrNull()
            if (authError != null) {
                ui.value = ui.value.copy(
                    syncing = false,
                    message = friendlyError(authError),
                    errorDetail = errorDetailOf(authError),
                    messageOk = false,
                    needWebLogin = authError is GbuException.NeedCaptcha || authError is GbuException.NeedSms,
                )
                return@launch
            }
            val sync = runCatchingNonCancellation { repo.sync(xnxq) }
            val syncError = sync.exceptionOrNull()
            ui.value = ui.value.copy(
                syncing = false,
                message = when {
                    syncError == null ->
                        app.getString(R.string.msg_credentials_saved, sync.getOrThrow().courseCount)
                    else ->
                        app.getString(R.string.msg_credentials_saved_sync_failed, friendlyError(syncError))
                },
                errorDetail = errorDetailOf(syncError),
                messageOk = syncError == null,
                needWebLogin = syncError is GbuException.NeedCaptcha || syncError is GbuException.NeedSms,
            )
            me.huanlin.gbuca.widget.TodayWidgetReceiver.refreshAll(app)
            app.reminderScheduler.rescheduleAsync()
        }
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
            ui.value = ui.value.copy(syncing = true, message = null, errorDetail = null, messageOk = null)
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
                errorDetail = errorDetailOf(e),
                messageOk = date != null,
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

    // ---- 导出到日历 ----

    val suggestedIcsFileName: String get() = app.icsExport.suggestedFileName(xnxq)

    /** 导出当前学期到用户通过 SAF 选择的 Uri。 */
    fun exportIcs(uri: Uri) {
        viewModelScope.launch {
            ui.value = ui.value.copy(exportMessage = null, exportOk = null)
            val result = runCatchingNonCancellation {
                val content = app.icsExport.build(xnxq) ?: throw NoCourses
                app.icsExport.writeToUri(uri, content)
            }
            val e = result.exceptionOrNull()
            ui.value = ui.value.copy(
                exportMessage = when {
                    e == null -> app.getString(R.string.msg_export_ok)
                    e === NoCourses -> app.getString(R.string.msg_export_empty)
                    else -> exportFailed(e)
                },
                exportOk = e == null,
            )
        }
    }

    /** 构造分享 Intent 并交回 UI 层启动（UI 负责处理 ActivityNotFoundException）。 */
    fun shareIcs(onIntent: (Intent) -> Unit) {
        viewModelScope.launch {
            ui.value = ui.value.copy(exportMessage = null, exportOk = null)
            val result = runCatchingNonCancellation {
                val content = app.icsExport.build(xnxq) ?: throw NoCourses
                app.icsExport.shareIntent(content, app.icsExport.suggestedFileName(xnxq))
            }
            val intent = result.getOrNull()
            if (intent != null) {
                onIntent(intent)
            } else {
                val e = result.exceptionOrNull()
                ui.value = ui.value.copy(
                    exportMessage = if (e === NoCourses) app.getString(R.string.msg_export_empty)
                    else exportFailed(e ?: IllegalStateException("unknown")),
                    exportOk = false,
                )
            }
        }
    }

    /** 导出分组内的失败提示（如设备无日历 App）。 */
    fun showExportMessage(message: String) {
        ui.value = ui.value.copy(exportMessage = message, exportOk = false)
    }

    /** 导出失败的兜底文案：永不出现 "?"。 */
    private fun exportFailed(e: Throwable): String = app.getString(
        R.string.msg_export_failed,
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName,
    )

    private object NoCourses : Exception()

    // ---- 服务器地址与 OOBE（向导 / 设置页） ----

    val jwxtHost: String get() = settings.jwxtHost
    val iaaaHost: String get() = settings.iaaaHost

    /** OOBE 是否已完成（两个地址均已配置）；未完成时不做任何网络访问。 */
    val serverConfigured: Boolean
        get() = settings.jwxtHost.isNotBlank() && settings.iaaaHost.isNotBlank()

    /** 已保存的学号；无凭据时 null。 */
    val credentialUsername: String? get() = app.creds.username

    val hasCredentials: Boolean get() = app.creds.username != null

    /**
     * 是否需要走 OOBE 向导。
     * 已配置地址且已有凭据的既有安装（v0.0.5 及以前）静默视为完成，不打扰。
     */
    val needsOobe: Boolean
        get() = !settings.oobeDone && !(serverConfigured && hasCredentials)

    /** 自动进入向导时的起始步（第一个未完成的步骤）。 */
    fun oobeStartStep(): OobeStep = OobeFlow.startStep(serverConfigured, hasCredentials)

    /** 向导「开始使用」：写完成标记。 */
    fun finishOobe() {
        settings.oobeDone = true
    }

    /** 向导地址步：保存服务器地址（调用方已用 HostNormalizer 校验）。 */
    fun completeSetup(jwxtHost: String, iaaaHost: String) {
        settings.jwxtHost = jwxtHost
        settings.iaaaHost = iaaaHost
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
