# 教务/认证域名去硬编码 + OOBE 服务器配置 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 移除代码与文档中的 `jwxt.example.edu.cn` / `iaaa.example.edu.cn` 硬编码，OOBE 向导步由学生自行填写服务器地址，设置页可随时修改。

**Architecture:** 方案 A —— `SettingsStore` 为域名唯一数据源；`GbuClient` 构造注入 `() -> Endpoints`，每请求现场解析 URL；`PersistentCookieJar` 与 `WebLoginActivity` 同样按当前配置域名工作；`MainActivity` 以「地址为空」作为 OOBE 门控。

**Tech Stack:** Kotlin / Jetpack Compose (Material3) / OkHttp / SharedPreferences / JUnit4

**Spec:** `docs/plans/2026-09-08-configurable-server-hosts-design.md`（已批准）

## Global Constraints

- 应用代码、注释、资源、README 中**不得出现**任何真实学校域名（`jwxt.example.edu.cn`、`iaaa.example.edu.cn`）；示例一律用 `jwxt.example.edu.cn` / `iaaa.example.edu.cn`
- `APP_ID = "gbu_jwxt"` 保留（iAAA OAuth 应用标识，非域名）
- 地址仅存裸主机名；URL 一律现场以 `https://` 拼接；强制 https
- 主机名校验：OkHttp `toHttpUrlOrNull()` 解析成功 **且** host 含 `.`，否则拒绝
- `APP_ID`、`UA` 均保持 `const`；不引入新依赖；不改 Room schema
- 改动服务器地址后必须 `cookieJar.clear()`（旧域会话作废）
- gradle 命令须在持久 terminal 中运行（阻塞型进程），用 `wait_for` 等待结果

---

### Task 1: Endpoints 模型 + HostNormalizer + SettingsStore 域名属性

**Files:**
- Create: `app/src/main/java/me/huanlin/gbuca/data/remote/Endpoints.kt`
- Modify: `app/src/main/java/me/huanlin/gbuca/data/local/SettingsStore.kt`
- Test: `app/src/test/java/me/huanlin/gbuca/HostNormalizerTest.kt`

**Interfaces:**
- Consumes: 无（基础层）
- Produces:
  - `data class Endpoints(jwxtHost: String, iaaaHost: String)`；派生 `jwxtBase: String`、`iaaaBase: String`（`https://<iaaa>/iaaa/`）、`redirUrl: String`
  - `object HostNormalizer { fun normalize(raw: String): String? }`
  - `SettingsStore.jwxtHost: String` / `SettingsStore.iaaaHost: String`（空串 = 未配置）

- [x] **Step 1: 创建 Endpoints.kt**（含 HostNormalizer）

```kotlin
package me.huanlin.gbuca.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 用户配置的服务端点（OOBE 填写，存于 SettingsStore），仅存裸主机名，URL 现场以 https 拼接。 */
data class Endpoints(
    val jwxtHost: String,
    val iaaaHost: String,
) {
    val jwxtBase: String get() = "https://$jwxtHost"
    val iaaaBase: String get() = "https://$iaaaHost/iaaa/"
    val redirUrl: String get() = "$jwxtBase/oauth/login/code"
}

/** 主机名规范化：接受裸主机名或完整 URL（scheme/路径/端口），剥出裸主机名；非法返回 null。 */
object HostNormalizer {

    /** 返回规范化主机名（小写），无效或不含点时返回 null。 */
    fun normalize(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val withScheme = if (s.contains("://")) s else "https://$s"
        val host = withScheme.toHttpUrlOrNull()?.host?.takeIf { it.isNotEmpty() } ?: return null
        return host.takeIf { it.contains('.') }
    }
}
```

- [x] **Step 2: SettingsStore 增加域名属性**

```kotlin
    /** 教务系统主机名（裸域名，OOBE 填写）；空 = 未配置。 */
    var jwxtHost: String
        get() = prefs.getString("jwxt_host", null)?.trim().orEmpty()
        set(v) = prefs.edit { putString("jwxt_host", v.trim()) }

    /** 统一认证（iAAA）主机名（裸域名，OOBE 填写）；空 = 未配置。 */
    var iaaaHost: String
        get() = prefs.getString("iaaa_host", null)?.trim().orEmpty()
        set(v) = prefs.edit { putString("iaaa_host", v.trim()) }
```

- [ ] **Step 3: 写 HostNormalizerTest**

