@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0dev.ps1" stop
if errorlevel 1 (
    pause
    exit /b 1
)
