@echo off
setlocal enabledelayedexpansion

set "SERVER_JAR=%CD%\p2p-server-0.0.1-SNAPSHOT.jar"
set "PID_FILE=%CD%\p2p-server.pid"
set "CONFIG_RECORD=%CD%\.last-config"
set "LOG_FILE=%CD%\logs\p2p-server.log"
set "RELOAD_FLAG=%CD%\keys\.reload-request"

set "COMMAND="
set "CONFIG_FILE="
set "CLIENT_ID="
set "PUBLIC_KEY="

REM Parse arguments
:parse_loop
if "%~1"=="" goto :parse_done
if /i "%~1"=="start" set "COMMAND=start" & shift & goto :parse_loop
if /i "%~1"=="stop" set "COMMAND=stop" & shift & goto :parse_loop
if /i "%~1"=="restart" set "COMMAND=restart" & shift & goto :parse_loop
if /i "%~1"=="reload" set "COMMAND=reload" & shift & goto :parse_loop
if /i "%~1"=="status" set "COMMAND=status" & shift & goto :parse_loop
if /i "%~1"=="list" set "COMMAND=list" & shift & goto :parse_loop
if /i "%~1"=="add" set "COMMAND=add" & shift & goto :parse_loop
if /i "%~1"=="remove" set "COMMAND=remove" & shift & goto :parse_loop
if /i "%~1"=="generate-keys" set "COMMAND=generate-keys" & shift & goto :parse_loop
if /i "%~1"=="--config" if not "%~2"=="" set "CONFIG_FILE=%~2" & shift & shift & goto :parse_loop
if /i "%~1"=="-c" if not "%~2"=="" set "CONFIG_FILE=%~2" & shift & shift & goto :parse_loop
if /i "%~1"=="--list" set "COMMAND=list" & shift & goto :parse_loop
if /i "%~1"=="-l" set "COMMAND=list" & shift & goto :parse_loop
if /i "%~1"=="--help" set "COMMAND=help" & shift & goto :parse_loop
if /i "%~1"=="-h" set "COMMAND=help" & shift & goto :parse_loop
if "!CLIENT_ID!"=="" (set "CLIENT_ID=%~1") else if "!PUBLIC_KEY!"=="" (set "PUBLIC_KEY=%~1")
shift
goto :parse_loop

:parse_done

echo ========================================
echo   P2P Tunnel Server Manager
echo ========================================
echo.

if "%COMMAND%"=="" set "COMMAND=help"

if "%COMMAND%"=="start" goto :cmd_start
if "%COMMAND%"=="stop" goto :cmd_stop
if "%COMMAND%"=="restart" goto :cmd_restart
if "%COMMAND%"=="reload" goto :cmd_reload
if "%COMMAND%"=="status" goto :cmd_status
if "%COMMAND%"=="list" goto :cmd_list
if "%COMMAND%"=="add" goto :cmd_add
if "%COMMAND%"=="remove" goto :cmd_remove
if "%COMMAND%"=="generate-keys" goto :cmd_generate_keys
if "%COMMAND%"=="help" goto :cmd_help

echo Unknown command: %COMMAND%
goto :cmd_help

REM ============ Commands ============

:cmd_start
if not exist "%SERVER_JAR%" (
    echo Error: Server JAR not found: %SERVER_JAR%
    echo Please build the project first: mvn clean package
    goto :eof
)

if exist "%PID_FILE%" (
    set /p FOUND_PID=<"%PID_FILE%"
    if not "!FOUND_PID!"=="" (
        tasklist /FI "PID eq !FOUND_PID!" 2>NUL | find /I "!FOUND_PID!" >NUL
        if !ERRORLEVEL! EQU 0 (
            echo Server is already running (PID: !FOUND_PID!)
            goto :eof
        )
        del "%PID_FILE%" 2>NUL
    )
)

