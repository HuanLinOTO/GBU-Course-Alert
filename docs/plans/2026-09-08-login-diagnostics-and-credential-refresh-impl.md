# 登录失败可诊断 + 设置页凭据立即生效 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让「同步失败：接口异常」变成可定位的具体原因（含可复制的技术详情），并让设置页修改学号/密码后真正用新凭据重新登录。

**Architecture:** 方案 A —— `GbuException.ApiError` 升级为结构化异常（stage + summary + url + httpStatus + snippet），技术文本由无 Android 依赖的纯函数 `GbuDiagnostics` 生成；`AppViewModel` 把它拆成用户可读 `message` 与技术 `errorDetail`，UI 用共用 `ErrorMessage` 组件渲染并提供「复制错误详情」；设置页改为「先用新凭据登录 → 成功才落盘 → 再同步」。

**Tech Stack:** Kotlin 2.4.10 / Jetpack Compose (Material3, BOM 2026.08.00) / OkHttp 5.5.0 / kotlinx.serialization / JUnit4

**Spec:** `docs/plans/2026-09-08-login-diagnostics-and-credential-refresh-design.md`（已批准）

## Global Constraints

- 凭据（学号 / 密码）、iAAA token **永不**进入 `message`、`errorDetail` 或日志；detail 只允许包含响应片段与请求 URL
- 响应片段统一走 `GbuDiagnostics.normalizeSnippet`（折叠空白 + 去控制字符 + 截断 400 字）
- `friendlyError` 永不返回空串、永不渲染成 `?`
- 用户可见文案一律中文，新增文案进 `app/src/main/res/values/strings.xml`，不在代码里硬编码中文
- 剪贴板使用 Android 框架 `ClipboardManager`（**不用** `LocalClipboardManager`，该 API 在当前 Compose BOM 下已弃用）
- 不引入新依赖；不改 Room schema；minSdk 26 / compileSdk 37 / JVM 17
- `ApiError` 的用户文案不加「同步失败：」前缀（summary 自带环节说明）
- gradle 命令须在持久 terminal 中运行（阻塞型进程），用 `terminal_wait_for` 等待结果
- 每个 Task 结束提交一次，提交信息用中文（仓库惯例）

---

### Task 1: GbuDiagnostics 纯函数 + 单测

**Files:**
- Create: `app/src/main/java/me/huanlin/gbuca/data/GbuDiagnostics.kt`
- Test: `app/src/test/java/me/huanlin/gbuca/GbuDiagnosticsTest.kt`

**Interfaces:**
- Consumes: 无（基础层，仅依赖 `GbuException.ApiError.Stage`，Task 2 提供）
- Produces:
  - `GbuDiagnostics.SNIPPET_MAX: Int = 400`
  - `GbuDiagnostics.normalizeSnippet(raw: String?, max: Int = SNIPPET_MAX): String?`
  - `GbuDiagnostics.detailOf(stage: GbuException.ApiError.Stage, url: String? = null, httpStatus: Int? = null, snippet: String? = null): String`
  - `GbuDiagnostics.reportOf(message: String, detail: String?, versionName: String, timestampMillis: Long): String`

> **顺序提示：** 本任务与 Task 2 互相引用（本文件用 `Stage`，Task 2 的 `ApiError.detail` 用本文件）。
> 先按 Step 1 写测试会编译失败 —— 这正是 TDD 的失败态。Step 3/4 需要先完成 Task 2 的
> `GbuException.kt` 改造，再回来跑通。执行顺序：Task1.Step1 → Task2.Step1~3 → Task1.Step2~5。

- [x] **Step 1: 写失败测试**

