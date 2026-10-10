@echo off
chcp 65001 >nul
setlocal
title Study Agent - 停止服务

rem =====================================================================
rem  停止本工程启动的后端服务（默认只停 Java/Python，中间件不动）
rem  用法：scripts\stop-all.bat            只停两个后端
rem        scripts\stop-all.bat --all      连 RocketMQ / Redis 一起停
rem =====================================================================

set "STOP_ALL=%~1"

echo ================================================================
echo   Study Agent 停止服务
echo ================================================================

for %%P in (8080 8000) do (
    for /f "tokens=5" %%i in ('netstat -ano ^| findstr /r /c:"LISTENING.*:%%P "') do (
        echo   停止端口 %%P 上的进程 PID=%%i
        taskkill /f /pid %%i >nul 2>&1
    )
)

if /i "%STOP_ALL%"=="--all" (
    echo   停止 RocketMQ Broker / NameServer ...
    taskkill /f /im java.exe /fi "WINDOWTITLE eq RocketMQ-Broker*" >nul 2>&1
    taskkill /f /im java.exe /fi "WINDOWTITLE eq RocketMQ-NameServer*" >nul 2>&1
    for /f "tokens=5" %%i in ('netstat -ano ^| findstr /r /c:"LISTENING.*:9876 "') do taskkill /f /pid %%i >nul 2>&1
    echo   停止 Redis ...
    taskkill /f /im redis-server.exe >nul 2>&1
    echo   [提示] MySQL 服务保持运行，如需停止请执行: net stop MySQL80
)

echo ================================================================
echo   完成
echo ================================================================
endlocal
