# 实施计划：教务/认证域名去硬编码 + OOBE 服务器配置

依据：`docs/plans/2026-09-08-configurable-server-hosts-design.md`（已批准）

## 变更清单（按依赖顺序）

1. **`data/remote/Endpoints.kt`（新增）**
   - `data class Endpoints(jwxtHost, iaaaHost)`：派生 `jwxtBase` / `iaaaBase` / `redirUrl`。
   - `object HostNormalizer { fun normalize(raw: String): String? }`：
     trim → 无 scheme 补 `https://` → OkHttp `toHttpUrlOrNull()` 取 host → 无点判非法。

2. **`data/local/SettingsStore.kt`**
   - 新增 `jwxtHost` / `iaaaHost`（`jwxt_host` / `iaaa_host` 键，空串 = 未配置）。

3. **`data/local/PersistentCookieJar.kt`**
   - 构造注入 `jwxtHost: () -> String`、`iaaaHost: () -> String`。
   - `hasJwxtSession()` / `saveFromResponse` 的 SESSION 判断改按当前 jwxtHost 匹配
     （`domain.removePrefix(".").removeSuffix(".").endsWith(host)`）。
   - `clearIaaa()` 改按当前 iaaaHost 匹配。

4. **`data/remote/GbuClient.kt`**
   - 删 companion 中 `IAAA_BASE` / `JWXT_BASE` / `REDIR_URL` / `webLoginUrl()`；
     `APP_ID`、`UA` 改 private。
   - 构造参数 `endpoints: () -> Endpoints`；请求时拼 URL；Referer 动态化；
     `webLoginUrl()` 变实例方法；暴露 `val endpoints: Endpoints`。

5. **`GbuCaApp.kt`**：构造顺序 settings → cookieJar(hosts) → client(provider)。

6. **`ui/WebLoginActivity.kt`**：URL 判断改 `Uri.parse(url).host == endpoints.jwxtHost`；
   Cookie 读取/注入用当前域名。

7. **`ui/SetupScreen.kt`（新建）** + **`ui/AppViewModel.kt`**
   - `vm.completeSetup(jwxt, iaaa, onDone)`；校验共用 HostNormalizer。
   - MainActivity：`needsSetup`（任一地址为空）→ SetupScreen → 按凭据分流。

8. **`ui/SettingsScreen.kt` + `AppViewModel`**：新增「服务器」分组（展示 + AlertDialog 编辑；
   保存时 `cookieJar.clear()` 并触发 sync）。

9. **`res/values/strings.xml`**：setup / 服务器分组文案（不出现真实域名）。

10. **`app/src/test/.../HostNormalizerTest.kt`**：正常/带 scheme/带路径/带端口/非法/无点。

11. **`README.md`**：隐私声明去真实域名；`PLAN.md` 历史文档域名替换为示例域名。

## 验证

- `gradlew :app:testDebugUnitTest`（HostNormalizer + 既有 ScheduleParserTest）
- `gradlew :app:compileDebugKotlin` 编译通过
