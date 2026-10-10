@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
title Study Agent - 一键启动

rem =====================================================================
rem  个性化备考学习 Agent 系统 · 本地一键启动脚本
rem  作用：检查并拉起 MySQL / Redis / RocketMQ NameServer+Broker，
rem        再启动 Java(8080) 与 Python(8000) 两个后端服务。
rem  用法：在工程根目录双击本文件，或 cmd 执行 scripts\start-all.bat
rem  停止：运行 scripts\stop-all.bat
rem =====================================================================

set "ROOT=%~dp0.."
for %%i in ("%ROOT%") do set "ROOT=%%~fi"

set "REDIS_DIR=D:\Redis-8.10.0-Windows-x64-cygwin-with-Service\Redis-8.10.0-Windows-x64-cygwin-with-Service"
set "ROCKETMQ_HOME=D:\rocketmq-all-5.5.0-bin-release"
set "PYTHON_EXE=C:\Users\asus\.workbuddy\binaries\python\versions\3.13.12\python.exe"
set "LOG_DIR=%ROOT%\logs"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

echo ================================================================
echo   Study Agent 本地环境启动
echo   工程根目录: %ROOT%
echo ================================================================
echo.

rem ---------------------------------------------------------------- 1) MySQL
echo [1/5] 检查 MySQL80 服务 ...
sc query MySQL80 | findstr /i "RUNNING" >nul
if errorlevel 1 (
    echo       MySQL80 未运行，尝试启动 ...
    net start MySQL80 >nul 2>&1
    if errorlevel 1 (
        echo       [失败] 无法启动 MySQL80，请用管理员权限运行本脚本
    ) else (
        echo       [成功] MySQL80 已启动
    )
) else (
    echo       [跳过] MySQL80 已在运行
)

rem ---------------------------------------------------------------- 2) Redis
echo [2/5] 检查 Redis (6379) ...
netstat -ano | findstr /r /c:"LISTENING.*:6379 " >nul
if errorlevel 1 (
    if exist "%REDIS_DIR%\start.bat" (
        echo       Redis 未运行，启动中 ...
        pushd "%REDIS_DIR%"
        start "Redis" /min cmd /c start.bat
        popd
        timeout /t 4 /nobreak >nul
        echo       [成功] Redis 已拉起
    ) else (
        echo       [失败] 未找到 %REDIS_DIR%\start.bat
    )
) else (
    echo       [跳过] Redis 已在运行
)

rem ------------------------------------------------------- 3) RocketMQ NameServer
echo [3/5] 检查 RocketMQ NameServer (9876) ...
netstat -ano | findstr /r /c:"LISTENING.*:9876 " >nul
if errorlevel 1 (
    echo       NameServer 未运行，启动中 ...
    start "RocketMQ-NameServer" /min cmd /c "set ROCKETMQ_HOME=%ROCKETMQ_HOME%&& cd /d %ROCKETMQ_HOME%\bin && mqnamesrv.cmd -n 127.0.0.1:9876"
    timeout /t 12 /nobreak >nul
    echo       [成功] NameServer 已拉起
) else (
    echo       [跳过] NameServer 已在运行
)

rem ----------------------------------------------------------- 4) RocketMQ Broker
echo [4/5] 检查 RocketMQ Broker (10911) ...
netstat -ano | findstr /r /c:"LISTENING.*:10911 " >nul
if errorlevel 1 (
    echo       Broker 未运行，启动中（堆内存 1G，约需 20 秒）...
    start "RocketMQ-Broker" /min cmd /c "set ROCKETMQ_HOME=%ROCKETMQ_HOME%&& cd /d %ROCKETMQ_HOME%\bin && mqbroker.cmd -c %ROCKETMQ_HOME%\conf\broker-local.conf -n 127.0.0.1:9876 autoCreateTopicEnable=true"
    timeout /t 25 /nobreak >nul
    netstat -ano | findstr /r /c:"LISTENING.*:10911 " >nul
    if errorlevel 1 (echo       [失败] Broker 未起来，看 %ROCKETMQ_HOME%\logs) else (echo       [成功] Broker 已拉起)
) else (
    echo       [跳过] Broker 已在运行
)

rem --------------------------------------------------------------- 5) 双后端
echo [5/5] 启动后端服务 ...

if exist "%ROOT%\server-java\target\server-java-1.0.0-SNAPSHOT.jar" (
    netstat -ano | findstr /r /c:"LISTENING.*:8080 " >nul
    if errorlevel 1 (
        echo       [Java] java -jar ... --server.port=8080
        start "study-agent-java" /min cmd /c "cd /d %ROOT%\server-java && java -jar target\server-java-1.0.0-SNAPSHOT.jar --server.port=8080 > %LOG_DIR%\java.out.log 2>&1"
    ) else (
        echo       [Java] 8080 已被占用，跳过
    )
) else (
    echo       [Java] 未找到 jar，请先在 server-java 执行: mvn -DskipTests package
)

if exist "%ROOT%\server-ai\.venv\Scripts\python.exe" (
    netstat -ano | findstr /r /c:"LISTENING.*:8000 " >nul
    if errorlevel 1 (
        echo       [Python] uvicorn app.main:app --port 8000
        start "study-agent-ai" /min cmd /c "cd /d %ROOT%\server-ai && .venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000 > %LOG_DIR%\ai.out.log 2>&1"
    ) else (
        echo       [Python] 8000 已被占用，跳过
    )
) else (
    echo       [Python] 未找到 server-ai\.venv，请先创建虚拟环境
)

echo.
echo ================================================================
echo   等待服务就绪 ...
timeout /t 15 /nobreak >nul
echo   健康检查：
echo     Java   http://127.0.0.1:8080/health
echo     Java   http://127.0.0.1:8080/swagger-ui.html
echo     Python http://127.0.0.1:8000/health
echo     Python http://127.0.0.1:8000/docs
echo ================================================================
endlocal
