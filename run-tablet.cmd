@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%run-tablet.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"

set "CMD_SAFE=%cmdcmdline:"=_%"
if not "%CMD_SAFE:/c __=%"=="%CMD_SAFE%" (
    echo.
    pause
)

exit /b %EXIT_CODE%
