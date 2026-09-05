# GBU Course Alert · GBU 课表

大湾区大学（GBU）本研一体化教学管理系统课表 App：自动拉取已选课程、还原真实作息时间、周课表视图与上课提醒。

设计与接口逆向文档见 [PLAN.md](PLAN.md)。

## 功能

- **iAAA 统一认证登录**（纯 API，明文表单 → token → 教务会话 Cookie）；触发验证码时 WebView 兜底
- **已选课程同步**（`/Xsxk/queryYxkc`），Room 离线缓存，自动重登重试
- **上课信息 HTML 解析**：主任务/课内实验分组、教师列表、单双周/列举周次、3 节连排压缩时间（以显式时间为准）
- **今日页**：上课中/课间倒计时卡片 + 今日课次列表
- **周课表**：大节网格对齐、实验课区分色、今日高亮、周切换
- **上课提醒**：精确闹钟（`setExactAndAllowWhileIdle`），提前分钟数可调，开机自启重排
- **桌面小组件**（Glance）：今日课程 + 下节课高亮
- **后台同步**：WorkManager 每 12h 一次（低频防风控）

## 构建

```bash
./gradlew assembleDebug          # 调试包
./gradlew testDebugUnitTest      # 单元测试（解析器含 9 门真实课程样本）
./gradlew assembleRelease        # 发布包（需 keystore.properties，见下）
```

Release 签名：在项目根目录创建 `keystore.properties`：

```properties
storeFile=my.jks
storePassword=***
keyAlias=***
keyPassword=***
```

## 隐私

- 凭据仅存本机（EncryptedSharedPreferences / Android Keystore 加密）
- 不写日志、不上传任何数据到第三方服务器
- 仅访问 `iaaa.gbu.edu.cn` 与 `jwxt.gbu.edu.cn`

## 技术栈

Kotlin 2.4 · Jetpack Compose (BOM 2026.08) · Material 3 · OkHttp · kotlinx.serialization · Room · WorkManager + AlarmManager · Glance Widget · AGP 9.4
