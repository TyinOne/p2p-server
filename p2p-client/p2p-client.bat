@echo off
setlocal enabledelayedexpansion

set "CLIENT_JAR=%CD%\p2p-client-0.0.1-SNAPSHOT.jar"
set "PID_FILE=%CD%\p2p-client.pid"
set "LOG_FILE=%CD%\logs\p2p-client.log"

set "COMMAND="
set "CONFIG_FILE="

REM Parse arguments
:parse_loop
if "%~1"=="" goto :parse_done
if /i "%~1"=="start" set "COMMAND=start" & shift & goto :parse_loop
if /i "%~1"=="stop" set "COMMAND=stop" & shift & goto :parse_loop
if /i "%~1"=="restart" set "COMMAND=restart" & shift & goto :parse_loop
if /i "%~1"=="status" set "COMMAND=status" & shift & goto :parse_loop
if /i "%~1"=="--config" if not "%~2"=="" set "CONFIG_FILE=%~2" & shift & shift & goto :parse_loop
if /i "%~1"=="-c" if not "%~2"=="" set "CONFIG_FILE=%~2" & shift & shift & goto :parse_loop
if /i "%~1"=="--help" set "COMMAND=help" & shift & goto :parse_loop
if /i "%~1"=="-h" set "COMMAND=help" & shift & goto :parse_loop
shift
goto :parse_loop

:parse_done

echo ========================================
echo   P2P Tunnel Client Manager
echo ========================================
echo.

if "%COMMAND%"=="" set "COMMAND=help"

if "%COMMAND%"=="start" goto :cmd_start
if "%COMMAND%"=="stop" goto :cmd_stop
if "%COMMAND%"=="restart" goto :cmd_restart
if "%COMMAND%"=="status" goto :cmd_status
if "%COMMAND%"=="help" goto :cmd_help

echo Unknown command: %COMMAND%
goto :cmd_help

REM ============ Commands ============

:cmd_start
if not exist "%CLIENT_JAR%" (
    echo Error: Client JAR not found: %CLIENT_JAR%
    echo Please build the project first: mvn clean package
    goto :eof
)

if exist "%PID_FILE%" (
    set /p FOUND_PID=<"%PID_FILE%"
    if not "!FOUND_PID!"=="" (
        tasklist /FI "PID eq !FOUND_PID!" 2>NUL | find /I "!FOUND_PID!" >NUL
        if !ERRORLEVEL! EQU 0 (
            echo Client is already running (PID: !FOUND_PID!)
            goto :eof
        )
        del "%PID_FILE%" 2>NUL
    )
)

set "CONFIG_ARG="
if not "%CONFIG_FILE%"=="" (
    set "CONFIG_ARG=--spring.config.additional-location=file:%CONFIG_FILE%"
    echo Starting with config: %CONFIG_FILE%
) else if exist "config\client.yaml" (
    set "CONFIG_ARG=--spring.config.additional-location=file:config\client.yaml"
    echo Starting with config: config\client.yaml
) else (
    echo Starting with default config
)

if not exist "%CD%\logs" mkdir "%CD%\logs"

echo Starting P2P Client...
start /B java -jar "%CLIENT_JAR%" !CONFIG_ARG! > "%LOG_FILE%" 2>&1

timeout /t 3 /nobreak >NUL

REM 获取最后启动的 java.exe 进程 PID
set "NEW_PID="
for /f "tokens=2 delims=," %%i in ('tasklist /fi "imagename eq java.exe" /fo csv /nh 2^>NUL') do (
    set "NEW_PID=%%~i"
)

if not "!NEW_PID!"=="" (
    echo !NEW_PID! > "%PID_FILE%"
    echo [OK] Client started successfully (PID: !NEW_PID!)
) else (
    echo Client started (check log file for details)
)

echo Log file: %LOG_FILE%
goto :eof

:cmd_stop
set "FOUND_PID="
if exist "%PID_FILE%" (
    set /p FOUND_PID=<"%PID_FILE%"
)
if "!FOUND_PID!"=="" (
    echo Client is not running
    goto :eof
)

tasklist /FI "PID eq !FOUND_PID!" 2>NUL | find /I "!FOUND_PID!" >NUL
if !ERRORLEVEL! NEQ 0 (
    echo Client is not running (stale PID file)
    del "%PID_FILE%" 2>NUL
    goto :eof
)

echo Stopping client (PID: !FOUND_PID!)...
taskkill /PID !FOUND_PID! >NUL 2>&1
timeout /t 3 /nobreak >NUL

tasklist /FI "PID eq !FOUND_PID!" 2>NUL | find /I "!FOUND_PID!" >NUL
if !ERRORLEVEL! EQU 0 (
    echo Force killing...
    taskkill /PID !FOUND_PID! /F >NUL 2>&1
)

del "%PID_FILE%" 2>NUL
echo [OK] Client stopped
goto :eof

:cmd_restart
call :cmd_stop
timeout /t 2 /nobreak >NUL
call :cmd_start
goto :eof

:cmd_status
set "FOUND_PID="
if exist "%PID_FILE%" (
    set /p FOUND_PID=<"%PID_FILE%"
)
if "!FOUND_PID!"=="" (
    echo Client is not running
    goto :eof
)

tasklist /FI "PID eq !FOUND_PID!" 2>NUL | find /I "!FOUND_PID!" >NUL
if !ERRORLEVEL! NEQ 0 (
    echo Client is not running (stale PID file)
    del "%PID_FILE%" 2>NUL
    goto :eof
)

echo Client is running
echo PID: !FOUND_PID!

for /f %%m in ('powershell -NoProfile -Command "(Get-Process -Id !FOUND_PID!).WorkingSet64 / 1MB" 2^>NUL') do (
    set "MEM_MB=%%m"
)
if defined MEM_MB (
    echo Memory: ~!MEM_MB! MB
)

if exist "%LOG_FILE%" (
    echo.
    echo Recent logs:
    powershell -NoProfile -Command "Get-Content '%LOG_FILE%' -Tail 5"
)
goto :eof

:cmd_help
echo Usage: %~nx0 ^<command^> [options]
echo.
echo Commands:
echo   start [--config^|-c ^<file^>]   Start the client
echo   stop                         Stop the client
echo   restart [--config^|-c ^<file^>] Restart the client
echo   status                       Check client status
echo.
echo Options:
echo   --config, -c ^<file^>   Specify configuration file
echo   --help, -h            Show this help message
echo.
echo Examples:
echo   %~nx0 start
echo   %~nx0 start -c config\client.yaml
echo   %~nx0 stop
echo   %~nx0 status
goto :eof
