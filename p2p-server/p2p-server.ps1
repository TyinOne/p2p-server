#!/usr/bin/env pwsh
# P2P Tunnel Server Management Script (PowerShell)
# Usage: ./p2p-server.ps1 [command] [options]

param(
    [Parameter(Position=0)]
    [string]$Command,
    [Parameter(Position=1)]
    [string]$Arg1,
    [Parameter(Position=2)]
    [string]$Arg2,
    [string]$Config
)

$ErrorActionPreference = "Stop"
$CWD = Get-Location
$SERVER_JAR = Join-Path $CWD "p2p-server-0.0.1-SNAPSHOT.jar"
$PID_FILE = Join-Path $CWD "p2p-server.pid"
$CONFIG_RECORD = Join-Path $CWD ".last-config"
$LOG_FILE = Join-Path $CWD "logs\p2p-server.log"
$RELOAD_FLAG = Join-Path $CWD "keys\.reload-request"

function Write-Header {
    Write-Host "========================================" -ForegroundColor Blue
    Write-Host "  P2P Tunnel Server Manager" -ForegroundColor Blue
    Write-Host "========================================" -ForegroundColor Blue
    Write-Host
}

function Get-ServerPid {
    if (Test-Path $PID_FILE) {
        $procId = (Get-Content $PID_FILE -Raw).Trim()
        if ($procId -and (Get-Process -Id $procId -ErrorAction SilentlyContinue)) {
            return [int]$procId
        }
        Remove-Item $PID_FILE -ErrorAction SilentlyContinue
    }
    return $null
}

function Start-Server {
    if (-not (Test-Path $SERVER_JAR)) {
        Write-Host "Error: Server JAR not found: $SERVER_JAR" -ForegroundColor Red
        Write-Host "Please build the project first: mvn clean package"
        return
    }

    $existingPid = Get-ServerPid
    if ($existingPid) {
        Write-Host "Server is already running (PID: $existingPid)" -ForegroundColor Yellow
        return
    }

    $configArg = @()
    if ($Config) {
        $configArg = @("--spring.config.additional-location=file:$Config")
        Write-Host "Starting with config: $Config" -ForegroundColor Blue
    } elseif (Test-Path "config\server.yaml") {
        $configArg = @("--spring.config.additional-location=file:config\server.yaml")
        Write-Host "Starting with config: config\server.yaml" -ForegroundColor Blue
    } else {
        Write-Host "Starting with default config" -ForegroundColor Blue
    }

    New-Item -ItemType Directory -Force -Path "logs" | Out-Null
    New-Item -ItemType Directory -Force -Path "keys" | Out-Null

    Write-Host "Starting P2P Server..." -ForegroundColor Green
    $javaArgs = @("-jar", $SERVER_JAR) + $configArg
    $proc = Start-Process java -ArgumentList $javaArgs -RedirectStandardOutput $LOG_FILE -PassThru -WindowStyle Hidden

    $proc.Id | Set-Content $PID_FILE
    Write-Host "[OK] Server started successfully (PID: $($proc.Id))" -ForegroundColor Green

    if ($Config) {
        $Config | Set-Content $CONFIG_RECORD
    } else {
        "default" | Set-Content $CONFIG_RECORD
    }
}

function Stop-Server {
    $procId = Get-ServerPid
    if (-not $procId) {
        Write-Host "Server is not running" -ForegroundColor Yellow
        return
    }

    Write-Host "Stopping server (PID: $procId)..." -ForegroundColor Yellow
    Stop-Process -Id $procId -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3

    if (Get-Process -Id $procId -ErrorAction SilentlyContinue) {
        Write-Host "Force killing..." -ForegroundColor Yellow
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    }

    Remove-Item $PID_FILE -ErrorAction SilentlyContinue
    Write-Host "[OK] Server stopped" -ForegroundColor Green
}

function Restart-Server {
    Stop-Server
    Start-Sleep -Seconds 2
    Start-Server
}

function Reload-Server {
    $procId = Get-ServerPid
    if (-not $procId) {
        Write-Host "Server is not running" -ForegroundColor Red
        return
    }

    Write-Host "Reloading configuration (PID: $procId)..." -ForegroundColor Yellow
    New-Item -ItemType Directory -Force -Path (Split-Path $RELOAD_FLAG) | Out-Null
    "reload" | Set-Content $RELOAD_FLAG

    $waited = 0
    while ((Test-Path $RELOAD_FLAG) -and $waited -lt 20) {
        Start-Sleep -Seconds 1
        $waited++
    }

    if (Test-Path $RELOAD_FLAG) {
        Write-Host "[WARNING] Reload flag not consumed after 10 seconds" -ForegroundColor Yellow
    } else {
        Write-Host "[OK] Configuration reloaded" -ForegroundColor Green
    }
}

function Get-Status {
    $procId = Get-ServerPid
    if (-not $procId) {
        Write-Host "Server is not running" -ForegroundColor Yellow
        return
    }

    Write-Host "Server is running" -ForegroundColor Green
    Write-Host "PID: $procId" -ForegroundColor Blue

    try {
        $proc = Get-Process -Id $procId
        $memMB = [math]::Round($proc.WorkingSet64 / 1MB)
        Write-Host "Memory: ~${memMB} MB" -ForegroundColor Blue
    } catch {}

    if (Test-Path $LOG_FILE) {
        Write-Host
        Write-Host "Recent logs:" -ForegroundColor Blue
        Get-Content $LOG_FILE -Tail 5
    }
}

