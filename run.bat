@echo off
chcp 65001 >nul
title 人生智能编排体 (Life Planner AI) - 一键启动
cls

echo ======================================================================
echo    人生智能编排体 (Life Planner AI) 正在启动...
echo ======================================================================
echo 正在检查 Python 运行环境并启动服务...
echo.

where py >nul 2>nul
if %errorlevel% equ 0 (
    py start.py
    goto end
)

where python >nul 2>nul
if %errorlevel% equ 0 (
    python start.py
    goto end
)

echo [错误] 未检测到 Python 环境！请确保已安装 Python 3.10+ 并已勾选 Add to PATH。
pause

:end
