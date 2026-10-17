@echo off
chcp 65001 >nul
rem 无论从哪里双击或调用，都先切到本脚本所在目录（android\），后面的相对路径才成立
cd /d "%~dp0"

echo ========================================================
echo       人生智能编排体 (Life Planner AI) 安卓 APK 构建工具
echo ========================================================
echo.

if exist "gradlew.bat" (
    echo [*] 检测到 Gradle Wrapper，开始构建 Debug APK...
    call gradlew.bat assembleDebug
    goto after_build
)

where gradle >nul 2>nul
if %errorlevel% equ 0 (
    echo [*] 未发现 gradlew.bat，改用本机已安装的 gradle 构建 Debug APK...
    echo     注意：本工程按 Gradle 8.4 配置，其他版本可能不兼容 AGP 8.2.2。
    call gradle assembleDebug
    goto after_build
)

echo [错误] 找不到 gradlew.bat，也找不到全局 gradle 命令，无法在命令行构建。
echo.
echo 本仓库已提交 gradle-wrapper.jar 与 gradlew 脚本（Gradle 8.4），请任选一种方式：
echo   1. 用 Android Studio 打开本目录（%CD%），
echo      等待 Gradle Sync 完成后：Build -^> Build Bundle(s) / APK(s) -^> Build APK(s)
echo   2. 安装 JDK 17 与 Gradle 8.4（https://gradle.org/releases/），并把 gradle 加入 PATH，再重新运行本脚本
echo   3. 在另一台已安装 gradle 的机器上，于本目录执行：
echo        gradle wrapper --gradle-version 8.4
echo      生成 gradlew / gradlew.bat / gradle-wrapper.jar 后拷回本目录
echo.
goto end

:after_build
if %errorlevel% neq 0 (
    echo.
    echo [错误] 构建失败，请查看上方 Gradle 输出。常见原因：未安装 JDK 17、缺少 Android SDK
    echo        （需要设置 ANDROID_HOME，或在 android\local.properties 里写 sdk.dir=...）。
    goto end
)

echo.
echo [完成] 构建成功，APK 位于（相对 android\ 目录）：
echo     app\build\outputs\apk\debug\app-debug.apk
echo.
echo 该 App 为完全本地的原生应用，无需电脑后端；AI 需在「设置」里填写 API Key。
echo 用 USB 数据线安装到手机：
echo     adb install -r app\build\outputs\apk\debug\app-debug.apk

:end
echo.
pause