```kotlin
package me.huanlin.gbuca

import me.huanlin.gbuca.data.remote.HostNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostNormalizerTest {

    @Test fun `bare hostname is kept`() =
        assertEquals("jwxt.example.edu.cn", HostNormalizer.normalize("jwxt.example.edu.cn"))

    @Test fun `whitespace trimmed`() =
        assertEquals("jwxt.example.edu.cn", HostNormalizer.normalize("  jwxt.example.edu.cn  "))

    @Test fun `scheme and path stripped`() =
        assertEquals("jwxt.example.edu.cn", HostNormalizer.normalize("https://jwxt.example.edu.cn/xsxk/zyxk"))

    @Test fun `port stripped`() =
        assertEquals("jwxt.example.edu.cn", HostNormalizer.normalize("jwxt.example.edu.cn:8443"))

    @Test fun `lowercased`() =
        assertEquals("jwxt.example.edu.cn", HostNormalizer.normalize("JWXT.Example.EDU.CN"))

    @Test fun `ipv4 accepted`() =
        assertEquals("192.168.1.10", HostNormalizer.normalize("192.168.1.10"))

    @Test fun `blank rejected`() =
        assertNull(HostNormalizer.normalize("   "))

    @Test fun `dotless host rejected`() =
        assertNull(HostNormalizer.normalize("jwxt"))

    @Test fun `garbage rejected`() =
        assertNull(HostNormalizer.normalize("ht tp://bad host"))
}
```

- [ ] **Step 4: 运行 `:app:testDebugUnitTest --tests "me.huanlin.gbuca.HostNormalizerTest"`，期望 PASS**

### Task 2: PersistentCookieJar host 感知

**Files:**
- Modify: `app/src/main/java/me/huanlin/gbuca/data/local/PersistentCookieJar.kt`

**Interfaces:**
- Consumes: `() -> String` 域名提供者（Task 4 接线时传 `{ settings.jwxtHost }` / `{ settings.iaaaHost }`）
- Produces: `PersistentCookieJar(storeFile: File, jwxtHost: () -> String, iaaaHost: () -> String)`；其余公开 API 不变

- [ ] **Step 1: 构造参数 + 匹配谓词**

```kotlin
class PersistentCookieJar(
    private val storeFile: File,
    private val jwxtHost: () -> String,
    private val iaaaHost: () -> String,
) : CookieJar {

    /** Cookie（或存储键前缀）是否属于当前配置的教务主机；domain 可能带前导点。 */
    private fun matchesJwxt(domain: String): Boolean {
        val host = jwxtHost()
        if (host.isEmpty()) return false
        return domain.removePrefix(".").removeSuffix(".") == host
    }

    private fun matchesIaaaKey(key: String): Boolean {
        val host = iaaaHost()
        if (host.isEmpty()) return false
        val domain = key.substringBefore('|')
        return domain.removePrefix(".").removeSuffix(".") == host
    }
```

- [ ] **Step 2: 替换三处硬编码**
  - `saveFromResponse`：`cookies.any { it.domain.contains("jwxt") && ... }` → `cookies.any { matchesJwxt(it.domain) && (it.name.equals("SESSION", true) || it.name.equals("JSESSIONID", true)) }`
  - `hasJwxtSession()`：`it.domain.contains("jwxt")` → `matchesJwxt(it.domain)`
  - `clearIaaa()`：`storage.keys.filter { matchesIaaaKey(it) }.forEach { storage.remove(it) }`

- [ ] **Step 3: 编译 `:app:compileDebugKotlin` 通过**

### Task 3: GbuClient 动态 Endpoints

**Files:**
- Modify: `app/src/main/java/me/huanlin/gbuca/data/remote/GbuClient.kt`

**Interfaces:**
- Consumes: `Endpoints`（Task 1）、`endpointsProvider: () -> Endpoints`
- Produces: `GbuClient(cookieJar: PersistentCookieJar, endpointsProvider: () -> Endpoints)`；`val endpoints: Endpoints`；`fun webLoginUrl(): String`（实例方法）；companion 仅剩 `const val APP_ID`（公开）与 `private const val UA`

- [ ] **Step 1: 类签名与 companion**

