# AGENTS.md — 项目经验与约定

面向 AI agent / 新协作者的关键知识。面向用户的功能说明见 [README.md](README.md)，接口逆向细节见 [PLAN.md](PLAN.md)。**本文件不写任何账号口令。**

## 领域知识：学校实行单双日两套作息（最重要）

大湾区大学作息分两套，**节号相同、时间不同**：

| | 周一/三/五 | 周二/四 |
|---|---|---|
| 结构 | 9 段 × 2 节 | 6 段 × 3 节连排 |
| 第4-6节 | 第3-4节 9:30-10:45 + 第5-6节 11:00-12:15 | 第二段 10:10-12:05 |
| 数据来源 | 教务 `queryYxkc` 响应的 `kbjclist`（UTC，需 +8h） | **教务接口不返回**，内置 `TimeGrid.DEFAULT_TT` |

- 权威口径：教务系统「上课节次时间查询」FineReport 报表（`/browserRedirect/queryReport?viewlet=byyt/zyzx/上课节次时间查询.cpt`），排查时间问题先看它
- `kcxx` 里的**显式时间字符串永远最高优先级**；省略时按星期选网格兜底（`TimeGrid.period(idx, weekday)`，周二/四用 DEFAULT_TT）
- 每节 35 分钟，段内间隔 5 分钟，段间 15 分钟（两套作息同规律）
- 排查"课程时间不对"的路径：抓 `queryYxkc` 看该课 `kcxx` 是否带显式时间 → 不带则核对星期与两套网格
- 时间修复一律改解析源头（`Meeting.startTime/endTime`）：提醒、ICS 导出、小组件、全部 UI 消费的是同一份持久化时间

## 教务接口要点

- 会话 Cookie 名是 **SESSION**（不是 JSESSIONID），登录判定两种都认（`PersistentCookieJar.hasJwxtSession`）
- `queryYxkc` **必须带学期参数**（`p_xn`/`p_xq`/`p_xnxq`），全空返回空列表不是报错；当前学期从 `/component/queryXnxq` 取（`sfdqxq=1`）
- `kbjclist` 只含周一三五网格；`KSSJ/JSSJ` 为 UTC ISO 串，解析时 +8h

## 发布流程（GitHub Release，CI 自动化）

发版 = 打 annotated tag 推送，CI（`.github/workflows/android-ci.yml`）自动构建/测试/签名校验并创建 Release。**发布说明的唯一载体是 tag message**，工作流不随版本改动：

```bash
# bump versionCode/versionName 并以 chore(release) 提交后：
git tag -a --cleanup=verbatim v0.0.11 -F <发布说明.md>
git push origin v0.0.11
```

三个已踩过的坑，勿再踩：

1. **`git tag -F` 默认 cleanup 会剥掉 `#` 开头的行**（`## 更新内容` 标题会静默丢失）→ 必须 `--cleanup=verbatim`
2. **tag 触发的 CI 使用 tag 所指提交里的工作流** → 改过 CI 后必须把 tag 打在包含该修复的提交上
3. CI 环境提取 tag message：actions/checkout 建的是**轻量 tag ref**，fetch refspec 要带 `+` 强制覆盖；提取用 `git cat-file tag <tag> | sed -e '1,/^$/d'`（`%(contents)` 有 subject/body 拆分语义，会丢首行）

发布后验证：`releases/latest` 的 notes 首行应与 tag message 一致；资产应含 `GBU-Course-Alert-vX.Y.Z-{debug,release}.apk`，应用内更新只挑 `-release.apk`。签名与密钥管理见 README。

## 真机调试（adb）

- release 与 debug 签名互斥：`INSTALL_FAILED_UPDATE_INCOMPATIBLE` 时需卸载重装；调试期装 debug 包
- debug 包可 `adb shell run-as me.huanlin.gbuca` 直写 `shared_prefs/gbuca_settings.xml`（`jwxt_host`/`iaaa_host`/`oobe_done`），先 push 到 `/data/local/tmp` 再 `run-as cp`（设备 shell 嵌套引号易碎）
- 凭据存 EncryptedSharedPreferences（Keystore 加密），**无法从外部写入**，只能启动 app 走登录页由其自存；Compose 输入框无 resource-id，用 `uiautomator dump` 拿坐标后 `input text`
- 登录成功即自动同步课表，今日页可直接核对课程时间

## 代码结构与测试

- `domain/time/TimeGrid`：两套作息网格（可变单例，`kbjclist` 覆盖仅作用周一三五表；`locate` 供周视图按真实时间插值）· `domain/parser/ScheduleParser`：kcxx HTML → Meeting · `data/repo/CourseRepository`：同步链路（覆盖网格 → 解析 → Room 事务写入）
- 单元测试用**真实 kcxx 样本**（已脱敏）；`TimeGrid` 是可变单例，测试前 `reset()`
- 周二四网格如有变更，须同步更新 `TimeGridTest` 与 `ScheduleParserTest` 的推导断言
