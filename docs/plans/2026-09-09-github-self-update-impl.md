# 应用内自更新（GitHub Release）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 设置页手动检查 GitHub Release、下载并安装更新（方案 A：下载 → SHA-256 校验 → FileProvider → 系统安装器）。

**Architecture:** 新增 `update` 包承载 DTO + 纯函数（可单测）与 Android 适配层（下载/安装），`AppViewModel` 持有 `UpdateState` 状态机，`SettingsScreen` 增加卡片与弹窗。复用 OkHttp / kotlinx.serialization / FileProvider 既有设施。

**Tech Stack:** Kotlin 2.4 · Compose M3 · OkHttp 5 · kotlinx.serialization · JUnit4

**Spec:** [docs/plans/2026-09-09-github-self-update-design.md](2026-09-09-github-self-update-design.md)

## Global Constraints

- 不新增第三方依赖（OkHttp + kotlinx.serialization 已在依赖中）。
- 排除 debug APK 资产（签名与正式版不同，覆盖安装必失败）。
- tag 或 versionName 无法解析时一律视为「非更新」，不弹窗。
- 遵循项目惯例：中文注释说明「为什么」、UI 文案进 `strings.xml`、隐私约定不打印敏感日志。
- minSdk 26（`canRequestPackageInstalls` 可直接用）。

---

### Task 1: DTO 与纯函数（版本比较 / 资产选择 / JSON 解析）

**Files:**
- Create: `app/src/main/java/me/huanlin/gbuca/update/GitHubRelease.kt`
- Test: `app/src/test/java/me/huanlin/gbuca/AppUpdateChecksTest.kt`

**Interfaces:**
- Produces: `GitHubRelease(tagName: String, name: String, body: String?, assets: List<GitHubAsset>)`；
  `GitHubAsset(name: String, size: Long, digest: String?, downloadUrl: String, contentType: String?)`（含 `isApk` 属性与 `APK_CONTENT_TYPE` 常量）；
  `AppUpdateChecks.parseVersion(tag: String): List<Int>?`、`isNewerVersion(current: String, tag: String): Boolean`、`pickApkAsset(assets: List<GitHubAsset>): GitHubAsset?`、`parseRelease(json: String): GitHubRelease`。

- [x] Step 1: 写失败测试（版本解析/比较、资产选择、JSON 解析，用真实 release 响应裁剪样本）
- [x] Step 2: 运行确认失败（类不存在）
- [x] Step 3: 实现 `GitHubRelease.kt`
- [x] Step 4: `gradlew testDebugUnitTest --tests "*AppUpdateChecksTest*"` 通过
- [x] Step 5: Commit `feat(update): release DTO 与版本比较纯函数`

### Task 2: AppUpdater（检查 / 流式下载 / 校验 / 安装 Intent）

**Files:**
- Create: `app/src/main/java/me/huanlin/gbuca/update/AppUpdater.kt`

**Interfaces:**
- Consumes: Task 1 的 DTO 与 `parseRelease`
- Produces: `class AppUpdater(context)`：`suspend checkLatest(): GitHubRelease?`（404 → null）、`suspend downloadApk(asset, onProgress: (Int) -> Unit): File`（progress<0=无总长）、`install(apk): InstallResult`（Launched/NeedPermission/NoInstaller）、`installPermissionIntent(): Intent`、`clearDownloads()`、`class ChecksumMismatchException`

- [x] Step 1: 实现（OkHttp 独立客户端：UA/Accept/15s 连接超时/30s 读超时；下载写 `cacheDir/update/<name>.part` 后校验 SHA-256 再重命名；FileProvider URI + FLAG_GRANT_READ_URI_PERMISSION）
- [x] Step 2: 编译通过（随 Task 5 全量验证）

### Task 3: 装配与清单

**Files:**
- Modify: `app/src/main/java/me/huanlin/gbuca/GbuCaApp.kt`（新增 `updater`）
- Modify: `app/src/main/AndroidManifest.xml`（`REQUEST_INSTALL_PACKAGES`）
- Modify: `app/src/main/res/xml/file_paths.xml`（cache-path `update/`）

- [x] Step 1: GbuCaApp 装配 `updater = AppUpdater(this)`
- [x] Step 2: Manifest 加权限；file_paths 加 update 路径

### Task 4: 文案 strings.xml

- [x] Step 1: 新增 `settings_section_update` 等更新文案（见下表：section/check/checking/uptodate/available/view/dialog_title/no_notes/download/downloading/cancel/ready/install/need_permission/grant/retry/recheck + msg_update_check_failed/no_release/no_apk/download_failed/checksum）

### Task 5: ViewModel 状态机

**Files:**
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/AppViewModel.kt`

**Interfaces:**
- Produces: `UpdateState`（Idle/Checking/UpToDate/Available/Downloading/Ready/NeedInstallPermission/Failed）、`updateState: StateFlow<UpdateState>`、`checkForUpdate()`、`downloadUpdate()`、`installUpdate()`、`cancelDownload()`、`dismissUpdate()`、`installPermissionIntent(onIntent: (Intent) -> Unit)`、私有 `updatePlainError / updateDownloadError`

- [x] Step 1: 实现 sealed interface + 六个方法（取消用 `updateJob?.cancel()`，CancellationException 回 Available 后原样抛出）
- [x] Step 2: SettingsScreen 接入（Task 6）

### Task 6: 设置页 UI

**Files:**
- Modify: `app/src/main/java/me/huanlin/gbuca/ui/SettingsScreen.kt`

**Interfaces:**
- Consumes: Task 5 全部状态与方法

- [x] Step 1: 导出卡片与关于卡片之间插入「应用更新」卡片 + `UpdateCardBody` 私有组合函数（各状态渲染）+ 更新说明 AlertDialog（可滚动，heightIn(max=360.dp)，confirm=下载并安装）
- [x] Step 2: 进度条用 lambda 版 `LinearProgressIndicator(progress = { ... })`；percent<0 用不确定样式

### Task 7: 全量验证与收尾

- [x] Step 1: `gradlew testDebugUnitTest` 全绿
- [x] Step 2: `gradlew assembleDebug` 编译通过；`lintDebug` 无新增错误
- [x] Step 3: README 功能列表加「应用内检查更新」
- [x] Step 4: 提交 `feat(update): 设置页手动检查 GitHub Release 并下载安装`

## Self-Review 结论

- 规格覆盖：检查/比较/下载/校验/授权门/安装/取消/清理 → Task 1–6 均有着落；错误文案 5 类全覆盖（strings.xml）。
- 无占位符：所有步骤含最终代码。
- 类型一致性：`GitHubAsset` 字段名、`UpdateState` 各 case、`onProgress(Int)`、`install(apk): InstallResult` 在各任务间一致。
