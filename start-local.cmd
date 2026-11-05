@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0dev.ps1" -OpenBrowser
if errorlevel 1 (
    echo Startup failed. See the error above and .local-dev logs.
    pause
    exit /b 1
)
