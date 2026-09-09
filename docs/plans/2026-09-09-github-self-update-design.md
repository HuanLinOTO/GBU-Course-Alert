# 应用内自更新（GitHub Release）设计

日期：2026-09-09 · 状态：已定稿（用户授权免确认直接实施）

## 目标

设置页新增「应用更新」入口：手动检查 GitHub Release 最新版本，展示更新说明，下载 APK 并转交系统安装器完成升级。不做自动轮询、不做静默安装。

## 背景事实（已核实）

- 仓库：`HuanLinOTO/GBU-Course-Alert`，当前版本 `versionCode=8 / versionName=0.0.8`。
- CI（android-ci.yml）打 `v*` tag 后创建 Release，附件命名为
  `GBU-Course-Alert-vX.Y.Z-release.apk` / `-debug.apk`，`content_type` 为
  `application/vnd.android.package-archive`，资产带 `digest: "sha256:<hex>"` 字段。
- `GET https://api.github.com/repos/HuanLinOTO/GBU-Course-Alert/releases/latest`
  免认证可用（60 次/小时/IP），手动触发足够；返回 `tag_name`、`name`、`body`（Markdown 更新说明）、`assets[]`。
- 本地与 CI 同一签名密钥，release APK 可相互覆盖安装。

## 方案取舍

| 方案 | 结论 |
| --- | --- |
| A. 下载 APK → FileProvider → `ACTION_VIEW` 转交系统安装器 | ✅ 采用。FOSS 通用模式，代码量最小，系统安装器自带确认 UI 与签名校验 |
| B. PackageInstaller session API | 否。需要写 session 流 + 状态回调广播，复杂度高，用户体验与 A 无差别（同样弹系统确认框） |
| C. 只跳转 GitHub Releases 网页 | 交互割裂，无应用内下载体验 |

## 流程

```
设置页「检查更新」
  → GET api.github.com/repos/HuanLinOTO/GBU-Course-Alert/releases/latest
  → isNewerVersion(BuildConfig.VERSION_NAME, tag_name)     // 纯函数，单元测试覆盖
      ├─ 否 → 「已是最新版本」
      ├─ 404/无 release → 「仓库还没有发布版本」
      └─ 是 → pickApkAsset(assets) 选 *-release.apk（排除 debug 包：签名不同装不上）
           → 弹窗展示 tag + body（更新说明）
           → 「下载并安装」：OkHttp 流式下载到 cacheDir/update/，进度回调（0..100）
           → digest 存在时校验 SHA-256，不符即删除并报错
           → canRequestPackageInstalls()？
               ├─ 是 → ACTION_VIEW + FileProvider URI 拉起系统安装器
               └─ 否 → 「去授权」跳 ACTION_MANAGE_UNKNOWN_APP_SOURCES，回来后可点「安装」重试
```

## 状态机（AppViewModel.UpdateState）

```
Idle ──检查──▶ Checking ──┬─▶ UpToDate(version)
                          ├─▶ Failed(message)          [重试=重新检查]
                          └─▶ Available(release, asset, error?)
Available ──下载──▶ Downloading(percent) ──┬─成功─▶ Ready(file) ──自动拉起安装器
                    ▲      │               │            └─缺授权─▶ NeedInstallPermission(file)
                    │      └─失败：Available(error)      [Ready/NeedInstallPermission 均可点「安装」重装]
                    └──────── 取消 ◀────────┘
任意状态 ──「完成/收起」──▶ Idle（清理已下载安装包）
```

- `Downloading.percent < 0` 表示无 content-length，显示不确定进度条。
- 下载失败 / 校验失败回到 `Available(error=文案)`，不丢已获取的 release 信息。
- 协程取消（用户取消）按 `runCatchingNonCancellation` 既有惯例处理。

## 文件清单

- 新增 `app/src/main/java/me/huanlin/gbuca/update/GitHubRelease.kt` — `@Serializable` DTO（GitHubRelease/GitHubAsset）+ 纯函数对象 `AppUpdateChecks`（parseVersion / isNewerVersion / pickApkAsset / parseRelease）
- 新增 `app/src/main/java/me/huanlin/gbuca/update/AppUpdater.kt` — OkHttp 检查与流式下载、SHA-256 校验、FileProvider 安装 Intent、未知来源授权 Intent
- 修改 `AppViewModel.kt` — `UpdateState` 状态机 + `checkForUpdate / downloadUpdate / installUpdate / cancelDownload / dismissUpdate / installPermissionIntent`
- 修改 `SettingsScreen.kt` — 「应用更新」卡片（导出卡片与关于卡片之间）+ 更新说明弹窗
- 修改 `GbuCaApp.kt` — 装配 `updater`
- 修改 `AndroidManifest.xml` — `REQUEST_INSTALL_PACKAGES`
- 修改 `res/xml/file_paths.xml` — `<cache-path name="update" path="update/"/>`
- 修改 `strings.xml` — 更新相关文案
- 新增测试 `app/src/test/java/me/huanlin/gbuca/AppUpdateChecksTest.kt`
- 更新 `README.md` 功能列表

## 错误处理与文案

| 情形 | 文案 / 行为 |
| --- | --- |
| 网络失败（IOException） | 「检查更新失败：网络异常…」/ 下载失败显示于 Available 卡片 |
| 404（无 release） | 「仓库还没有发布版本」 |
| 无 release APK 资产（或只有 debug 包） | 「最新版本未提供可安装的 APK」 |
| SHA-256 不符 | 「安装包校验失败…已删除」，回到 Available |
| 缺「安装未知应用」授权 | 「去授权」按钮跳系统设置 |
| versionName/tag 无法解析 | 一律视为「不是更新」，绝不误弹窗 |

## 安全与隐私

- 仅访问 `api.github.com` 与 `github.com`（HTTPS），无第三方更新服务。
- 下载后校验 GitHub 官方 `digest`（sha256），不符即弃。
- 安装包缓存于 `cacheDir/update/`，单文件保留，重检时清理。
- 复用现有签名一致性：本地与 CI 同密钥，覆盖安装无障碍。

## 测试

- 单元测试（纯 JVM，JUnit4，沿用现有测试风格）：版本解析与比较（含边界：前缀 v、缺位、非数字标签）、APK 资产选择（优先 release、排除 debug、忽略非 APK）、真实 release JSON 解析与未知字段容忍。
- `testDebugUnitTest` + `assembleDebug` + `lintDebug` 与 CI 同口径验证。
