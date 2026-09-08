# 设计：导出到日历（.ics）

日期：2026-09-08
状态：已确认（用户逐项审批通过）

## 背景与目标

课表数据目前只在 App 内可见（今日页 / 周课表 / 桌面小组件）。用户希望把课表导入手机或电脑的
日历（Google 日历、Apple 日历、Outlook 等），在系统日历里统一查看、跨设备同步并参与日程冲突检测。
本功能把**当前学期**的课表导出为 iCalendar（`.ics`）文件。

## 需求决策记录

| 决策点 | 结论 |
| --- | --- |
| 事件形态 | 重复事件：一个「课次」一条 `VEVENT`，周次用 `RRULE`/`RDATE` 表达 |
| 交付方式 | ① 系统文件选择器（SAF）保存 `.ics`；② 分享 Intent 直接送进日历 App |
| 事件内提醒 | 写 `VALARM`，提前分钟数跟随 App 的「提前提醒时间」设置 |
| 导出范围 | 仅当前选中学期（`xnxq`） |
| 时区 | 固定 `Asia/Shanghai`（附 `VTIMEZONE`，无 DST，恒定 +0800） |
| 明确不做 | 多学期合并、时区选择、自定义 VALARM 文案、与系统日历双向同步、引入第三方 ICS 库 |

## 方案比选

- **A（采用）：自建纯 Kotlin ICS 构建器 + Android 薄适配层。**
  `IcsCalendar`（RFC 5545 序列化）与 `CourseIcsExporter`（业务映射）不依赖 Android，
  可直接 JVM 单测；Android 侧仅 `IcsExportManager` 负责读库、写文件与构造 Intent。零新依赖。
- B（否决）：引入 biweekly / ical4j。为一个功能增加依赖体积与 ProGuard 规则，
  而「周次集合 → RRULE/RDATE」的推导本来就无法由库代劳。
- C（否决）：`CalendarContract` 直接写入系统日历。需要日历写权限、每门课一次确认弹窗，
  且无法产出 `.ics` 文件（用户明确要文件）。

## 架构与数据流

```
Room (CourseEntity / MeetingEntity)
  └─ CourseRepository.meetingsByXnxq(xnxq)
SettingsStore.semesterStartMonday(xnxq) ─┐
SettingsStore.reminderMinutes           ─┤
                                         ▼
              CourseIcsExporter（domain/export，纯 Kotlin）
                 Meeting → 周次集合 → 真实日期 → RRULE / RDATE
                                         │ List<IcsCalendar.Event>
                                         ▼
              IcsCalendar（domain/export，纯 Kotlin，RFC 5545）
                                         │ String（CRLF + 75 octet 折行 + TEXT 转义）
              ┌──────────────────────────┴──────────────────────────┐
              ▼                                                     ▼
   SAF CreateDocument → .ics 文件                     cacheDir/ics/*.ics + FileProvider
                                                      → ACTION_SEND (text/calendar)
```

## 组件设计

### 1. `domain/export/IcsCalendar.kt`（新建）

RFC 5545 序列化器，纯 Kotlin，无 Android 依赖。

- 行尾统一 `CRLF`。
- **折行**：单行 ≤ 75 octet（UTF-8 字节数），超出则插 `CRLF + 空格`；
  续行预算 74 octet（含前导空格）。折行**按码点**推进，绝不切断多字节中文。
- **TEXT 转义**：`\` → `\\`，`;` → `\;`，`,` → `\,`，换行 → `\n`，`\r` 丢弃。
- 固定输出 `VERSION:2.0`、`PRODID`、`CALSCALE:GREGORIAN`、`METHOD:PUBLISH`、
  `X-WR-CALNAME`、`X-WR-TIMEZONE` 与 `VTIMEZONE`（`Asia/Shanghai`，`STANDARD` 段恒定 `+0800`）。
- `Event` 数据类：`uid / start / end / summary / location? / description? / rrule? / rdates / alarmMinutes?`。
  - `DTSTART`/`DTEND`/`RDATE` 带 `;TZID=Asia/Shanghai` 参数。
  - `rrule` 非空 → 输出 `RRULE:<值>`（值不含 `RRULE:` 前缀）。
  - `rdates` 非空 → 输出单条 `RDATE;TZID=...:a,b,c`。
  - `alarmMinutes` 非 null 且 > 0 → 输出 `VALARM`（`ACTION:DISPLAY` + `TRIGGER:-PT{n}M` + `DESCRIPTION`）。
- `DTSTAMP` 取 `Instant` 参数（默认 `Instant.now()`），格式化为 UTC `...Z`。

### 2. `domain/export/CourseIcsExporter.kt`（新建）

业务映射：`TermData` + 学期起始周一 → `List<IcsCalendar.Event>`。

- 每个 `Meeting` → 一条 `VEVENT`。真实日期 =
  `semesterStartMonday + (week - 1) * 7 + (weekday - 1)` 天。
- **重复规则**：周次排序后相邻间隔若恒定（如 1-16 周步长 1、双周步长 2）
  → `FREQ=WEEKLY;INTERVAL={步长};COUNT={次数}`；间隔不规则（如 `1-4,9,12周`）
  → `RDATE` 列出除首场外的全部日期；只有一次 → 两者都不写。
- **UID 稳定**：对 `xnxq|rwh|role|weekday|节次|时间|周次集合` 取 SHA-1，取前 32 位十六进制
  + `@gbuca`。同一课次重复导入是**更新**而非重复创建。同一门课同一时段按周次拆分的课次
  （如物理 1-4 周 / 5-16 周教师不同）因周次集合不同而得到不同 UID。
- `SUMMARY` = 课程名；`role == ROLE_LAB` 时追加 ` · 实验`。
- `LOCATION` = 教室；`null`（`无地点` 已在解析层归一为空）则省略该行。
- `DESCRIPTION` = 节次与时间 / 周次（复用 `ScheduleLogic.formatWeeks`）/ 教师 / 课程代码 /
  课序号 / 学分 / 班级 / 非主任务角色。
- 输出按开始时间、名称排序，保证导出结果稳定可比对。
- 空课表返回空列表（由上层给出「请先同步」提示）。

### 3. `data/export/IcsExportManager.kt`（新建）

Android 薄适配层，构造注入 `Context`、`CourseRepository`、`SettingsStore`。

- `suspend fun build(xnxq: String): String?` —— 读 `meetingsByXnxq` 与
  `semesterStartMonday`/`reminderMinutes`，组装并序列化；无课返回 `null`。
- `fun suggestedFileName(xnxq: String): String` —— `GBU课表-{xnxq}.ics`。
- `fun writeToUri(uri: Uri, content: String)` —— 经 `contentResolver.openOutputStream` 写入
  （SAF 不需要存储权限）。
- `fun shareIntent(content: String, fileName: String): Intent` —— 写
  `cacheDir/ics/{fileName}`，经 FileProvider 取 `content://` URI，
  `ACTION_SEND` + `type = "text/calendar"` + `FLAG_GRANT_READ_URI_PERMISSION`，
  `Intent.createChooser` 由调用方包一层。