set "CONFIG_ARG="
if not "%CONFIG_FILE%"=="" (
    set "CONFIG_ARG=--spring.config.additional-location=file:%CONFIG_FILE%"
    echo Starting with config: %CONFIG_FILE%
) else if exist "config\server.yaml" (
    set "CONFIG_ARG=--spring.config.additional-location=file:config\server.yaml"
    echo Starting with config: config\server.yaml
) else (
    echo Starting with default config
)

if not exist "%CD%\logs" mkdir "%CD%\logs"
if not exist "%CD%\keys" mkdir "%CD%\keys"

echo Starting P2P Server...
start /B java -jar "%SERVER_JAR%" !CONFIG_ARG! > "%LOG_FILE%" 2>&1

timeout /t 3 /nobreak >NUL

REM 获取最后启动的 java.exe 进程 PID
set "NEW_PID="
for /f "tokens=2 delims=," %%i in ('tasklist /fi "imagename eq java.exe" /fo csv /nh 2^>NUL') do (
    set "NEW_PID=%%~i"
)

if not "!NEW_PID!"=="" (
    echo !NEW_PID! > "%PID_FILE%"
    echo [OK] Server started successfully (PID: !NEW_PID!)
) else (
    echo Server started (check log file for PID)
    echo Log file: %LOG_FILE%
)

if not "%CONFIG_FILE%"=="" (
    echo %CONFIG_FILE% > "%CONFIG_RECORD%"
) else (
    echo default > "%CONFIG_RECORD%"
)
goto :eof

:cmd_stop
set "FOUND_PID="
if exist "%PID_FILE%" (
    set /p FOUND_PID=<"%PID_FILE%"
)
if "!FOUND_PID!"=="" (
    echo Server is not running
    goto :eof
)

tasklist /FI "PID eq !FOUND_PID!" 2>NUL | find /I "!FOUND_PID!" >NUL
if !ERRORLEVEL! NEQ 0 (
    echo Server is not running (stale PID file)
    del "%PID_FILE%" 2>NUL
    goto :eof
)

echo Stopping server (PID: !FOUND_PID!)...
taskkill /PID !FOUND_PID! >NUL 2>&1
timeout /t 3 /nobreak >NUL

tasklist /FI "PID eq !FOUND_PID!" 2>NUL | find /I "!FOUND_PID!" >NUL
if !ERRORLEVEL! EQU 0 (
    echo Force killing...
    taskkill /PID !FOUND_PID! /F >NUL 2>&1
)

del "%PID_FILE%" 2>NUL
echo [OK] Server stopped
goto :eof

:cmd_restart
call :cmd_stop
timeout /t 2 /nobreak >NUL
call :cmd_start
goto :eof

:cmd_reload
set "FOUND_PID="
if exist "%PID_FILE%" (
    set /p FOUND_PID=<"%PID_FILE%"
)
if "!FOUND_PID!"=="" (
    echo Server is not running
    goto :eof
)

echo Reloading configuration (PID: !FOUND_PID!)...
if not exist "%CD%\keys" mkdir "%CD%\keys"
echo reload > "%RELOAD_FLAG%"

set /a "wait_count=0"
:reload_wait_loop
if !wait_count! GEQ 20 (
    echo [WARNING] Reload flag not consumed after 10 seconds
    goto :eof
)
timeout /t 1 /nobreak >NUL
if exist "%RELOAD_FLAG%" (
    set /a "wait_count+=1"
    goto :reload_wait_loop
)
echo [OK] Configuration reloaded
goto :eof

:cmd_status
set "FOUND_PID="
if exist "%PID_FILE%" (
    set /p FOUND_PID=<"%PID_FILE%"
)
if "!FOUND_PID!"=="" (
    echo Server is not running
    goto :eof
)

tasklist /FI "PID eq !FOUND_PID!" 2>NUL | find /I "!FOUND_PID!" >NUL
if !ERRORLEVEL! NEQ 0 (
    echo Server is not running (stale PID file)
    del "%PID_FILE%" 2>NUL
    goto :eof
)

echo Server is running
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

:cmd_list
if not exist "%SERVER_JAR%" (
    echo Error: Server JAR not found: %SERVER_JAR%
    goto :eof
)
echo Registered Clients:
echo ----------------------------------------
java -jar "%SERVER_JAR%" list
echo ----------------------------------------
goto :eof

