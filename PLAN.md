# GBU Course Alert — 课表提醒 App 开发计划

> 目标：为大湾区大学（GBU）本研一体化教学管理系统开发 Android 课表 App，
> 自动获取已选课程、还原真实作息时间、提供课表视图与上课提醒。
>
> 探索日期：2026-09-04 · 已用测试账号（26100070）实测验证全链路

---

## 一、探索结论（已实测）

### 1. 登录链路（纯 API 可行 ✅）

不走 jwxt 自带登录表单（RSA 加密 + `/c_raskey`），直接走 iAAA 统一身份认证。
iAAA 登录为**明文 AJAX POST**（逆向自 `https://iaaa.gbu.edu.cn/iaaa/resources/javascript/OAuthLogin.js?_v=20250228`）：

```
① POST https://iaaa.gbu.edu.cn/iaaa/oauthlogin.do
   Content-Type: application/x-www-form-urlencoded
   appid=gbu_jwxt
   userName=26100070
   password=<明文密码>
   randCode=        ← 验证码，默认不触发；连续失败后 iAAA 返回 showCode=true 时需要
   smsCode=         ← 短信验证码（条件触发）
   otpCode=         ← 动态口令（条件触发）
   redirUrl=https://jwxt.gbu.edu.cn/oauth/login/code

   → 200 {"success":true,"token":"8548d80fde8ce2fa17544f701763afb9"}

② GET https://jwxt.gbu.edu.cn/oauth/login/code?_rand=<0~1随机数>&token=<token>
   → 302 → /authentication/main，响应 Set-Cookie 建立教务系统会话（JSESSIONID）

③ 后续所有请求携带该 Cookie 即可访问需登录接口
```

实测记录（curl，2026-09-04）：`oauthlogin.do → success:true` →
`/oauth/login/code → 302` → `POST /Xsxk/queryYxkc → 200, 83,663 bytes, 9 门课`。

### 2. 关键数据接口（均为 POST，需会话 Cookie，UTF-8 JSON）

| 接口 | 用途 | 备注 |
|---|---|---|
| `/Xsxk/queryYxkc` | **已选课程列表（核心）** | 参数见下 |
| `/component/queryXnxq` | 学年学期列表 | 取当前学期 `p_xnxq` |
| `/component/queryKbjg` | 节次时间结构 | `queryYxkc` 响应内已含 `kbjclist` |
| `/Xsxk/queryKxrw` | 可选课程任务 | 选课页用，暂不需要 |
| `/component/queryKkyx` / `queryDmb` / `queryXiaoqu` | 院系 / 代码表 / 校区 | 下拉筛选，暂不需要 |

`queryYxkc` 请求体（实测可用的最小集 + 完整集，完整参数见附录 A）：

```
p_xn=2026-2027        学年
p_xq=1                学期
p_xnxq=2026-20271     学年学期（拼接）
p_dqxnxq=2026-20271
p_xkfsdm=yixuan       查询类型：已选
p_sfxsgwckb=1
pageNum=1&pageSize=15 分页
```

响应顶层字段：`yxkcList[]`（课程）、`kbjclist[]`（节次时间表）、`xkgwcList[]`（选课购物车）、`xsxkPage`（分页）等。

### 3. 课程字段（`yxkcList[]` 元素，约 200 字段，常用的如下）

| 字段 | 含义 | 示例 |
|---|---|---|
| `kcmc` / `kcmc_en` | 课程名中/英 | 高等数学1 / Advanced Mathematics I |
| `kcdm` | 课程代码 | MATH101 |
| `kxh` | 课序号 | 001C |
| `rwh` | 任务号（唯一键） | 2026-2027-1-MATH101-001C |
| `rwmc` | 班级任务名 | 高等数学1-01班-3组 |
| `xf` / `xs` | 学分 / 学时 | 5.0 / 64.0 |
| `kcxzmc` / `kclbmc` | 课程性质 / 类别 | 必修 / 专业必修课 |
| `kkyxmc` | 开课学院 | 理学院 |
| `kcxx` / `pkjgmx` | **上课信息 HTML（核心）** | 见下 |
| `xksj` | 选课时间 | 2026-09-03 22:00:14 |
| `dnrl` / `dnyxrs` | 容量 / 已选 | 35 / 35 |
| `njmc` / `yxmc` / `zymc` | 年级 / 院系 / 专业 | 2026 / 信息科学技术学院 / 计算机科学与技术 |

`kcxx` 上课信息 HTML 结构（需解析的部分）：

```html
<p><b>主任务:</b> <a onclick="queryJsxx('226162029')">段金桥</a>
<p><b>上课信息:</b>
  <p>1-16周,星期三第1-2节 8:00-9:15 B304</p>
  <p>1-16周,星期五第1-2节 8:00-9:15 B304</p>
<p><b>课内实验:</b> <a>李丹丹</a>
<p><b>上课信息:</b>
  <p>1-16周,星期五第3-4节 9:30-10:45 B303</p>
```