### 4. FileProvider 配置

- 新建 `res/xml/file_paths.xml`：`<cache-path name="ics" path="ics/" />`。
- `AndroidManifest.xml` 新增 `<provider>`：`androidx.core.content.FileProvider`，
  `authorities = "${applicationId}.fileprovider"`，`exported="false"`，
  `grantUriPermissions="true"`。**不需要新增任何权限**（SAF 与 FileProvider 均免权限）。

### 5. UI 接入

- `SettingsScreen` 在「学期配置」之后新增分组「导出到日历」：
  - 说明文案：把当前学期课表导出为 `.ics`，可导入系统日历。
  - 按钮①「导出 .ics 文件」→ `ActivityResultContracts.CreateDocument("text/calendar")`，
    回调把 `Uri` 交给 `vm.exportIcs(uri)`。
  - 按钮②「分享到日历 App」→ `vm.shareIcs()`，成功后 `startActivity(createChooser(...))`。
  - 结果提示复用 `ui.message`（成功/无课/失败）。
- `AppViewModel` 新增 `exportIcs(uri: Uri)` 与 `shareIcs(onIntent: (Intent) -> Unit)`，
  复用 `runCatchingNonCancellation` 与 `friendlyError` 的既有约定。
- `GbuCaApp` 新增 `lateinit var icsExport: IcsExportManager`，在 `onCreate` 中构造。
- `strings.xml` 新增导出相关条目（`settings_section_export`、`settings_export_file`、
  `settings_export_share`、`settings_export_hint`、`msg_export_ok`、`msg_export_empty`、
  `msg_export_failed`、`msg_export_no_app`）。

## 错误处理与边界

| 场景 | 行为 |
| --- | --- |
| 当前学期无课（未同步/未开学） | 提示「当前学期还没有课程，请先同步」 |
| 未配置服务器地址 | 复用 `error_server_unset` 文案，不做网络访问 |
| SAF 写入失败（`IOException`/`SecurityException`） | 捕获 → 提示导出失败，不崩溃 |
| 设备无可用日历 App（`ActivityNotFoundException`） | 提示改用「导出 .ics 文件」 |
| 学期起始周一未校准 | 沿用 `SettingsStore` 三级回退（手动 > 教务校准 > 校历默认） |
| `weeks` 为空或时间缺失的课次 | 解析层已过滤，导出层再兜底 `continue` |
| 单双周与不规则周次 | RRULE 覆盖恒定间隔；不规则走 RDATE |

## 测试

- `app/src/test/java/me/huanlin/gbuca/IcsCalendarTest.kt`（JVM 单测）
  - 折行：含中文的长 `SUMMARY` 折行后每行 ≤ 75 octet 且能原样还原；
  - 转义：`\ ; , 换行` 正确转义；
  - 行尾为 `CRLF`；`VTIMEZONE`/`X-WR-CALNAME` 存在；`DTSTAMP` 为 UTC；
  - `VALARM` 仅在 `alarmMinutes > 0` 时出现。
- `app/src/test/java/me/huanlin/gbuca/CourseIcsExporterTest.kt`（JVM 单测）
  - 1-16 周 → `FREQ=WEEKLY;INTERVAL=1;COUNT=16`；
  - 2-16 双周 → `INTERVAL=2;COUNT=8`；
  - `2,4周` → RDATE 且不含 RRULE；
  - 单次课次 → 无 RRULE 无 RDATE；
  - 同课次两次导出 UID 相同；同课同时段不同周次集合 UID 不同；
  - 无地点 → 无 `LOCATION`；实验课 → `SUMMARY` 含 `· 实验`；
  - 日期换算：第 1 周周一 = 2026-08-31 时，第 1 周周三 → 2026-09-02。
- 手动验证：真机导出 → 导入 Google 日历，核对时间、重复、提醒、教室。