function List-Clients {
    if (-not (Test-Path $SERVER_JAR)) {
        Write-Host "Error: Server JAR not found: $SERVER_JAR" -ForegroundColor Red
        return
    }
    Write-Host "Registered Clients:" -ForegroundColor Green
    Write-Host "----------------------------------------"
    java -jar $SERVER_JAR list
    Write-Host "----------------------------------------"
}

function Add-Client {
    if (-not (Test-Path $SERVER_JAR)) {
        Write-Host "Error: Server JAR not found: $SERVER_JAR" -ForegroundColor Red
        return
    }
    if (-not $Arg1 -or -not $Arg2) {
        Write-Host "Error: Client ID and Public Key are required" -ForegroundColor Red
        Write-Host "Usage: $($MyInvocation.MyCommand.Name) add <client-id> <public-key>"
        return
    }

    Write-Host "Adding client: $Arg1" -ForegroundColor Yellow
    java -jar $SERVER_JAR add $Arg1 $Arg2

    if ($LASTEXITCODE -eq 0) {
        Write-Host "[OK] Client added successfully" -ForegroundColor Green
        if (Get-ServerPid) {
            Write-Host "Reloading server configuration..." -ForegroundColor Blue
            Reload-Server
        }
    } else {
        Write-Host "[ERROR] Failed to add client" -ForegroundColor Red
    }
}

function Remove-Client {
    if (-not (Test-Path $SERVER_JAR)) {
        Write-Host "Error: Server JAR not found: $SERVER_JAR" -ForegroundColor Red
        return
    }
    if (-not $Arg1) {
        Write-Host "Error: Client ID is required" -ForegroundColor Red
        Write-Host "Usage: $($MyInvocation.MyCommand.Name) remove <client-id>"
        return
    }

    Write-Host "Removing client: $Arg1" -ForegroundColor Yellow
    $confirm = Read-Host "Are you sure? (y/N)"
    if ($confirm -ne "y" -and $confirm -ne "Y") {
        Write-Host "Cancelled."
        return
    }

    java -jar $SERVER_JAR remove $Arg1
    if ($LASTEXITCODE -eq 0) {
        Write-Host "[OK] Client removed successfully" -ForegroundColor Green
        if (Get-ServerPid) {
            Write-Host "Reloading server configuration..." -ForegroundColor Blue
            Reload-Server
        }
    } else {
        Write-Host "[ERROR] Failed to remove client" -ForegroundColor Red
    }
}

function Generate-Keys {
    if (-not (Test-Path $SERVER_JAR)) {
        Write-Host "Error: Server JAR not found: $SERVER_JAR" -ForegroundColor Red
        return
    }
    if (-not $Arg1) {
        Write-Host "Error: Client ID is required" -ForegroundColor Red
        Write-Host "Usage: $($MyInvocation.MyCommand.Name) generate-keys <client-id>"
        return
    }

    Write-Host "Generating key pair for: $Arg1" -ForegroundColor Yellow
    java -jar $SERVER_JAR generate-keys $Arg1
    if ($LASTEXITCODE -eq 0) {
        Write-Host
        Write-Host "[OK] Keys generated successfully" -ForegroundColor Green
        Write-Host "Private key: keys\$Arg1-private.key" -ForegroundColor Blue
        Write-Host "Public key: keys\$Arg1-public.key" -ForegroundColor Blue
        Write-Host
        Write-Host "Next steps:" -ForegroundColor Yellow
        Write-Host "1. Send private key to client machine (secure channel)"
        Write-Host "2. Run: $($MyInvocation.MyCommand.Name) add $Arg1 (type keys\$Arg1-public.key)"
    } else {
        Write-Host "[ERROR] Failed to generate keys" -ForegroundColor Red
    }
}

function Show-Help {
    $scriptName = Split-Path $PSCommandPath -Leaf
    Write-Host "Usage: $scriptName <command> [options]"
    Write-Host
    Write-Host "Commands:"
    Write-Host "  start [-Config <file>]      Start the server"
    Write-Host "  stop                        Stop the server"
    Write-Host "  restart [-Config <file>]    Restart the server"
    Write-Host "  reload                      Reload configuration and keys"
    Write-Host "  status                      Check server status"
    Write-Host "  list                        List all registered clients"
    Write-Host "  add <client-id> <public-key> Add a new client"
    Write-Host "  remove <client-id>          Remove a client"
    Write-Host "  generate-keys <client-id>   Generate key pair for new client"
    Write-Host
    Write-Host "Examples:"
    Write-Host "  .\$scriptName start"
    Write-Host "  .\$scriptName start -Config config\server.yaml"
    Write-Host "  .\$scriptName stop"
    Write-Host "  .\$scriptName status"
}

# Main
Write-Header

switch ($Command) {
    "start"         { Start-Server }
    "stop"          { Stop-Server }
    "restart"       { Restart-Server }
    "reload"        { Reload-Server }
    "status"        { Get-Status }
    "list"          { List-Clients }
    "add"           { Add-Client }
    "remove"        { Remove-Client }
    "generate-keys" { Generate-Keys }
    default         { Show-Help }
}