:cmd_add
if not exist "%SERVER_JAR%" (
    echo Error: Server JAR not found: %SERVER_JAR%
    goto :eof
)
if "%CLIENT_ID%"=="" (
    echo Error: Client ID and Public Key are required
    echo Usage: %~nx0 add ^<client-id^> ^<public-key^>
    goto :eof
)
if "%PUBLIC_KEY%"=="" (
    echo Error: Client ID and Public Key are required
    echo Usage: %~nx0 add ^<client-id^> ^<public-key^>
    goto :eof
)

echo Adding client: %CLIENT_ID%
java -jar "%SERVER_JAR%" add "%CLIENT_ID%" "%PUBLIC_KEY%"

if !ERRORLEVEL! EQU 0 (
    echo [OK] Client added successfully
    if exist "%PID_FILE%" (
        set /p FOUND_PID=<"%PID_FILE%"
        if not "!FOUND_PID!"=="" (
            echo Reloading server configuration...
            call :cmd_reload
        )
    )
) else (
    echo [ERROR] Failed to add client
)
goto :eof

:cmd_remove
if not exist "%SERVER_JAR%" (
    echo Error: Server JAR not found: %SERVER_JAR%
    goto :eof
)
if "%CLIENT_ID%"=="" (
    echo Error: Client ID is required
    echo Usage: %~nx0 remove ^<client-id^>
    goto :eof
)

echo Removing client: %CLIENT_ID%
set /p CONFIRM="Are you sure? (y/N): "
if /i not "!CONFIRM!"=="y" (
    echo Cancelled.
    goto :eof
)

java -jar "%SERVER_JAR%" remove "%CLIENT_ID%"
if !ERRORLEVEL! EQU 0 (
    echo [OK] Client removed successfully
    if exist "%PID_FILE%" (
        set /p FOUND_PID=<"%PID_FILE%"
        if not "!FOUND_PID!"=="" (
            echo Reloading server configuration...
            call :cmd_reload
        )
    )
) else (
    echo [ERROR] Failed to remove client
)
goto :eof

:cmd_generate_keys
if not exist "%SERVER_JAR%" (
    echo Error: Server JAR not found: %SERVER_JAR%
    goto :eof
)
if "%CLIENT_ID%"=="" (
    echo Error: Client ID is required
    echo Usage: %~nx0 generate-keys ^<client-id^>
    goto :eof
)

echo Generating key pair for: %CLIENT_ID%
java -jar "%SERVER_JAR%" generate-keys "%CLIENT_ID%"
if !ERRORLEVEL! EQU 0 (
    echo.
    echo [OK] Keys generated successfully
    echo Private key: keys\%CLIENT_ID%-private.key
    echo Public key: keys\%CLIENT_ID%-public.key
    echo.
    echo Next steps:
    echo 1. Send private key to client machine (secure channel)
    echo 2. Run: %~nx0 add %CLIENT_ID% ^(type keys\%CLIENT_ID%-public.key^)
) else (
    echo [ERROR] Failed to generate keys
)
goto :eof

:cmd_help
echo Usage: %~nx0 ^<command^> [options]
echo.
echo Commands:
echo   start [--config^|-c ^<file^>]   Start the server
echo   stop                         Stop the server
echo   restart [--config^|-c ^<file^>] Restart the server
echo   reload                       Reload configuration and keys
echo   status                       Check server status
echo   list, -l                     List all registered clients
echo   add ^<client-id^> ^<public-key^> Add a new client
echo   remove ^<client-id^>           Remove a client
echo   generate-keys ^<client-id^>    Generate key pair for new client
echo.
echo Options:
echo   --config, -c ^<file^>   Specify configuration file
echo   --list, -l            List clients (shortcut)
echo   --help, -h            Show this help message
echo.
echo Examples:
echo   %~nx0 start
echo   %~nx0 start -c config\server.yaml
echo   %~nx0 stop
echo   %~nx0 status
goto :eof
