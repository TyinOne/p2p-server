#!/usr/bin/env pwsh
# P2P Tunnel Client Management Script (PowerShell)
# Usage: ./p2p-client.ps1 [command] [options]

param(
    [Parameter(Position=0)]
    [string]$Command,
    [Parameter(Position=1)]
    [string]$Arg1,
    [string]$Config
)

$ErrorActionPreference = "Stop"
$CWD = Get-Location
$CLIENT_JAR = Join-Path $CWD "p2p-client-0.0.1-SNAPSHOT.jar"
$PID_FILE = Join-Path $CWD "p2p-client.pid"
$LOG_FILE = Join-Path $CWD "logs\p2p-client.log"

function Write-Header {
    Write-Host "========================================" -ForegroundColor Blue
    Write-Host "  P2P Tunnel Client Manager" -ForegroundColor Blue
    Write-Host "========================================" -ForegroundColor Blue
    Write-Host
}

function Get-ClientPid {
    if (Test-Path $PID_FILE) {
        $procId = (Get-Content $PID_FILE -Raw).Trim()
        if ($procId -and (Get-Process -Id $procId -ErrorAction SilentlyContinue)) {
            return [int]$procId
        }
        Remove-Item $PID_FILE -ErrorAction SilentlyContinue
    }
    return $null
}

function Start-Client {
    if (-not (Test-Path $CLIENT_JAR)) {
        Write-Host "Error: Client JAR not found: $CLIENT_JAR" -ForegroundColor Red
        Write-Host "Please build the project first: mvn clean package"
        return
    }

    $existingPid = Get-ClientPid
    if ($existingPid) {
        Write-Host "Client is already running (PID: $existingPid)" -ForegroundColor Yellow
        return
    }

    $configArg = @()
    if ($Config) {
        $configArg = @("--spring.config.additional-location=file:$Config")
        Write-Host "Starting with config: $Config" -ForegroundColor Blue
    } elseif (Test-Path "config\client.yaml") {
        $configArg = @("--spring.config.additional-location=file:config\client.yaml")
        Write-Host "Starting with config: config\client.yaml" -ForegroundColor Blue
    } else {
        Write-Host "Starting with default config" -ForegroundColor Blue
    }

    New-Item -ItemType Directory -Force -Path "logs" | Out-Null

    Write-Host "Starting P2P Client..." -ForegroundColor Green
    $javaArgs = @("-jar", $CLIENT_JAR) + $configArg
    $proc = Start-Process java -ArgumentList $javaArgs -RedirectStandardOutput $LOG_FILE -PassThru -WindowStyle Hidden

    $proc.Id | Set-Content $PID_FILE
    Write-Host "[OK] Client started successfully (PID: $($proc.Id))" -ForegroundColor Green
    Write-Host "Log file: $LOG_FILE" -ForegroundColor Blue
}

function Stop-Client {
    $procId = Get-ClientPid
    if (-not $procId) {
        Write-Host "Client is not running" -ForegroundColor Yellow
        return
    }

    Write-Host "Stopping client (PID: $procId)..." -ForegroundColor Yellow
    Stop-Process -Id $procId -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3

    if (Get-Process -Id $procId -ErrorAction SilentlyContinue) {
        Write-Host "Force killing..." -ForegroundColor Yellow
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    }

    Remove-Item $PID_FILE -ErrorAction SilentlyContinue
    Write-Host "[OK] Client stopped" -ForegroundColor Green
}

function Restart-Client {
    Stop-Client
    Start-Sleep -Seconds 2
    Start-Client
}

function Get-Status {
    $procId = Get-ClientPid
    if (-not $procId) {
        Write-Host "Client is not running" -ForegroundColor Yellow
        return
    }

    Write-Host "Client is running" -ForegroundColor Green
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

function Show-Help {
    $scriptName = Split-Path $PSCommandPath -Leaf
    Write-Host "Usage: $scriptName <command> [options]"
    Write-Host
    Write-Host "Commands:"
    Write-Host "  start [-Config <file>]   Start the client"
    Write-Host "  stop                     Stop the client"
    Write-Host "  restart [-Config <file>] Restart the client"
    Write-Host "  status                   Check client status"
    Write-Host
    Write-Host "Examples:"
    Write-Host "  .\$scriptName start"
    Write-Host "  .\$scriptName start -Config config\client.yaml"
    Write-Host "  .\$scriptName stop"
    Write-Host "  .\$scriptName status"
}

# Main
Write-Header

switch ($Command) {
    "start"   { Start-Client }
    "stop"    { Stop-Client }
    "restart" { Restart-Client }
    "status"  { Get-Status }
    default   { Show-Help }
}