```kotlin
class GbuClient(
    private val cookieJar: PersistentCookieJar,
    private val endpointsProvider: () -> Endpoints,
) {

    companion object {
        /** iAAA 中教务系统的 OAuth 应用标识（非域名，保持常量）。 */
        const val APP_ID = "gbu_jwxt"

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }

    /** 当前服务端点：每次访问取最新配置，域名修改即时生效。 */
    val endpoints: Endpoints get() = endpointsProvider()
```

- [ ] **Step 2: URL 全部动态化（逐处替换）**

```kotlin
// Referer 拦截器
.header("Referer", "${endpoints.jwxtBase}/xsxk/zyxk")

// webLoginUrl（变实例方法）
fun webLoginUrl(): String {
    val e = endpoints
    return "https://${e.iaaaHost}/iaaa/oauth.jsp?appID=$APP_ID&redirectURL=" +
        java.net.URLEncoder.encode(e.redirUrl, "UTF-8") + "&appName=%E6%95%99%E5%8A%A1%E7%B3%BB%E7%BB%9F"
}

// login()：
.add("redirUrl", endpoints.redirUrl)
Request.Builder().url("${endpoints.iaaaBase}oauthlogin.do").post(form).build()
.url("${endpoints.jwxtBase}/oauth/login/code?_rand=$rand&token=$token")

// queryYxkc / queryXnxq / queryXnxqZc：
.url("${endpoints.jwxtBase}/Xsxk/queryYxkc")
.url("${endpoints.jwxtBase}/component/queryXnxq")
.url("${endpoints.jwxtBase}/component/getXnxqByRq?rq=$rq")
```

- [ ] **Step 3: KDoc 登录链路注释改为占位域名（`{jwxt}`/`{iaaa}`），不出现真实域名**
- [ ] **Step 4: 编译 `:app:compileDebugKotlin` 通过**

### Task 4: GbuCaApp 接线 + WebLoginActivity 动态域名

**Files:**
- Modify: `app/src/main/java/me/huanlin/gbuca/GbuCaApp.kt:31-42`
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/WebLoginActivity.kt:50-71`

**Interfaces:**
- Consumes: Task 1/2/3 的构造签名
- Produces: 应用级组装；`WebLoginActivity` 无常量域名

- [ ] **Step 1: GbuCaApp.onCreate 调整构造顺序**

```kotlin
settings = SettingsStore(this)
cookieJar = PersistentCookieJar(File(filesDir, "cookies.json"), { settings.jwxtHost }, { settings.iaaaHost })
client = GbuClient(cookieJar) { Endpoints(settings.jwxtHost, settings.iaaaHost) }
```

（`import me.huanlin.gbuca.data.remote.Endpoints`；`settings` 必须在 `cookieJar` 之前初始化。）

- [ ] **Step 2: WebLoginActivity.onPageFinished**

```kotlin
override fun onPageFinished(view: WebView, url: String) {
    val host = GbuCaApp.instance.client.endpoints.jwxtHost
    if (Uri.parse(url).host != host) return
    val cm = CookieManager.getInstance()
    val cookies = cm.getCookie("https://$host") ?: return
    if (Regex("(SESSION|JSESSIONID)=", RegexOption.IGNORE_CASE).containsMatchIn(cookies)) {
        // 系统 Cookie → okhttp CookieJar
        app.cookieJar.inject("https://$host/", cookies.split(";"))
        setResult(RESULT_OK)
        finish()
    }
}
```

（`import android.net.Uri`；KDoc 中「jwxt JSESSIONID」改为「教务会话 Cookie」。）

- [ ] **Step 3: 编译 `:app:compileDebugKotlin` 通过；`grep -r "example.edu.cn" app/src` 仅剩 0 处（strings.xml 文案除外，若亦无则更佳）**

### Task 5: SetupScreen（OOBE 向导步）+ MainActivity 门控

**Files:**
- Create: `app/src/main/java/me/huanlin/gbuca/ui/SetupScreen.kt`
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/AppViewModel.kt`（新增 `jwxtHost` / `iaaaHost` / `completeSetup`）
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/MainActivity.kt:22-39`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `vm.jwxtHost` / `vm.iaaaHost`（本任务定义）、`HostNormalizer.normalize`
- Produces: `SetupScreen(vm: AppViewModel, onDone: () -> Unit)`；`AppViewModel.completeSetup(jwxtHost: String, iaaaHost: String, onDone: () -> Unit)`

- [ ] **Step 1: AppViewModel 新增**

```kotlin
    // ---- 服务器地址（OOBE / 设置页） ----

    val jwxtHost: String get() = settings.jwxtHost
    val iaaaHost: String get() = settings.iaaaHost

    /** OOBE 完成：保存服务器地址（调用方已用 HostNormalizer 校验）。 */
    fun completeSetup(jwxtHost: String, iaaaHost: String, onDone: () -> Unit) {
        settings.jwxtHost = jwxtHost
        settings.iaaaHost = iaaaHost
        onDone()
    }
