# 设计：教务/认证域名去硬编码，OOBE 由学生自行填写

日期：2026-09-08
状态：已确认（用户逐节审批通过）

## 背景与目标

学校要求把教务系统域名从应用中移除（连同统一认证域名），改为在 OOBE
（首次使用向导）中由学生自行填写。应用不得再内嵌任何真实学校域名。

## 需求决策记录

| 决策点 | 结论 |
| --- | --- |
| 动态化范围 | `jwxt` 与 `iaaa` 两个域名都去掉硬编码 |
| OOBE 形态 | 独立向导步（先填地址，再进登录页） |
| 输入格式 | 仅裸主机名，强制 `https://`，粘完整 URL 自动剥主机名 |
| 运行期架构 | 方案 A：SettingsStore 为唯一数据源，请求时现场解析 |
| 保留 | `APP_ID = "gbu_jwxt"`（iAAA OAuth 应用标识，非域名） |
| 明确不做 | 连通性预检、多域名配置、http 支持（YAGNI） |

## 方案比选

- **A（采用）：运行时动态解析** —— `GbuClient` 构造注入 `() -> Endpoints` 提供者，
  每请求从 SettingsStore 读当前域名。单一数据源、域名热更新、无生命周期风险；
  代价仅是每请求一次 prefs 读取（SharedPreferences 有内存缓存，纳秒级）。
- B（否决）：构造注入 + 变更重建 client/repo —— `repo`、`SyncWorker`、Widget 均持有
  client 引用，重建链路复杂易漏。
- C（否决）：BuildConfig / 远端配置下发 —— 不满足"学生自己填"。

## 架构与数据流

```
SettingsStore (jwxtHost / iaaaHost, SharedPreferences)
      │  唯一数据源，() -> Endpoints 提供者
      ├─→ PersistentCookieJar（host 感知）
      ├─→ GbuClient（每请求现场解析 URL）
      └─→ SetupScreen / SettingsScreen / WebLoginActivity（直接读取）
```

- `GbuCaApp.onCreate` 构造顺序：`settings → cookieJar(hosts 提供者) → client → repo`。
- 删除 `GbuClient` companion 中的 `IAAA_BASE` / `JWXT_BASE` / `REDIR_URL` / `webLoginUrl()`；
  `APP_ID`、`UA` 保留为 private const。
- 新增 `data class Endpoints(jwxtBase, iaaaBase, redirUrl)`；Referer 头同样动态化。

## 组件设计

### 1. SettingsStore

- 新增 `jwxtHost: String`、`iaaaHost: String`（存规范化裸主机名；空串 = 未配置）。
- 新增 `normalizeHost(input): String?`：trim 后拼 `https://` 交给 OkHttp
  `toHttpUrlOrNull()` 解析取 host；解析失败或 host 不含 `.` 返回 null。

### 2. GbuClient

- 构造参数追加 `endpoints: () -> Endpoints`。
- Referer 头、`oauth/login/code`、`Xsxk/queryYxkc`、`component/queryXnxq`、
  `component/getXnxqByRq` 的 URL 全部改为请求时取值拼接。
- `webLoginUrl()` 变为实例方法（供 `WebLoginActivity` 调用）。

### 3. PersistentCookieJar

- 构造注入 `jwxtHost: () -> String`、`iaaaHost: () -> String`。
- `hasJwxtSession()` / `saveFromResponse` 的 `contains("jwxt")` → 与当前 `jwxtHost()`
  匹配（Cookie.domain 存的是 `.jwxt.xxx` 带前导点形式，用
  `removePrefix(".").endsWith(host)` 判定）。
- `clearIaaa()` 按当前 `iaaaHost()` 匹配；`clear()` 已全清，不改。

### 4. WebLoginActivity

- `onPageFinished` 的 URL 判断改为 `HttpUrl.host == jwxtHost()` 精确比较；
  Cookie 读取与注入 URL 使用当前 `jwxtHost()`。

### 5. OOBE：SetupScreen（新建 `ui/SetupScreen.kt`）

- **门控**：`MainActivity` 增加
  `needsSetup = settings.jwxtHost.isBlank() || settings.iaaaHost.isBlank()`；
  needsSetup → SetupScreen；否则原逻辑（已登录 → AppNavHost，未登录 → LoginScreen）。
- **UI**：标题「连接你的学校」+ 说明（地址以学校公布为准）+ 两个输入框
  （教务系统地址、认证 iAAA 地址；不设 placeholder 提示，不出现任何示例域名）+「下一步」。
- **校验**：与设置页共用 `normalizeHost`；解析失败或 host 无点则 inline 报错；
  两框均有效才启用按钮。
- **保存后**：`vm.completeSetup()` 存 prefs → 回调 MainActivity：凭据存在 → 主界面，
  否则 → 登录页。旧域 Cookie 因域名失配自然失效，下次 sync 用已存凭据自动重登。

### 6. 设置页「服务器」分组

- 账号区上方新增分组：展示当前两个地址 +「修改」→ AlertDialog 编辑（同套校验）。
- 保存时 `cookieJar.clear()`（旧域会话作废），下次 sync 自动重登。

## 错误处理与边界

- 地址格式错误：Setup/设置页 inline 拦截，不放行。
- 老用户升级：检测地址为空 → 强制过一次向导；凭据保留，无感迁移。
- 运行期网络/会话错误沿用现有 `friendlyError` 与 `SessionExpired` 重登链路。

## 测试

- 新增 JVM 单测 `HostNormalizerTest`：裸主机名 / `https://` 前缀 / 带路径 /
  带端口 / 非法输入 / 无点主机名。
- 手动验证清单：首次启动向导 → 登录 → 同步；改域名后 cookie 清空 → 自动重登；
  老用户（有凭据无地址）升级后强制过向导。

## 文档

- README 隐私声明改为「仅访问用户自行配置的教务与认证地址」，移除真实域名。
- strings.xml 新增 setup 相关条目，中文文案不出现真实域名。
