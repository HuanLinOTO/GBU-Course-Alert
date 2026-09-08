# 设计：登录/同步失败可诊断 + 设置页凭据变更立即生效

日期：2026-09-08
状态：已确认（用户审批通过）

## 背景与问题

两个用户反馈：

1. OOBE 登录时提示「同步失败：接口异常」，没有任何可据以修复的信息。
2. 设置页修改学号/密码后点「保存并同步」，实际仍使用旧凭据。

### 根因（代码级）

**问题 1：诊断信息在出口处被丢弃**

- `GbuException.kt:9` —— `class ApiError(val body: String) : GbuException("接口异常")`：
  message 是常量，真正的 `body` 无人读取。
- `AppViewModel.friendlyError()`（152-163 行）无 `ApiError` 分支 → 兜底 `error_sync_failed`
  → 渲染 `e.message` = `"接口异常"`。
- 六个抛点（iAAA 响应非 JSON / 无 token / 教务会话未建立 / HTTP 非 2xx / 解析失败 /
  学期格式）的原因在出口处全部丢失。
- 现场最可能：学生在 OOBE 里填错认证地址 → iAAA 接口返回 404 的 HTML 页面 →
  `ApiError("iAAA 响应异常: <!DOCTYPE html>…")` → 用户看到「接口异常」。

**问题 2：会话复用导致新凭据不生效**

- `SettingsScreen.kt:133-140` 保存后调用 `vm.saveCredentials()` + `vm.sync()`。
- `CourseRepository.sync()`（56 行）仅 `if (!client.hasSession)` 才登录；教务会话 Cookie
  持久化于 `files/cookies.json`（`GbuCaApp.kt:40`），重启仍在 → 旧会话有效时永不使用新凭据，
  同步照样成功并显示「已同步 N 门课程」。
- `creds.save()` 在验证之前执行 → 错误密码被永久写盘；前台因旧会话仍正常，
  后台 `SyncWorker` 从此每次静默失败。

## 需求决策记录

| 决策点 | 结论 |
| --- | --- |
| 诊断呈现方式 | 结构化异常 + 用户可读 summary + 技术 detail + 「复制错误详情」按钮 + logcat |
| ApiError 文案前缀 | 不加「同步失败：」，summary 自带环节说明 |
| snippet 范围 | 仅响应体，截断 400 字符，不含请求表单与凭据 |
| 凭据保存时机 | 先登录验证，成功才落盘（失败保留旧凭据与旧会话） |
| 密码留空语义 | 保留「留空 = 沿用已存密码」；学号变更且密码留空则拦截 |
| 明确不做 | 独立诊断页 / 日志导出 / 远端上报、自动重试、清空会话后强制重登 |

## 方案比选

### 问题 1

- **A（采用）**：结构化 `ApiError(stage, summary, url, httpStatus, snippet)` +
  纯函数 `GbuDiagnostics` 生成 detail + 复制按钮。
  收益：一次用户反馈即可定位到具体环节；成本：6 个抛点 + 3 个页面小改。
- B（否决）：仅把 `body` 拼进消息（如「同步失败：HTTP 404」）。一行改动，
  但用户仍看不懂、反馈信息不足以定位。
- C（否决）：独立诊断页 / 日志导出 / 远端上报。YAGNI，且与「数据仅存本机」的隐私声明冲突。

### 问题 2

- **A（采用）**：先验证后落盘（`login` → 成功才 `save` → `sync`）。
  失败时凭据与会话都保持原样，App 不会被打坏。
- B（否决）：先保存再强制重登（清会话 → `login`）。失败后凭据已被覆盖为错值、
  旧会话已清空，用户必须重新输入才能恢复。
- C（否决）：保存时清会话、其余不变。仍会把错误密码写盘，只是让问题提前暴露。

## 架构与数据流

```
GbuClient / CourseRepository ──throw──▶ GbuException.ApiError(stage, summary, url, status, snippet)
                                              │
                                    GbuDiagnostics.detailOf(...)   ← 纯函数，可单测
                                              │
                    AppViewModel.friendlyError() ──▶ UiState.message    （用户可读 summary）
                                              └──▶ UiState.errorDetail （技术 detail）
                                                          │
                                        ui/components/ErrorMessage.kt
                                        （红字消息 + 「复制错误详情」按钮）
                                              │
                        LoginScreen / SetupFlowScreen(LoginStep) / SettingsScreen
```

凭据变更：

```
SettingsScreen「保存并登录」
      └─▶ AppViewModel.saveCredentialsAndLogin(u, p)
                ├─ client.login(u, p)      失败 → 保留旧凭据 + 旧会话，抛错给 UI
                ├─ creds.save(u, p)        成功才落盘
                └─ repo.sync(xnxq)         会话已属于新账号
```

## 组件设计