- 一门课可含多个教学角色段：`主任务` / `课内实验`，各带教师与独立上课信息
- 地点可能为 `无地点`；教师可多人（多个 `<a>`）
- 双语字段 `kcxx_en` 可一并解析（英文周次格式 `1-16Week,Wed. 1-2 8:00-9:15`）

### 4. 作息时间规律（响应内 `kbjclist` 官方确认 ✅）

8:00 开始；小节 35 分钟；**同一大节内**小节间隔 5 分钟；**大节之间**间隔 15 分钟。

| 大节 | 时间 | 大节 | 时间 |
|---|---|---|---|
| 第 1-2 节 | 8:00–9:15 | 第 9-10 节 | 14:00–15:15 |
| 第 3-4 节 | 9:30–10:45 | 第 11-12 节 | 15:30–16:45 |
| 第 5-6 节 | 11:00–12:15 | 第 13-14 节 | 17:00–18:15 |
| 第 7-8 节 | 12:30–13:45 | 第 15-16 节 | 18:30–19:45 |
| | | 第 17-18 节 | 20:00–21:15 |

`kbjclist` 元素结构：`{XJ:小节序号, KSSJ/JSSJ:开始/结束(UTC 存储需 +8h), DJ:大节序号, DJMS:大节名称, SXW:时段(1上午/3下午/5晚上)}`。

⚠️ **边缘情况**：3 节连排课程系统给出的是**压缩时间**（间隔全按 5 分钟），与大节网格不严格对齐：

- 思想道德与法治 第 1-3 节 → 8:00-9:55（非 8:00-10:05）
- 线性代数 第 10-12 节 → 14:30-16:25（非 14:40-16:45）
- 物理原理1 第 4-6 节 → 10:10-12:05（非 10:10-12:15）

**结论：解析时以数据里的显式 `HH:MM-HH:MM` 字符串为准，大节网格仅作展示对齐与兜底。**

周次模式实测出现：`1-16周`、`1-4周`、`5-16周`、`2,4周`（列举）、`2-16双周`（单/双周）。
同一课程同一时段可拆多行（如物理 1-4 周与 5-16 周教师不同、CS101 1-8 周与 9-16 周分段）。

---

## 二、Android App 计划

**技术栈**：Kotlin + Jetpack Compose + Material 3 · Retrofit/OkHttp · kotlinx.serialization · Room · WorkManager + AlarmManager（精确闹钟）· Glance Widget

### 架构

```
┌─ UI 层 ──────────────────────────────┐
│ 今日页(倒计时卡) · 周课表 · 设置 · Widget │
├─ Domain 层 ──────────────────────────┤
│ ScheduleParser(HTML→模型)             │
│ TimeGrid(大节网格) · NextClassUseCase │
│ ReminderScheduler(闹钟编排)           │
├─ Data 层 ────────────────────────────┤
│ GbuAuth(oauthlogin.do→token→cookie)  │
│ GbuApi(queryYxkc/queryXnxq)          │
│ SessionStore(Cookie持久化)            │
│ Room: CourseEntity/MeetingEntity     │
└──────────────────────────────────────┘
```

### M1 数据层（2-3 天）

- [ ] OkHttp + 自定义 `CookieJar`，Cookie 持久化到磁盘
- [ ] `GbuAuth`：实现 ①→② 登录链路；`success=false` 或需 `randCode` 时降级
- [ ] WebView SSO 兜底登录页（加载 `oauth.jsp`，监听拿到 jwxt Cookie 后注入 CookieJar）
- [ ] 会话过期检测（API 返回登录页/302 → 自动重登）
- [ ] `queryYxkc` 拉取 + Room 缓存；凭据存 EncryptedSharedPreferences（Keystore 加密）
- [ ] 学期列表接口 `queryXnxq` 接入

### M2 解析引擎（2-3 天）

- [ ] 正则解析上课信息行：`^(周次),星期([一二三四五六日])第(\d+)-(\d+)节 (\d+:\d+)-(\d+:\d+)( (.+))?$`
- [ ] 周次解析器：`1-16周` / `2-16双周`(偶数) / `单周` / `2,4周`(列举) → 周集合
- [ ] 主任务 / 课内实验 分组，教师列表提取
- [ ] 模型：`Course` → `List<Meeting(weekSet, weekday, startTime, endTime, room, teachers, role)>`
- [ ] 内置大节时间表（第一节 8:00，硬编码 + 可从 `kbjclist` 刷新）；显式时间优先
- [ ] 解析失败兜底：原文展示 + 上报日志

### M3 UI（3-5 天）

- [ ] 今日页：当前/下节课倒计时卡片（上课中/课间 5min/课间 15min 三种状态文案）
- [ ] 周课表：时间轴视图，按大节网格对齐（冲突可并列）；主任务/实验区分色
- [ ] 学期第 1 周周一日期配置（把"第 X 周"映射到真实日期；首版手动填，默认 2026-08-31 需用户确认，后续调研校历接口）
- [ ] 课程详情：学分/教师/教室/周次/选课时间
- [ ] 多学期切换、深色模式

