# 项目验证脚本（团队共用）
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools\verify.ps1                # 构建 APK + 跑 core 测试
#   powershell -ExecutionPolicy Bypass -File tools\verify.ps1 -SkipTests     # 只构建
#   powershell -ExecutionPolicy Bypass -File tools\verify.ps1 -MirrorDir C:\lpbuild-t1
#
# 说明：中文项目路径会让 Gradle 测试进程 ClassNotFoundException，
# 所以测试在 ASCII 镜像目录里跑；构建仍在真实目录里跑。
param(
    [switch]$SkipTests,
    [string]$MirrorDir = "C:\lpbuild"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$android = Join-Path $root "android"

$env:JAVA_HOME = "C:\Users\Administrator\Tools\jdk"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path

Write-Host "=== [1/2] 构建 Debug APK（真实目录） ===" -ForegroundColor Cyan
Push-Location $android
try {
    & .\gradlew.bat --no-daemon assembleDebug 2>&1 | Select-Object -Last 30
    if ($LASTEXITCODE -ne 0) { Write-Host "[FAIL] assembleDebug" -ForegroundColor Red; exit 1 }
} finally {
    Pop-Location
}
$apk = Join-Path $android "app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apk) {
    Write-Host ("[OK] APK: " + $apk + " (" + [math]::Round((Get-Item $apk).Length / 1MB, 2) + " MB)") -ForegroundColor Green
}

if ($SkipTests) { exit 0 }

Write-Host "=== [2/2] 单元测试（ASCII 镜像目录） ===" -ForegroundColor Cyan
if (Test-Path $MirrorDir) { Remove-Item -Recurse -Force $MirrorDir }
New-Item -ItemType Directory -Path $MirrorDir | Out-Null
robocopy $root $MirrorDir /E /XD build .gradle .git __pycache__ .pytest_cache /XF *.pyc /NFL /NDL /NJH /NJS /NP | Out-Null

Push-Location (Join-Path $MirrorDir "android")
try {
    & .\gradlew.bat --no-daemon :core:test 2>&1 | Select-Object -Last 40
    if ($LASTEXITCODE -ne 0) { Write-Host "[FAIL] :core:test" -ForegroundColor Red; exit 1 }
    Write-Host "[OK] :core:test 全部通过" -ForegroundColor Green
} finally {
    Pop-Location
}