```kotlin
package me.huanlin.gbuca

import me.huanlin.gbuca.data.GbuDiagnostics
import me.huanlin.gbuca.data.GbuException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GbuDiagnosticsTest {

    @Test fun `null snippet stays null`() = assertNull(GbuDiagnostics.normalizeSnippet(null))

    @Test fun `blank snippet becomes null`() =
        assertNull(GbuDiagnostics.normalizeSnippet("  \n\t  "))

    @Test fun `whitespace collapsed`() =
        assertEquals("a b c", GbuDiagnostics.normalizeSnippet("a\n\n  b\t c"))

    @Test fun `control characters removed`() =
        assertEquals("ab", GbuDiagnostics.normalizeSnippet("a\u0000\u0007b"))

    @Test fun `long snippet truncated with ellipsis`() {
        val out = GbuDiagnostics.normalizeSnippet("x".repeat(500), max = 10)
        assertEquals("xxxxxxxxxx…", out)
    }

    @Test fun `detail lists stage url status and snippet`() {
        val d = GbuDiagnostics.detailOf(
            GbuException.ApiError.Stage.CourseApi,
            url = "https://jwxt.example.edu.cn/Xsxk/queryYxkc",
            httpStatus = 404,
            snippet = "<html>not found</html>",
        )
        assertTrue(d.contains("环节：教务接口"))
        assertTrue(d.contains("地址：https://jwxt.example.edu.cn/Xsxk/queryYxkc"))
        assertTrue(d.contains("HTTP：404"))
        assertTrue(d.contains("响应片段：<html>not found</html>"))
    }

    @Test fun `detail omits empty fields`() {
        val d = GbuDiagnostics.detailOf(GbuException.ApiError.Stage.IaaaLogin)
        assertEquals("环节：iAAA 登录", d)
    }

    @Test fun `detail never contains credentials`() {
        val d = GbuDiagnostics.detailOf(
            GbuException.ApiError.Stage.IaaaLogin,
            url = "https://iaaa.example.edu.cn/iaaa/oauthlogin.do",
            httpStatus = 200,
            snippet = "{\"success\":false}",
        )
        assertFalse(d.contains("password"))
        assertFalse(d.contains("userName"))
    }

    @Test fun `report appends version and time`() {
        val r = GbuDiagnostics.reportOf(
            message = "课表接口返回 HTTP 404（教务地址或学期可能不正确）",
            detail = "环节：教务接口",
            versionName = "0.0.6",
            timestampMillis = 1_700_000_000_000L,
        )
        assertTrue(r.startsWith("课表接口返回 HTTP 404（教务地址或学期可能不正确）\n\n环节：教务接口"))
        assertTrue(r.contains("版本：0.0.6"))
        assertTrue(r.lines().last().startsWith("时间："))
    }

    @Test fun `report without detail still carries version`() {
        val r = GbuDiagnostics.reportOf("网络错误", null, "0.0.6", 1_700_000_000_000L)
        assertTrue(r.startsWith("网络错误\n\n版本：0.0.6"))
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run（持久 terminal）：`.\gradlew.bat :app:testDebugUnitTest --tests "me.huanlin.gbuca.GbuDiagnosticsTest"`
Expected: FAIL —— 编译错误 `Unresolved reference: GbuDiagnostics` / `Stage`

- [x] **Step 3: 写实现**

```kotlin
package me.huanlin.gbuca.data

/**
 * 失败诊断文本的纯函数集合：无 Android 依赖，可直接 JVM 单测。
 *
 * 只处理**响应**片段，永不接触请求表单与凭据 —— 隐私约定见 README。
 */
object GbuDiagnostics {

    /** 响应片段保留的最大字符数。 */
    const val SNIPPET_MAX = 400

    /**
     * 规范化响应片段：去控制字符、折叠连续空白、去首尾空白、超长截断加省略号。
     * 空白或 null 返回 null（调用方据此省略整行）。
     */
    fun normalizeSnippet(raw: String?, max: Int = SNIPPET_MAX): String? {
        if (raw == null) return null
        val cleaned = buildString(raw.length) {
            raw.forEach { c -> if (!c.isISOControl() || c == '\n' || c == '\t') append(c) }
        }
        val collapsed = cleaned.replace(Regex("\\s+"), " ").trim()
        if (collapsed.isEmpty()) return null
        return if (collapsed.length <= max) collapsed else collapsed.take(max) + "…"
    }

    /**
     * 技术细节多行文本；空字段整行省略，永不含请求参数与凭据。形如：
     * ```
     * 环节：iAAA 登录
     * 地址：https://iaaa.example.edu.cn/iaaa/oauthlogin.do
     * HTTP：404
     * 响应片段：<!DOCTYPE html>…
     * ```
     */
    fun detailOf(
        stage: GbuException.ApiError.Stage,
        url: String? = null,
        httpStatus: Int? = null,
        snippet: String? = null,
    ): String = buildList {
        add("环节：${stage.label}")
        url?.takeIf { it.isNotBlank() }?.let { add("地址：$it") }
        httpStatus?.let { add("HTTP：$it") }
        normalizeSnippet(snippet)?.let { add("响应片段：$it") }
    }.joinToString("\n")

