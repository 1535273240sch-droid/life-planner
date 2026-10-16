# 人生智能编排体 (Life Planner AI) - Android 工程

包名 `com.lifeplanner.ai`，当前版本 `1.1.0`（versionCode 2）。

这是一个 WebView 套壳应用：APK 里只打包了一个移动端界面（`app/src/main/assets/www/index.html`），所有数据和 AI 功能都来自你自己运行的后端（仓库根目录的 FastAPI 服务）。

**必须在 App 右上角 ⚙️ 里填写后端地址才能使用，没有离线模式。** 没填地址或连不上时，界面会弹出提示，而不是显示空白或假数据。

---

## 工作方式

- 页面通过 `androidx.webkit.WebViewAssetLoader` 加载，地址为 `https://appassets.androidplatform.net/assets/www/index.html`。WebView 已关闭 `file://` 相关的跨源权限（`allowFileAccess`、`allowFileAccessFromFileURLs`、`allowUniversalAccessFromFileURLs`、`allowContentAccess` 均为 false）。
- 页面用 `fetch` 请求你填写的后端地址。后端已启用 CORS，所以该 https 源可以跨域访问局域网后端。
- 后端地址格式：`http://192.168.x.x:8000` 或 `https://你的域名`。以 `http://` 或 `https://` 开头，末尾斜杠会自动去掉。`http://` 仅允许本机和局域网地址，公网地址必须用 `https://`。
- 手机必须能访问到电脑：同一 Wi-Fi，且电脑防火墙放行端口 8000。

## 安全说明（请读）

- 后端 `start.py` 监听 `0.0.0.0` 且没有登录保护。同一 Wi-Fi 下的任何人都能读写你的全部数据（含健康信息）。只在可信网络使用。
- 局域网后端是明文 `http://`，同一网络里的人理论上可以嗅探到流量。Android 的 `network-security-config` 无法按网段（CIDR）放行明文，局域网 IP 又无法逐个列举，所以本工程保留了对明文的放行，并由页面代码限制 `http://` 只能连私有/本机地址。想要更强的保护，请给后端加 HTTPS（例如放在反向代理后面）。
- `allowBackup` 已关闭，应用数据不会被 `adb backup` 导出。
- 配置了 AI Key 后，健康数据会被后端发给你所配置的模型服务商，详见仓库根目录 `README.md` 的隐私声明。

---

## 构建 APK

本仓库**没有提交** `gradle-wrapper.jar` 与 `gradlew` / `gradlew.bat`。工程按 JDK 17、Gradle 8.4、AGP 8.2.2 配置。

### 方式一：Android Studio
1. 用 Android Studio 打开本目录（`android/`）。
2. 等待 Gradle Sync 完成。
3. 菜单 Build -> Build Bundle(s) / APK(s) -> Build APK(s)。
4. 产物：`app/build/outputs/apk/debug/app-debug.apk`。

### 方式二：命令行
安装 JDK 17、Android SDK 和 Gradle 8.4 后，在本目录运行 `build_apk.bat`。脚本会依次尝试 `gradlew.bat` 和全局 `gradle`，都没有时会打印下一步该怎么做。

想生成 wrapper，可在已安装 Gradle 的机器上运行：
```bash
gradle wrapper --gradle-version 8.4
```

### 方式三：GitHub Actions
`.github/workflows/android-apk-build.yml` 会用 Gradle 8.4 执行 `gradle assembleDebug` 并上传 APK。

## 安装到手机
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
需要在手机上开启开发者选项和 USB 调试。
