# 人生智能编排体 (Life Planner AI) - 原生安卓应用工程 (Native Android App)

本项目是「人生智能编排体」专为安卓手机打造的标准原生应用工程（Native Android Studio Project），包名：`com.lifeplanner.ai`。

---

## 📱 原生安卓应用特性

1. **沉浸式真机体验**：
   - 全屏无缝融入 OLED 纯黑 `#000000` 状态栏与手势导航；
   - 原生触感：集成 `WebAppInterface.kt`，调用手机硬件震动马达提供按键与保存触觉反馈；
   - 双击返回防误退与原生 Android Toast 提醒。

2. **内置离线引擎 + 实时智能体联动**：
   - 原生 APK 内置打包了全部移动端核心交互界面（`assets/www/index.html`），即便无网络状态下也可直接离线秒开；
   - 支持在手机端右上角【⚙️ 服务设置】输入后端局域网地址（如 `http://192.168.x.x:8000`），即可与后端的 FastAPI 和 AI 大模型决策大脑实时打通！

---

## 🚀 编译与打包生成 APK

### 方式一：使用 Android Studio 一键打包（最推荐，简单快捷）
1. 下载并启动 [Android Studio](https://developer.android.google.cn/studio)；
2. 选择 **Open**，直接打开本目录：`e:\人生智能规划\android`；
3. 等待 Gradle 自动完成依赖同步（Sync）；
4. 点击顶部菜单栏：
   👉 **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**；
5. 构建完成后，右下角会弹出提示，点击 **locate** 即可直接找到生成的 `app-debug.apk`！
6. 将 `app-debug.apk` 通过微信/QQ或USB数据线发送到手机，点击即可直接安装运行！

### 方式二：使用 Gradle 命令行构建
在配置了 JDK 17+ 的环境下，直接在当前目录执行：
```bash
./build_apk.bat
# 或
./gradlew assembleDebug
```
生成的 APK 文件位于：
`android/app/build/outputs/apk/debug/app-debug.apk`

---

## 📲 USB 真机直接调试与安装
1. 安卓手机打开【设置 -> 关于手机 -> 连续点击“版本号”7次】开启开发者选项；
2. 进入【开发者选项】，开启【USB调试】；
3. 用数据线将手机连接电脑；
4. 在 Android Studio 中点击右上角绿色的 **Run 'app'** 按钮（或执行 `adb install -r app/build/outputs/apk/debug/app-debug.apk`），软件将直接自动安装并启动在您的手机上！