    /** 「复制错误详情」的完整报告：用户可读消息 + 技术细节 + 版本与时间。 */
    fun reportOf(
        message: String,
        detail: String?,
        versionName: String,
        timestampMillis: Long,
    ): String = buildString {
        append(message)
        detail?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
        append("\n\n版本：").append(versionName)
        append("\n时间：").append(
            java.time.Instant.ofEpochMilli(timestampMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        )
    }
}
```

- [x] **Step 4: 跑测试确认通过**

Run：`.\gradlew.bat :app:testDebugUnitTest --tests "me.huanlin.gbuca.GbuDiagnosticsTest"`
Expected: PASS（10 个用例）

- [x] **Step 5: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/data/GbuDiagnostics.kt app/src/test/java/me/huanlin/gbuca/GbuDiagnosticsTest.kt
git commit -m "feat(diag): 新增失败诊断文本纯函数与单测"
```

---

### Task 2: GbuException.ApiError 结构化

**Files:**
- Modify: `app/src/main/java/me/huanlin/gbuca/data/GbuException.kt`（整文件替换，共 10 行）
- Test: `app/src/test/java/me/huanlin/gbuca/GbuDiagnosticsTest.kt`（追加用例）

**Interfaces:**
- Consumes: `GbuDiagnostics.detailOf(...)`（Task 1）
- Produces:
  - `GbuException.ApiError(stage: Stage, summary: String, url: String? = null, httpStatus: Int? = null, snippet: String? = null)`
  - `GbuException.ApiError.Stage { IaaaLogin, JwxtSession, CourseApi, Parse }`，每项带 `label: String`
  - `GbuException.ApiError.detail: String`
  - `message` == `summary`（不再有 `body` 字段）

- [x] **Step 1: 追加失败测试**

在 `GbuDiagnosticsTest` 末尾追加：

```kotlin
    @Test fun `api error message equals summary and detail is built`() {
        val e = GbuException.ApiError(
            stage = GbuException.ApiError.Stage.Parse,
            summary = "课表接口返回的数据无法解析（接口可能已变更）",
            url = "https://jwxt.example.edu.cn/Xsxk/queryYxkc",
            httpStatus = 200,
            snippet = "not-json",
        )
        assertEquals("课表接口返回的数据无法解析（接口可能已变更）", e.message)
        assertTrue(e.detail.contains("环节：数据解析"))
        assertTrue(e.detail.contains("响应片段：not-json"))
    }

    @Test fun `api error stage labels are user facing`() {
        assertEquals("iAAA 登录", GbuException.ApiError.Stage.IaaaLogin.label)
        assertEquals("教务会话", GbuException.ApiError.Stage.JwxtSession.label)
        assertEquals("教务接口", GbuException.ApiError.Stage.CourseApi.label)
        assertEquals("数据解析", GbuException.ApiError.Stage.Parse.label)
    }
```

- [x] **Step 2: 跑测试确认失败**

Run：`.\gradlew.bat :app:testDebugUnitTest --tests "me.huanlin.gbuca.GbuDiagnosticsTest"`
Expected: FAIL —— `Too many arguments for constructor ApiError` / `Unresolved reference: Stage`

- [x] **Step 3: 替换 GbuException.kt 全文**

```kotlin
package me.huanlin.gbuca.data

sealed class GbuException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable) : GbuException("网络错误: ${cause.message}", cause)
    class BadCredentials(val message0: String) : GbuException(message0)
    class NeedCaptcha : GbuException("需要验证码，请改用网页登录")
    class NeedSms : GbuException("需要短信验证码，请改用网页登录")
    class SessionExpired : GbuException("会话已过期")

    /**
     * 接口 / 协议层失败。
     *
     * [summary] 面向用户：说明失败环节与最可能原因，直接展示，不套「同步失败：」前缀。
     * [detail] 面向排查：环节 / 地址 / HTTP 状态 / 响应片段，**永不含请求表单与凭据**。
     */
    class ApiError(
        val stage: Stage,
        val summary: String,
        val url: String? = null,
        val httpStatus: Int? = null,
        val snippet: String? = null,
    ) : GbuException(summary) {

        /** 失败环节；[label] 直接进用户可见的诊断文本。 */
        enum class Stage(val label: String) {
            IaaaLogin("iAAA 登录"),
            JwxtSession("教务会话"),
            CourseApi("教务接口"),
            Parse("数据解析"),
        }

        /** 技术细节（多行）；由纯函数生成，可单测。 */
        val detail: String get() = GbuDiagnostics.detailOf(stage, url, httpStatus, snippet)
    }
}
```

- [x] **Step 4: 跑测试确认通过**

Run：`.\gradlew.bat :app:testDebugUnitTest --tests "me.huanlin.gbuca.GbuDiagnosticsTest"`
Expected: PASS（12 个用例）

- [x] **Step 5: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/data/GbuException.kt app/src/test/java/me/huanlin/gbuca/GbuDiagnosticsTest.kt
git commit -m "feat(diag): ApiError 结构化（环节/摘要/地址/状态/响应片段）"
```

---

### Task 3: 六个抛点改造

**Files:**
- Modify: `app/src/main/java/me/huanlin/gbuca/data/remote/GbuClient.kt:74-126`（login）、`169-188`（parseApiResponse）
- Modify: `app/src/main/java/me/huanlin/gbuca/data/repo/CourseRepository.kt:111-114`

**Interfaces:**
- Consumes: `GbuException.ApiError(...)`（Task 2）
- Produces: 无新接口；所有 `ApiError` 抛点都带 `stage` + 可读 `summary` + 技术字段

- [x] **Step 1: 改造 login 的 iAAA 响应解析**

把 `GbuClient.kt` 中：

```kotlin
        val body = apiClient.newCall(req).execute().use { resp ->
            resp.body?.string() ?: throw GbuException.Network(java.io.IOException("空响应"))
        }
        // 隐私约定：不记录响应内容（含 token / 密码相关字段）到日志
        val obj: JsonObject = runCatching { json.parseToJsonElement(body) as? JsonObject }
            .getOrElse { throw GbuException.ApiError("iAAA 响应异常: ${body.take(120)}") }
            ?: throw GbuException.ApiError("iAAA 响应异常: ${body.take(120)}")
```

替换为：

```kotlin
        val (httpCode, body) = apiClient.newCall(req).execute().use { resp ->
            resp.code to (resp.body?.string() ?: throw GbuException.Network(java.io.IOException("空响应")))
        }
        // 隐私约定：不记录响应内容（含 token / 密码相关字段）到日志
        val obj: JsonObject = runCatching { json.parseToJsonElement(body) as? JsonObject }
            .getOrElse { throw iaaaNotJson(body, httpCode) }
            ?: throw iaaaNotJson(body, httpCode)
```

- [x] **Step 2: 改造无 token 与教务会话两处抛点**

把：

```kotlin
        val token = (obj["token"] as? JsonPrimitive)?.content
            ?: throw GbuException.ApiError("iAAA 未返回 token")
```

替换为：

```kotlin
        val token = (obj["token"] as? JsonPrimitive)?.content
            ?: throw GbuException.ApiError(
                stage = GbuException.ApiError.Stage.IaaaLogin,
                summary = "认证服务器未返回登录令牌（认证地址可能不正确）",
                url = "${endpoints.iaaaBase}oauthlogin.do",
                httpStatus = httpCode,
                snippet = body,
            )
```

把：

```kotlin
        loginClient.newCall(codeReq).execute().use { resp ->
            if (!cookieJar.hasJwxtSession() && !resp.request.url.encodedPath.contains("authentication")) {
                throw GbuException.ApiError("教务系统会话建立失败")
            }
        }
```

替换为：

```kotlin
        loginClient.newCall(codeReq).execute().use { resp ->
            if (!cookieJar.hasJwxtSession() && !resp.request.url.encodedPath.contains("authentication")) {
                val e = GbuException.ApiError(
                    stage = GbuException.ApiError.Stage.JwxtSession,
                    summary = "教务系统会话建立失败（教务地址可能不正确，或需要网页登录）",
                    url = resp.request.url.toString(),
                    httpStatus = resp.code,
                    snippet = runCatching { resp.body?.string() }.getOrNull(),
                )
                android.util.Log.w("GbuClient", e.detail)
                throw e
            }
        }
```

- [x] **Step 3: 新增 iaaaNotJson 私有辅助函数**

在 `GbuClient` 内、`parseApiResponse` 之前插入：

```kotlin
    /** iAAA 返回的不是 JSON：通常是认证地址填错，拿到了 404 或门户首页 HTML。 */
    private fun iaaaNotJson(body: String, httpCode: Int) = GbuException.ApiError(
        stage = GbuException.ApiError.Stage.IaaaLogin,
        summary = "认证接口返回的不是登录结果（认证地址可能不正确）",
        url = "${endpoints.iaaaBase}oauthlogin.do",
        httpStatus = httpCode,
        snippet = body,
    )
```

- [x] **Step 4: 改造 parseApiResponse 全文**

```kotlin
    private fun <T> parseApiResponse(resp: Response, parse: (String) -> T): T {
        val url = resp.request.url.toString()
        if (resp.isRedirect) {
            android.util.Log.w("GbuClient", "302 redirect → 会话过期 url=$url")
            throw GbuException.SessionExpired()
        }
        val text = resp.body?.string() ?: throw GbuException.Network(java.io.IOException("空响应"))
        if (!resp.isSuccessful) {
            val e = GbuException.ApiError(
                stage = GbuException.ApiError.Stage.CourseApi,
                summary = "教务接口返回 HTTP ${resp.code}（教务地址或学期可能不正确）",
                url = url,
                httpStatus = resp.code,
                snippet = text,
            )
            android.util.Log.w("GbuClient", e.detail)
            throw e
        }
        val trimmed = text.trimStart()
        if (trimmed.startsWith("<")) {
            android.util.Log.w("GbuClient", "返回 HTML（登录页）→ 会话过期 url=$url")
            throw GbuException.SessionExpired()
        }
        return runCatching { parse(text) }.getOrElse { cause ->
            val e = GbuException.ApiError(
                stage = GbuException.ApiError.Stage.Parse,
                summary = "教务接口返回的数据无法解析（接口可能已变更）",
                url = url,
                httpStatus = resp.code,
                snippet = text,
            )
            android.util.Log.w("GbuClient", e.detail, cause)
            throw e
        }
    }
```

- [x] **Step 5: 改造 CourseRepository 学期格式抛点**

把 `CourseRepository.kt`：

```kotlin
            ?: throw GbuException.ApiError("学期格式异常: $xnxq")
```

替换为：

```kotlin
            ?: throw GbuException.ApiError(
                stage = GbuException.ApiError.Stage.Parse,
                summary = "学期格式异常：$xnxq",
            )
```

- [x] **Step 6: 编译 + 全量单测**

Run：`.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全部既有测试通过

- [x] **Step 7: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/data/remote/GbuClient.kt app/src/main/java/me/huanlin/gbuca/data/repo/CourseRepository.kt
git commit -m "fix(diag): 六处接口失败抛点补齐环节与响应片段"
```

---

### Task 4: AppViewModel 错误详情 + saveCredentialsAndLogin

**Files:**
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/AppViewModel.kt`（UiState / friendlyError / sync / login / calibrate / saveCredentials）
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/SettingsScreen.kt:70-72,132-155`（调用点最小切换，保证可编译）
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `GbuException.ApiError.summary` / `.detail`、`GbuDiagnostics.reportOf(...)`、`BuildConfig.VERSION_NAME`
- Produces:
  - `AppViewModel.UiState.errorDetail: String?`、`AppViewModel.UiState.messageOk: Boolean?`（`calibrateOk` 删除）
  - `AppViewModel.saveCredentialsAndLogin(u: String, p: String)`（`saveCredentials` 删除）
  - 字符串资源 `msg_credentials_saved`、`msg_credentials_saved_sync_failed`

- [x] **Step 1: strings.xml 追加三条文案**

在 `<!-- 设置 -->` 段落内 `settings_sync_only` 之后插入：

```xml
    <string name="msg_credentials_saved">已保存并登录，同步到 %1$d 门课程</string>
    <string name="msg_credentials_saved_sync_failed">已保存并登录，但同步失败：%1$s</string>
```

在 `<!-- 通用 -->` 段落内 `no_room` 之后插入：

```xml
    <string name="common_copy_error_detail">复制错误详情</string>
```

- [x] **Step 2: 改造 UiState**

把：

```kotlin
    data class UiState(
        val syncing: Boolean = false,
        val message: String? = null,
        val needWebLogin: Boolean = false,
        /** 学期校准消息语义：true=成功（主色）、false=失败（错误色）、null=其他消息。 */
        val calibrateOk: Boolean? = null,
        /** 导出到日历的独立提示（不与同步/校准消息串台）。 */
        val exportMessage: String? = null,
        val exportOk: Boolean? = null,
    )
```

替换为：

```kotlin
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
```

- [x] **Step 3: 改造 friendlyError 并新增 errorDetailOf**

把 `friendlyError` 全文替换为：

```kotlin
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
```

新增 import：`me.huanlin.gbuca.BuildConfig`、`me.huanlin.gbuca.data.GbuDiagnostics`。

- [x] **Step 4: sync() 带上 errorDetail / messageOk**

把 `sync()` 中：

```kotlin
            ui.value = ui.value.copy(syncing = true, message = null, calibrateOk = null)
```

替换为：

```kotlin
            ui.value = ui.value.copy(syncing = true, message = null, errorDetail = null, messageOk = null)
```

把成功分支：

```kotlin
                ui.value.copy(
                    syncing = false,
                    message = app.getString(R.string.msg_synced_courses, result.getOrThrow().courseCount),
                )
```

替换为：

```kotlin
                ui.value.copy(
                    syncing = false,
                    message = app.getString(R.string.msg_synced_courses, result.getOrThrow().courseCount),
                    messageOk = true,
                )
```

把失败分支：

```kotlin
                ui.value.copy(
                    syncing = false,
                    message = friendlyError(e),
                    needWebLogin = e is GbuException.NeedCaptcha || e is GbuException.NeedSms,
                )
```

替换为：

```kotlin
                ui.value.copy(
                    syncing = false,
                    message = friendlyError(e),
                    errorDetail = errorDetailOf(e),
                    messageOk = false,
                    needWebLogin = e is GbuException.NeedCaptcha || e is GbuException.NeedSms,
                )
```

- [x] **Step 5: login() 带上 errorDetail / messageOk**

把 `ui.value = ui.value.copy(syncing = true, message = null, needWebLogin = false, calibrateOk = null)`
替换为 `ui.value = ui.value.copy(syncing = true, message = null, errorDetail = null, messageOk = null, needWebLogin = false)`。

把：

```kotlin
            ui.value = ui.value.copy(
                syncing = false,
                // 仅失败时提示：成功时若仍调 friendlyError(null) 会渲染成「同步失败：?」
                message = e?.let { friendlyError(it) },
                needWebLogin = e is GbuException.NeedCaptcha || e is GbuException.NeedSms,
            )
```

替换为：

```kotlin
            ui.value = ui.value.copy(
                syncing = false,
                // 仅失败时提示：成功时若仍调 friendlyError(null) 会渲染成「同步失败：?」
                message = e?.let { friendlyError(it) },
                errorDetail = errorDetailOf(e),
                messageOk = if (e == null) null else false,
                needWebLogin = e is GbuException.NeedCaptcha || e is GbuException.NeedSms,
            )
```

- [x] **Step 6: 用 saveCredentialsAndLogin 替换 saveCredentials**

删除：

```kotlin
    fun saveCredentials(u: String, p: String) {
        app.creds.save(u, p)
    }
```

在其位置新增：

```kotlin
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
```

- [x] **Step 7: calibrateSemesterStartFromServer 去掉 calibrateOk**

把：

```kotlin
            ui.value = ui.value.copy(syncing = true, message = null, calibrateOk = null)
```

替换为：

```kotlin
            ui.value = ui.value.copy(syncing = true, message = null, errorDetail = null, messageOk = null)
```

把：

```kotlin
                calibrateOk = if (date != null) true else false,
```

替换为：

```kotlin
                errorDetail = errorDetailOf(e),
                messageOk = date != null,
```

- [x] **Step 8: SettingsScreen 调用点最小切换（保证编译）**

把 `SettingsScreen.kt:70-72`：

```kotlin
    var username by rememberSaveable { mutableStateOf(GbuCaApp.instance.creds.username ?: "") }
    var password by remember { mutableStateOf("") }
    var savedTick by remember { mutableIntStateOf(0) }
```

替换为（同时删掉死变量 `savedTick`，并移除 `mutableIntStateOf` import）：

```kotlin
    var username by rememberSaveable { mutableStateOf(GbuCaApp.instance.creds.username ?: "") }
    var password by remember { mutableStateOf("") }
```

把 `SettingsScreen.kt:133-140` 的按钮体：

```kotlin
                Button(onClick = {
                    if (username.isNotBlank()) {
                        vm.saveCredentials(username, password.ifBlank {
                            GbuCaApp.instance.creds.password ?: ""
                        })
                        savedTick++
                        vm.sync()
                    }
                }, enabled = !ui.syncing) {
```

替换为：

```kotlin
                Button(onClick = {
                    val u = username.trim()
                    if (u.isNotBlank()) {
                        vm.saveCredentialsAndLogin(
                            u,
                            password.ifBlank { GbuCaApp.instance.creds.password ?: "" },
                        )
                    }
                }, enabled = !ui.syncing) {
```

- [x] **Step 9: 编译**

Run：`.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [x] **Step 10: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/ui/AppViewModel.kt app/src/main/java/me/huanlin/gbuca/ui/SettingsScreen.kt app/src/main/res/values/strings.xml
git commit -m "feat(auth): 保存并登录改为先验证后落盘；UiState 增加错误详情"
```

---

### Task 5: ErrorMessage 组件 + 三处接线

**Files:**
- Create: `app/src/main/java/me/huanlin/gbuca/ui/components/ErrorMessage.kt`
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/LoginScreen.kt:129-136`
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/SetupFlowScreen.kt:367-379`
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/SettingsScreen.kt:156-162`、`205-211`

**Interfaces:**
- Consumes: `AppViewModel.UiState.message / errorDetail / messageOk`（Task 4）
- Produces: `@Composable fun ErrorMessage(message: String?, modifier: Modifier = Modifier, detail: String? = null, ok: Boolean? = null)`（`modifier` 必须是第一个可选参数，否则 lint `ModifierParameter` 报警）

- [x] **Step 1: 新建 ErrorMessage.kt**

```kotlin
package me.huanlin.gbuca.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.huanlin.gbuca.R

/**
 * 统一的提示文本：默认错误色，[ok] == true 时用主色。
 *
 * [detail] 非空时提供「复制错误详情」按钮 —— 把可定位的环节/地址/HTTP 状态/响应片段
 * 一次性交给用户，便于反馈给开发者。使用框架 ClipboardManager（不依赖已弃用的
 * Compose 剪贴板 API）；Android 13+ 复制后由系统自行提示。
 */
@Composable
fun ErrorMessage(
    message: String?,
    detail: String? = null,
    ok: Boolean? = null,
    modifier: Modifier = Modifier,
) {
    if (message == null) return
    val context = LocalContext.current
    Column(modifier) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        if (detail != null) {
            TextButton(onClick = { context.copyToClipboard(detail) }) {
                Text(stringResource(R.string.common_copy_error_detail))
            }
        }
    }
}

private fun Context.copyToClipboard(text: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("gbuca-error-detail", text))
}
```

- [x] **Step 2: LoginScreen 接线**

把：

```kotlin
        ui.message?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
```

替换为：

```kotlin
        if (ui.message != null) Spacer(Modifier.height(8.dp))
        ErrorMessage(ui.message, ui.errorDetail, ui.messageOk)
```

新增 import：`me.huanlin.gbuca.ui.components.ErrorMessage`。

- [x] **Step 3: SetupFlowScreen 登录步接线**

把 `LoginStep` 中：

```kotlin
        ui.message?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            if (onPrevious != null) {
                TextButton(onClick = onPrevious) {
                    Text(stringResource(R.string.oobe_login_back_to_address))
                }
            }
        }
```

替换为：

```kotlin
        if (ui.message != null) Spacer(Modifier.height(8.dp))
        ErrorMessage(ui.message, ui.errorDetail, ui.messageOk)
        if (ui.message != null && onPrevious != null) {
            TextButton(onClick = onPrevious) {
                Text(stringResource(R.string.oobe_login_back_to_address))
            }
        }
```

新增 import：`me.huanlin.gbuca.ui.components.ErrorMessage`。

- [x] **Step 4: SettingsScreen 两处接线**

账号卡片内把：

```kotlin
            ui.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
```

替换为：

```kotlin
            ErrorMessage(ui.message, ui.errorDetail, ui.messageOk)
```

学期卡片内把：

```kotlin
            ui.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.calibrateOk == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
```

替换为：

```kotlin
            ErrorMessage(ui.message, ui.errorDetail, ui.messageOk)
```

新增 import：`me.huanlin.gbuca.ui.components.ErrorMessage`。

- [x] **Step 5: 编译 + lint**

Run：`.\gradlew.bat :app:compileDebugKotlin` → BUILD SUCCESSFUL
Run：`.\gradlew.bat :app:lintDebug` → 无新增 error

- [x] **Step 6: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/ui/components/ErrorMessage.kt app/src/main/java/me/huanlin/gbuca/ui/LoginScreen.kt app/src/main/java/me/huanlin/gbuca/ui/SetupFlowScreen.kt app/src/main/java/me/huanlin/gbuca/ui/SettingsScreen.kt
git commit -m "feat(ui): 共用 ErrorMessage 组件（主色/错误色 + 复制错误详情）"
```

---

### Task 6: 设置页「保存并登录」交互完善

**Files:**
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/SettingsScreen.kt`（账号卡片）
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `AppViewModel.saveCredentialsAndLogin(u, p)`、`ErrorMessage`、`GbuCaApp.instance.creds`
- Produces: 字符串资源 `settings_password_required_for_new_id`

- [x] **Step 1: 新增字符串**

在 `settings_password_keep` 之后插入：

```xml
    <string name="settings_password_required_for_new_id">更改学号后需重新输入密码</string>
```

- [x] **Step 2: 密码输入框 label 按「是否已存密码」判断**

把：

```kotlin
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (username.isBlank()) stringResource(R.string.login_password) else stringResource(R.string.settings_password_keep)) },
```

替换为：

```kotlin
            val hasSavedPassword = remember { GbuCaApp.instance.creds.password != null }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = {
                    Text(
                        if (hasSavedPassword) stringResource(R.string.settings_password_keep)
                        else stringResource(R.string.login_password)
                    )
                },
```

- [x] **Step 3: 学号变更 + 密码留空就地拦截**

把 Task 4 改过的按钮体：

```kotlin
                Button(onClick = {
                    val u = username.trim()
                    if (u.isNotBlank()) {
                        vm.saveCredentialsAndLogin(
                            u,
                            password.ifBlank { GbuCaApp.instance.creds.password ?: "" },
                        )
                    }
                }, enabled = !ui.syncing) {
```

替换为：

```kotlin
                Button(onClick = {
                    val u = username.trim()
                    val savedId = GbuCaApp.instance.creds.username.orEmpty()
                    when {
                        u.isBlank() -> Unit
                        // 学号变了却沿用旧密码，几乎必然登录失败：就地拦截
                        password.isBlank() && u != savedId -> guardMessage = needPasswordHint
                        else -> {
                            guardMessage = null
                            vm.saveCredentialsAndLogin(
                                u,
                                password.ifBlank { GbuCaApp.instance.creds.password ?: "" },
                            )
                        }
                    }
                }, enabled = !ui.syncing) {
```

并在 `SettingsCard` 内、密码输入框之前声明：

```kotlin
            val needPasswordHint = stringResource(R.string.settings_password_required_for_new_id)
            var guardMessage by remember { mutableStateOf<String?>(null) }
```

在按钮 `Row { ... }` 之后、`ErrorMessage(...)` 之前插入：

```kotlin
            guardMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
```

- [x] **Step 4: 编译 + lint**

Run：`.\gradlew.bat :app:compileDebugKotlin` → BUILD SUCCESSFUL
Run：`.\gradlew.bat :app:lintDebug` → 无新增 error

- [x] **Step 5: 提交**

```bash
git add app/src/main/java/me/huanlin/gbuca/ui/SettingsScreen.kt app/src/main/res/values/strings.xml
git commit -m "fix(settings): 学号变更需重输密码；密码框标签按已存密码判断"
```

---

### Task 7: 全量验证

**Files:** 无新增/修改（只跑验证）

**Interfaces:**
- Consumes: 前六个 Task 的全部改动
- Produces: 可发布的验证结论

- [x] **Step 1: 全量单测**

Run：`.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，`GbuDiagnosticsTest` 12 个用例 + 既有测试全绿

- [x] **Step 2: 打包**

Run：`.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [x] **Step 3: lint**

Run：`.\gradlew.bat :app:lintDebug`
Expected: 无新增 error

- [ ] **Step 4: 真机无破坏性验证（需用户在场）**

安装 debug 包后，在 OOBE 地址步把**认证地址**填成 `example.com`（可正常解析但非 iAAA）：

- 期望：登录页显示「认证接口返回的不是登录结果（认证地址可能不正确）」
- 期望：出现「复制错误详情」按钮，复制内容含 `环节：iAAA 登录` / `地址：https://example.com/iaaa/oauthlogin.do` / `HTTP：...` / `响应片段：...` / `版本` / `时间`
- 期望：`adb logcat -s GbuClient` 能看到同样的 detail

- [ ] **Step 5: 真机凭据验证（需用户明确同意后再动）**

设置页把学号改成错误值 + 输入错误密码 → 点「保存并登录」：

- 期望：提示登录失败，**且已存凭据未被覆盖**（随后「仅同步」仍能成功，因为旧会话/旧凭据未变）

设置页改回正确学号 + 正确密码 → 点「保存并登录」：

- 期望：提示「已保存并登录，同步到 N 门课程」（主色）
- 期望：`adb logcat -s GbuClient` 出现一次新的 iAAA 登录（证明真的用了新凭据，而不是复用旧会话）

- [x] **Step 6: 更新 README（如需要）**

README 的隐私说明无需改动（未新增任何外发数据）；若其中列有错误提示说明，同步补充「可复制错误详情」。无则跳过。

- [x] **Step 7: 提交验证记录**

```bash
git add -A
git commit -m "chore: 登录诊断与凭据刷新验证通过"
```

---

---

### Task 8: 修复「今日页吞掉其他页消息」（验证中发现）

**Files:**
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/AppViewModel.kt`（`UiState.snackbar` / `sync()` / `clearMessage` → `clearSnackbar`）
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/TodayScreen.kt`（消费 `snackbar` 而非 `message`）

**问题：** `AppNavHost` 的 `HorizontalPager` 设了 `beyondViewportPageCount = tabs.size - 1`，
三个页面常驻组合；`TodayScreen` 的 `LaunchedEffect(ui.message)` 会把**任何**页面的消息
（包括设置页/登录页的错误提示）拿去做 snackbar，并在约 4 秒后 `clearMessage()`。
结果：错误提示只显示几秒就消失 —— 用户反馈「压根没有助于修复的信息」也有这一层原因，
且新增的「复制错误详情」按钮会在用户来得及点之前消失。

**修复：** `UiState` 新增 `snackbar: String?`（今日页专用瞬时消息）；`sync()` 同时写
`message` 与 `snackbar`；`TodayScreen` 只消费 `snackbar` 并调 `clearSnackbar()`；
登录 / 设置 / 校准的消息只写 `message`，常驻到下一次操作。

- [x] **Step 1: 改造 UiState + sync + clearSnackbar**

```kotlin
        /**
         * 今日页专用瞬时消息（snackbar），显示后即清除。
         * 与 [message] 分离：三个页面常驻组合，若共用同一字段，今日页会把
         * 设置页/登录页的常驻错误提示抢走并在几秒后清空。
         */
        val snackbar: String? = null,
```

`sync()` 成功/失败分支分别补 `snackbar = msg`；`clearMessage()` 改为：

```kotlin
    fun clearSnackbar() {
        ui.value = ui.value.copy(snackbar = null)
    }
```

- [x] **Step 2: TodayScreen 消费 snackbar**

```kotlin
    LaunchedEffect(ui.snackbar) {
        ui.snackbar?.let {
            snackbar.showSnackbar(it)
            vm.clearSnackbar()
        }
    }
```

- [x] **Step 3: 单测 + lint + 真机复验**

Run：`.\gradlew.bat :app:testDebugUnitTest :app:lintDebug`
Expected: 全部 PASS / 无新增 error
真机：填错密码 → 错误提示在 T+3s 与 T+15s 均仍在（修复前 T+5s 已消失）；今日页「同步」snackbar 正常。

- [x] **Step 4: 提交**

```bash
git commit -m "fix(ui): 今日页不再吞掉设置/登录页的常驻错误提示（snackbar 字段分离）"
```

---

## 验证记录（2026-09-08，真机 PJF110 / Android 16）

| 项 | 结果 |
| --- | --- |
| `:app:testDebugUnitTest` | 11 个测试类全绿，含新增 `GbuDiagnosticsTest` 12 例 |
| `:app:assembleDebug` / `:app:assembleRelease` | BUILD SUCCESSFUL |
| `:app:lintDebug` | 改动文件 0 问题（全库仅 2 个既有 warning） |
| release 包 `adb install -r` 升级 | Success，签名一致，**数据与凭据保留** |
| 升级后「同步」 | 「已同步 11 门课程」（今日页 snackbar 正常） |
| 设置页填错密码 →「保存并登录」 | 显示「登录失败：User ID or Password is NOT correct.」，**T+15s 仍常驻**（修复前约 4s 被清空） |
| 失败后凭据未被覆盖 | 代码级结构性保证：`login` 抛异常时 `creds.save` 不会执行；设备侧未做强制掉线复验（需改地址，未获授权） |
| ApiError 文案 + 复制按钮真机复验 | **未做** —— 需临时把认证地址改成非 iAAA 主机再改回，未获授权 |

## 自检记录

- **Spec 覆盖**：诊断可见（Task 1/2/3/5）、凭据先验证后落盘（Task 4/6）、测试与手工验证（Task 1/2/7）、文档（设计文档已提交，本计划）—— 无遗漏。
- **类型一致性**：`ApiError(stage, summary, url, httpStatus, snippet)` 在 Task 2 定义，Task 3/4 使用一致；`UiState.errorDetail` / `messageOk` 在 Task 4 定义，Task 5 使用一致；`calibrateOk` 在 Task 4 全量移除。
- **占位符扫描**：无 TBD / TODO / 「类似上文」；每个改动步骤都给出完整代码。
- **无测试框架的部分**（AppViewModel / Compose UI）没有伪造测试步骤，改为「纯逻辑抽到 GbuDiagnostics 并单测 + 编译/lint + 真机清单」。
