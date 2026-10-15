@echo off
REM install.bat — Mario AI Unified Installer Launcher for Windows
cd /d "%~dp0"

if exist ".venv\Scripts\python.exe" (
    ".venv\Scripts\python.exe" install.py %*
) else (
    python install.py %*
)

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] La instalacion ha finalizado con errores.
    pause
)
