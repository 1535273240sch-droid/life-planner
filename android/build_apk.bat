@echo off
chcp 65001 >nul
echo ========================================================
echo       人生智能编排体 (Life Planner AI) 安卓 APK 构建工具
echo ========================================================
echo.

if exist "gradlew.bat" (
    echo [*] 正在调用 Gradle Wrapper 构建 Android Debug APK...
    call gradlew.bat assembleDebug
    if %ERRORLEVEL% EQU 0 (
        echo.
        echo [√] 构建成功！APK 文件已生成于：
        echo     app\build\outputs\apk\debug\app-debug.apk
        echo.
        echo [*] 你可以通过 USB 数据线直接将该 APK 安装到安卓手机：
        echo     adb install -r app\build\outputs\apk\debug\app-debug.apk
        goto end
    )
)

echo [提示] 本机尚未配置全局 Gradle 或 Java 编译环境。
echo.
echo 【最佳原生安卓开发与安装推荐方式】：
echo 1. 使用 Android Studio 打开本目录:
echo    路径: %CD%
echo 2. 点击顶部菜单: Build -> Build Bundle(s) / APK(s) -> Build APK(s)
echo 3. 构建完成后，Android Studio 会自动弹出提示，直接点击 locate 即可获取 APK 文件！
echo 4. 将该 APK 传至安卓手机，点击即可直接安装为原生桌面应用！
echo.

:end
pause