### 1. GbuException.ApiError（`data/GbuException.kt`）

```kotlin
class ApiError(
    val stage: Stage,
    val summary: String,
    val url: String? = null,
    val httpStatus: Int? = null,
    val snippet: String? = null,
) : GbuException(summary) {
    enum class Stage(val label: String) {
        IaaaLogin("iAAA 登录"),
        JwxtSession("教务会话"),
        CourseApi("课表接口"),
        Parse("数据解析"),
    }
}
```

- `summary`：面向用户，一句话说明「哪个环节 + 最可能原因」。
- 其余字段仅供 detail，不进入 `message`（避免提示与日志串台）。

### 2. GbuDiagnostics（新增 `data/GbuDiagnostics.kt`）

- `normalizeSnippet(raw: String?, max: Int = 400): String?` —— 折叠连续空白、去控制字符、
  超长截断并加「…」。
- `detailOf(stage, url, httpStatus, snippet): String` —— 多行文本：
  `环节：` / `地址：` / `HTTP：` / `响应片段：`，空字段整行省略。
- 纯 Kotlin，无 Android 依赖，JVM 单测覆盖。

### 3. 抛点改造

| 文件:位置 | stage | summary |
| --- | --- | --- |
| `GbuClient.login` 响应非 JSON | IaaaLogin | 认证接口返回的不是登录结果（认证地址可能不正确） |
| `GbuClient.login` 无 token | IaaaLogin | 认证服务器未返回登录令牌（认证地址可能不正确） |
| `GbuClient.login` 会话未建立 | JwxtSession | 教务系统会话建立失败（教务地址可能不正确，或需要网页登录） |
| `GbuClient.parseApiResponse` HTTP 非 2xx | CourseApi | 课表接口返回 HTTP {code}（教务地址或学期可能不正确） |
| `GbuClient.parseApiResponse` 解析失败 | Parse | 课表接口返回的数据无法解析（接口可能已变更） |
| `CourseRepository` 学期格式 | Parse | 学期格式异常：{xnxq} |

`parseApiResponse` 需从 `resp.request.url` 取请求地址，并把响应片段交给异常。

### 4. AppViewModel

- `UiState` 新增 `errorDetail: String?`、`messageOk: Boolean?`。
- `friendlyError` 新增 `ApiError -> e.summary`。
- `sync()` / `login()` / `calibrateSemesterStartFromServer()` 三处失败时同时写 `errorDetail`，
  由 `GbuDiagnostics.detailOf(...)` + 版本号 + 时间戳拼装。
- 新增 `saveCredentialsAndLogin(u, p)`；删除只写不验的 `saveCredentials`。

### 5. ui/components/ErrorMessage.kt（新增）

```kotlin
@Composable
fun ErrorMessage(
    message: String?,
    modifier: Modifier = Modifier,
    detail: String? = null,
    ok: Boolean? = null,
)
```

- `message == null` 时不渲染。
- 颜色：`ok == true` 主色，否则 error 色。
- `detail != null` 时显示「复制错误详情」TextButton（`LocalClipboardManager`），
  复制内容为 `message + "\n\n" + detail`。
- 替换三处调用点：`LoginScreen`、`SetupFlowScreen`(LoginStep)、`SettingsScreen`（账号区 + 学期区）。

### 6. SettingsScreen

- 按钮改为 `vm.saveCredentialsAndLogin(username, password.ifBlank { creds.password ?: "" })`。
- 学号变更且密码留空 → 就地提示（新增 string 资源），不提交。
- 删除死变量 `savedTick`。
- 成功提示改用主色。

## 错误处理与边界

- 登录失败：不写凭据、不清会话 → 旧凭据仍可用，用户可改回。
- 登录成功但同步失败：凭据已更新（新账号确实有效），错误照常显示；
  DB 中旧账号课程保持到下次同步成功。
- 响应片段为空（如 204）：detail 省略该行，不留空行。
- snippet 可能含 HTML：不做 HTML 解析，仅折叠空白与截断（原始片段对定位更有用）。
- 日志：只记响应，不记请求表单，与「凭据不写日志」的既有约定一致。

## 测试

- 新增 `GbuDiagnosticsTest`（JVM）：折叠空白 / 截断 / 控制字符清理；detail 含环节与状态；
  空字段省略；断言不含凭据关键字。
- 既有测试保持通过（`./gradlew test`）。
- 手工验证：
  - 填错认证地址 → 提示「认证接口返回的不是登录结果…」+ 可复制详情（无破坏性）。
  - 设置页改学号/密码 → 立即用新凭据登录（真机验证需用户同意后执行）。
  - 设置页填错密码 → 报错且旧凭据仍能同步。

## 文档

- 本设计文档 + `2026-09-08-login-diagnostics-and-credential-refresh-impl.md`。
- README 隐私说明无需改动（未新增任何外发数据）。