### M4 提醒 + Widget（3-4 天）

- [ ] `AlarmManager.setExactAndAllowWhileIdle` 上课提醒；`SCHEDULE_EXACT_ALARM` 权限与降级
- [ ] 默认提前 15 分钟（大课间）与提前 5 分钟（小课间）两档，用户可调
- [ ] WorkManager 每日重排未来 24h 闹钟 + 半天 1 次后台同步课程（低频，防风控）
- [ ] 通知渠道管理 / 开机自启（`BOOT_COMPLETED` 重排）/ 电池优化白名单引导
- [ ] Glance 桌面小组件：今日课程列表 + 下节课倒计时

### M5 打磨

- [ ] 错误兜底与重试（网络/风控/接口变更）
- [ ] 解析结果与网页"课表查看"交叉校验
- [ ] 发布：签名、ProGuard（保留序列化模型）、隐私声明（仅本机存储凭据）

### 风险与对策

| 风险 | 对策 |
|---|---|
| iAAA 风控（连续失败触发验证码/锁定） | WebView 兜底；登录失败指数退避；提示用户检查密码 |
| 接口/HTML 结构变更 | 解析层隔离 + 版本化解析器 + 失败原文展示 |
| 请求频率风控 | 半天 1 次同步 + 手动刷新；不实现选/退课操作 |
| 3 节连排时间与大节网格不一致 | 显式时间字符串为最高优先级 |
| 凭据安全 | 仅存本机加密存储；不写日志；不回传任何服务器 |

### 里程碑验收

| 里程碑 | 验收标准 |
|---|---|
| M1 | 模拟器内输入账号密码 → 拉到 9 门课并离线可看 |
| M2 | 9 门课全部课次解析正确（含双周/列举/分段，抽样与网页人工比对） |
| M3 | 周课表与教务系统"课表查看"视觉一致 |
| M4 | 锁屏收到"15 分钟后上课"通知；桌面小组件显示今日课程 |

---

## 附录 A：queryYxkc 完整请求参数（2026-09 实测）

```
cxsfmt=0&p_pylx=1&mxpylx=1&p_sfgldjr=0&p_sfredis=0&p_sfsyxkgwc=0
&p_xktjz=&p_chaxunxh=&p_gjz=&p_skjs=
&p_xn=2026-2027&p_xq=1&p_xnxq=2026-20271
&p_dqxn=2026-2027&p_dqxq=1&p_dqxnxq=2026-20271
&p_xkfsdm=yixuan&p_xiaoqu=&p_kkyx=&p_kclb=&p_xkxs=&p_dyc=
&p_kkxnxq=&p_id=&p_sfhlctkc=0&p_sfhllrlkc=0
&p_kxsj_xqj=&p_kxsj_ksjc=&p_kxsj_jsjc=
&p_kcdm_js=&p_kcdm_cxrw=&p_kcdm_cxrw_zckc=&p_kc_gjz=
&p_xzcxtjz_nj=&p_xzcxtjz_yx=&p_xzcxtjz_zy=&p_xzcxtjz_zyfx=&p_xzcxtjz_bj=
&p_sfxsgwckb=1&p_skyy=&p_sfmxzj=&p_chaxunxkfsdm=
&pageNum=1&pageSize=15
```

实测精简参数集亦可用：`p_xn、p_xq、p_xnxq、p_dqxnxq、p_xkfsdm、p_sfxsgwckb、pageNum、pageSize`。

## 附录 B：实测已选课程样本（2026-2027-1，总学分 24）

| 课程 | 代码 | 学分 | 时间 |
|---|---|---|---|
| 高等数学1 | MATH101 | 5.0 | 三/五 1-2节 8:00-9:15 B304；实验 五 3-4节 B303 |
| 人工智能研讨课1 | GEC06A | 2.0 | 三 13-14节 17:00-18:15 B304 |
| 大学英语1 | ENGL101 | 2.0 | 五 5-6节 11:00-12:15 B302 |
| 物理原理1 | PHY101 | 4.0 | 四 4-6节 10:10-12:05 B304；实验 双周三 9-12节 14:00-16:45 |
| 物理课答疑I | TUT02 | 0.0 | 双周二 4-6节 10:10-12:05 B501 |
| 计算机科学导论答疑I | TUT01 | 0.0 | 一 15-16节 18:30-19:45 |
| 线性代数 | MATH103 | 4.0 | 四 10-12节 14:30-16:25 B304；实验 一 11-12节 15:30-16:45 B402 |
| 思想道德与法治 | IPC101 | 3.0 | 四 1-3节 8:00-9:55 B306 |
| 计算机科学导论（上） | CS101 | 4.0 | 一 3-4节 9:30-10:45 B205；实验 三 3-6节 9:30-12:15 B407 |
