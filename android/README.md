# 人生智能编排体 (Life Planner AI) - Android 工程

包名 `com.lifeplanner.ai`，当前版本 `2.0.0`（versionCode 3）。

v2.0.0 起这是**完全自包含的原生 Android 应用**：Kotlin + Jetpack Compose + 手机本地 SQLite（SQLDelight）。
不再使用 WebView，不再需要电脑端 FastAPI 服务，也不加载任何 CDN 资源。

---

## 模块结构

```
android/
├── core/                       # 纯 Kotlin 业务内核（无 android.* 依赖，可 JVM 单测）
│   └── src/main/kotlin/com/lifeplanner/core/
│       ├── model/              # Profile / Skill / Goal / Task / DailyLog / AiMessage / 统计结构
│       ├── logic/              # 目标树、父级/成环校验、进度状态机、记录、统计、缺口检测
│       ├── ai/                 # 提示词、危机筛查、JSON 提取、AiService、错误码
│       ├── json/               # JSON 备份导出/导入（含 id 重映射）
│       ├── data/               # SQLDelight 仓储实现
│       └── db/                 # 表结构（Tables.sq）与查询（Queries.sq）
├── app/                        # Android 接入层
│   └── src/main/kotlin/com/lifeplanner/ai/
│       ├── LifePlannerApp.kt   # Application + AppContainer（手动 DI）
│       ├── ui/                 # 7 个 Compose 页面、通用组件、主题、导航
│       ├── data/SettingsStore.kt
│       └── net/OkHttpTransport.kt
├── gradlew / gradlew.bat / gradle/wrapper/   # 已提交的 Gradle Wrapper 8.4
├── build.gradle / settings.gradle
└── gradle.properties
```

## 工作方式

- **本地优先**：目标、记录、统计、缺口、备份全部在手机本地完成，飞行模式可用。
- **AI 可选**：在「设置」里填写 OpenAI 兼容端点的 API Key / Base URL / 模型名后启用顾问、复盘、目标拆解。
  - 未配置或请求失败时显示真实的中文错误，不会用模板冒充 AI。
  - 调用前先做本地危机关键词筛查；命中即短路，不发出网络请求，并显示 24 小时热线。
- **数据导出**：设置页通过系统文件选择器导出单个 JSON；导入为覆盖式（有二次确认）。
- **隐私**：配置 Key 后，档案、技能、进行中目标与近 7 天日志（含健康数据）会发送给所选模型服务商。

## 安全说明（请读）

- API Key 只保存在本机应用私有 SharedPreferences；默认掩码显示。
- `http://` 仅允许本机/私有网段（localhost、127.0.0.1、10/8、172.16-31/12、192.168/16、::1）；公网地址必须使用 `https://`，保存时会校验。
- `allowBackup` 已关闭，应用数据不会被 `adb backup` 导出。
- 系统文件导入不是跨仓储事务：导入中途失败可能留下部分数据，界面已明确提示。

---

## 构建 APK

工程按 JDK 17、Gradle 8.4、AGP 8.2.2 配置。Gradle Wrapper 已提交，直接运行：

```bash
cd android
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

Windows 下也可以双击 `build_apk.bat`。

> 注意：如果项目路径包含中文，本工程已在 `gradle.properties` 中设置
> `android.overridePathCheck=true`；若某些 Kotlin/AGP 版本仍报路径错误，
> 请把仓库复制到纯 ASCII 路径下构建。

### 测试

```bash
./gradlew :core:test              # 96 个纯 JVM 单元测试
./gradlew :app:testDebugUnitTest  # Robolectric 冒烟（建库/仓储/备份/AI 错误路径）
```

### GitHub Actions

`.github/workflows/android-apk-build.yml` 会先跑 `:core:test`，再执行 `assembleDebug` 并上传 APK；
推送 `v*` 标签时会自动创建 Release 并附上 APK。

## 安装到手机

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

需要在手机上开启开发者选项和 USB 调试。
