# 课表页时间绘制修正 + 课程详情页深色模式修复 — 设计

日期：2026-09-09
状态：已批准（用户确认坐标轴样式：线上时间 + 行内淡显节次号）

## 背景（真机数据实证）

通过 adb 抓取设备上全部 9 条课次的教务系统显式时间，与当前作息网格对照：

| 课次 | 教务显式时间 | 网格推算 | 对齐 |
|---|---|---|---|
| 高等数学1 周三/周五 第1-2节 | 08:00-09:15 | 08:00-09:15 | ✅ |
| 高等数学1 周五 第3-4节(实验) | 09:30-10:45 | 09:30-10:45 | ✅ |
| 计算机导论 周一 第3-4节 | 09:30-10:45 | 09:30-10:45 | ✅ |
| 计算机导论 周三 第3-6节(实验) | 09:30-12:15 | 09:30-12:15 | ✅ |
| 大学英语1 周五 第5-6节 | 11:00-12:15 | 11:00-12:15 | ✅ |
| 物理1 周三 第9-12节(实验) | 14:00-16:45 | 14:00-16:45 | ✅ |
| 思想道德与法治 周四 第1-3节 | 08:00-09:55 | 08:00-10:05 | ❌ |
| 物理1 周四 第4-6节 | 10:10-12:05 | 10:10-12:15 | ❌ |
| 线性代数 周四 第10-12节 | 14:30-16:25 | 14:40-16:45 | ❌ |

结论：

1. **作息网格（分节时间段）本身正确**。与教务 `queryYxkc` 响应中官方 `kbjclist` 一致（PLAN.md §4 已确认），所有 2 节/4 节课程全部吻合。第 7-8 节 12:30–13:45 官方如此，非数据错误。
2. **真正的缺陷**：跨大节的 3 节连排课，教务系统给出「全 5 分钟间隔」的压缩显式时间（PLAN.md 记录的边缘情况）。解析器已把显式时间存入 `Meeting.startTime/endTime`，但 `WeekScreen` 绘制课块只按节次索引（`startPeriod/endPeriod`）摆放、**忽略 `endTime`**，导致：
   - 3 节连排课块被画成整块贴到节次线（实际提前 10~20 分钟结束，线代第 10-12 节甚至提前 10 分钟于 14:30 开始）；
   - 左轴时间标签挤在行块内，而非放在分隔线上。
3. `CourseDetailScreen` 无 `Scaffold`：正文直接叠在框架主题 `windowBackground`（深色下约 #303030 灰）上，而 `TopAppBar` 用 M3 `surface`（动态取色深藏青），页面呈双色拼接。`LoginScreen`、`SetupFlowScreen` 同样裸露框架背景。

## 设计

### 1. 时间→坐标映射（`TimeGrid.locate`，纯函数可单测）

```kotlin
/** 时刻 → (节次序号, 节内比例 0f..1f)。 */
fun locate(time: LocalTime): Pair<Int, Float>?
```

- 早于/等于首节开始 → `(首节, 0f)`；
- 节内：`fraction = (time - p.start) / (p.end - p.start)`（按秒，分母 coerceAtLeast(1s)）；
- 落在两节之间空隙（含大小课间）→ `(前一节, 1f)`；
- 晚于末节结束 → `(末节, 1f)`；
- `periods` 为空 → `null`（调用方回退）。

### 2. WeekScreen 课块按真实时间绘制

- 顶/底各自 `locate(m.startTime)` / `locate(m.endTime)` → `y = periodRowH * ((节号-1) + 比例)`；
- `locate` 返回 `null` 时回退现有公式 `periodRowH * (startPeriod-1)` / 高度 `(end-start+1)*periodRowH`；
- 课块 rect：`offset(y = yTop + 1.dp)`、`height = (yBottom - yTop) - 2.dp`（保持现有 1dp/2dp 内缩）；
- 视觉语义：压缩课的起止越过节次分隔线（如 14:30 开始的课块顶略高于 14:40 那条线）＝真实时间诚实呈现。

### 3. 左轴重绘（用户选定样式）

- 每条节次分隔线上居中放置该节开始时间（`labelSmall`、`onSurfaceVariant`、surface 背景横向小内边距，遮住线段形成刻度效果）；绘制顺序在分隔线之后，保证遮盖；
- 节次序号保留：行内居中，`onSurfaceVariant.copy(alpha ≈ 0.55f)` 淡显，不再显示时间；
- 网格底部追加一条分隔线 + 末节结束时间标签；
- 滚动内容顶部留 8dp、底部留 20dp 内边距，防止首尾刻度标签被裁剪。

### 4. 冲突分道按时间判断

`assignLanes`：`it.endPeriod < m.startPeriod` → `it.endTime <= m.startTime`；分道前按 `startTime` 升序排序，保证贪心装道正确。

### 5. 深色模式修复

- `CourseDetailScreen`：`Column { TopAppBar; ... }` → `Scaffold(topBar = { TopAppBar(...) }) { padding -> LazyColumn(...padding(padding)) }`；
- `LoginScreen`、`SetupFlowScreen`：根层包 `Surface(Modifier.fillMaxSize())`。

## 不做（YAGNI）

- 不改 `kbjclist` 解析、不归一化压缩时间（详情页显式时间与教务一致，是事实数据）；
- 不做真实时间比例轴（课间留空隙），保持等高节次行网格；
- 不加班中「现在」指示线；
- `MeetingChip` 内容（课名 + 教室 + 开始时间）不变。

## 测试

- 新增 `TimeGridTest`（JVM）：`locate` 首节前 / 节内比例（如 DEFAULT 网格 14:30 → (9, 30/35)）/ 课间 → 前节 1f / 末节后 → (18, 1f) / 空网格 → null；
- 全量 `gradlew test` 通过（parser/ICS/widget 不受影响）。

## 验证

1. `gradlew assembleRelease`（同签名，versionCode 7 > 已装 6）；
2. `adb install -r` 保留用户数据；
3. 设备深色模式截图：课表页（压缩课块越线、左轴刻度）、课程详情页（背景统一）；
4. 浅色模式抽查。