```

- [ ] **Step 2: 创建 SetupScreen.kt**

```kotlin
package me.huanlin.gbuca.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import me.huanlin.gbuca.R
import me.huanlin.gbuca.data.remote.HostNormalizer

/** OOBE 首步：填写教务系统与统一认证（iAAA）地址。仅裸主机名，强制 https。 */
@Composable
fun SetupScreen(vm: AppViewModel, onDone: () -> Unit) {
    var jwxt by rememberSaveable { mutableStateOf(vm.jwxtHost) }
    var iaaa by rememberSaveable { mutableStateOf(vm.iaaaHost) }
    val focus = LocalFocusManager.current
    val jHost = remember(jwxt) { HostNormalizer.normalize(jwxt) }
    val iHost = remember(iaaa) { HostNormalizer.normalize(iaaa) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(88.dp))
        Text(
            stringResource(R.string.setup_title),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.setup_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(48.dp))
        OutlinedTextField(
            value = jwxt,
            onValueChange = { jwxt = it },
            label = { Text(stringResource(R.string.setup_jwxt_label)) },
            placeholder = { Text(stringResource(R.string.setup_host_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            isError = jwxt.isNotBlank() && jHost == null,
            supportingText = { if (jwxt.isNotBlank() && jHost == null) Text(stringResource(R.string.setup_host_invalid)) },
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = iaaa,
            onValueChange = { iaaa = it },
            label = { Text(stringResource(R.string.setup_iaaa_label)) },
            placeholder = { Text(stringResource(R.string.setup_host_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                val j = jHost ?: return@KeyboardActions
                val i = iHost ?: return@KeyboardActions
                focus.clearFocus()
                vm.completeSetup(j, i) { onDone() }
            }),
            isError = iaaa.isNotBlank() && iHost == null,
            supportingText = { if (iaaa.isNotBlank() && iHost == null) Text(stringResource(R.string.setup_host_invalid)) },
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                focus.clearFocus()
                val j = jHost ?: return@Button
                val i = iHost ?: return@Button
                vm.completeSetup(j, i) { onDone() }
            },
            enabled = jHost != null && iHost != null,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text(stringResource(R.string.setup_next)) }
    }
}
```

- [ ] **Step 3: MainActivity 门控**

```kotlin
var needsSetup by rememberSaveable {
    mutableStateOf(app.settings.jwxtHost.isBlank() || app.settings.iaaaHost.isBlank())
}
when {
    needsSetup -> SetupScreen(vm) { needsSetup = false }
    loggedIn -> AppNavHost(…原样…)
    else -> LoginScreen(…原样…)
}
```

- [ ] **Step 4: strings.xml 新增（放在「登录页」段之后）**

```xml
    <!-- OOBE 服务器配置 -->
    <string name="setup_title">连接你的学校</string>
    <string name="setup_subtitle">填写学校的教务系统与统一认证（iAAA）地址，请以学校公布的信息为准。</string>
    <string name="setup_jwxt_label">教务系统地址</string>
    <string name="setup_iaaa_label">统一认证（iAAA）地址</string>
    <string name="setup_host_hint">jwxt.example.edu.cn</string>
    <string name="setup_host_invalid">地址格式不正确，请填写如 jwxt.example.edu.cn 的域名</string>
    <string name="setup_next">下一步</string>
```

- [ ] **Step 5: 编译通过；手动冒烟：清数据启动 → 显示向导 → 填错被拦 → 填对进入登录页**

### Task 6: 设置页「服务器」分组

**Files:**
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/AppViewModel.kt`（新增 `saveHosts`）
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/SettingsScreen.kt`（新增分组 + 对话框）
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `HostNormalizer`、`vm.jwxtHost` / `vm.iaaaHost`、`app.cookieJar`
- Produces: `AppViewModel.saveHosts(jwxtHost: String, iaaaHost: String)`

- [ ] **Step 1: AppViewModel.saveHosts**

```kotlin
    /** 设置页修改服务器地址：清空旧域会话 Cookie，随后自动重登并同步。 */
    fun saveHosts(jwxtHost: String, iaaaHost: String) {
        val changed = settings.jwxtHost != jwxtHost || settings.iaaaHost != iaaaHost
        settings.jwxtHost = jwxtHost
        settings.iaaaHost = iaaaHost
        if (changed) app.cookieJar.clear()
        sync()
    }
```

- [ ] **Step 2: SettingsScreen「账号」分组上方插入**

```kotlin
        // ---- 服务器 ----
        SectionTitle(stringResource(R.string.settings_section_server))
        SettingsCard {
            Text(
                stringResource(
                    R.string.settings_server_jwxt,
                    vm.jwxtHost.ifBlank { stringResource(R.string.settings_server_unset) },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    R.string.settings_server_iaaa,
                    vm.iaaaHost.ifBlank { stringResource(R.string.settings_server_unset) },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            var editingServer by rememberSaveable { mutableStateOf(false) }
            OutlinedButton(onClick = { editingServer = true }) {
                Text(stringResource(R.string.settings_server_edit))
            }
            if (editingServer) {
                ServerEditDialog(vm = vm, onDismiss = { editingServer = false })
            }
        }
```

（`editingServer` 状态按上文声明在 SettingsCard 内，不另提升。）

- [ ] **Step 3: ServerEditDialog（SettingsScreen.kt 内私有 Composable）**

```kotlin
@Composable
private fun ServerEditDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var jwxt by rememberSaveable { mutableStateOf(vm.jwxtHost) }
    var iaaa by rememberSaveable { mutableStateOf(vm.iaaaHost) }
    val j = remember(jwxt) { HostNormalizer.normalize(jwxt) }
    val i = remember(iaaa) { HostNormalizer.normalize(iaaa) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_server_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = jwxt,
                    onValueChange = { jwxt = it },
                    label = { Text(stringResource(R.string.setup_jwxt_label)) },
                    singleLine = true,
                    isError = jwxt.isNotBlank() && j == null,
                    supportingText = { if (jwxt.isNotBlank() && j == null) Text(stringResource(R.string.setup_host_invalid)) },
                )
                OutlinedTextField(
                    value = iaaa,
                    onValueChange = { iaaa = it },
                    label = { Text(stringResource(R.string.setup_iaaa_label)) },
                    singleLine = true,
                    isError = iaaa.isNotBlank() && i == null,
                    supportingText = { if (iaaa.isNotBlank() && i == null) Text(stringResource(R.string.setup_host_invalid)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = j != null && i != null,
                onClick = { vm.saveHosts(j ?: return@TextButton, i ?: return@TextButton); onDismiss() },
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
```

（新增 import：`androidx.compose.material3.AlertDialog`、`me.huanlin.gbuca.data.remote.HostNormalizer`。）

- [ ] **Step 4: strings.xml「设置」段新增**

```xml
    <string name="settings_section_server">服务器</string>
    <string name="settings_server_jwxt">教务系统：%1$s</string>
    <string name="settings_server_iaaa">统一认证：%1$s</string>
    <string name="settings_server_edit">修改服务器地址</string>
    <string name="settings_server_unset">未配置</string>
    <string name="common_cancel">取消</string>
```

- [ ] **Step 5: 编译通过**

### Task 6: 文档去域名 + 全量验证 + 提交

**Files:**
- Modify: `README.md:39`
- Modify: `PLAN.md:26,30`
- Test: 全量单测 + 编译

- [ ] **Step 1: README 隐私声明改为**

```markdown
- 仅访问用户自行配置的教务系统与认证地址（首次使用时填写，应用不内置任何学校域名）
```

- [ ] **Step 2: PLAN.md 登录链路示例中的真实域名替换为 `<教务域名>` / `<认证域名>` 占位**
- [ ] **Step 3: `grep -r "gbu\.edu\.cn" app/src README.md PLAN.md` → 期望 0 匹配**
- [ ] **Step 4: `gradlew :app:testDebugUnitTest` 全量 PASS；`gradlew :app:assembleDebug` 成功**
- [ ] **Step 5: Commit（分任务小步提交，格式 `feat: …` / `test: …` / `docs: …`）**

## Execution Notes

- gradle 长任务一律用 terminal_create 启动 + terminal_wait_for 等待（遵循会话约束）
- 每完成一个 Task 勾选对应 checkbox 并提交
