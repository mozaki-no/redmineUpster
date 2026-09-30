@echo off
rem Export: double-click to write the project's tickets, users and groups to redmine-export.xlsx.
rem The exported file can be dragged onto run-dry-run.bat / run.bat as it is.
rem Users and groups need an administrator API key (otherwise only tickets are exported).
setlocal
cd /d "%~dp0"
"%~dp0redmineUpster.exe" --export --config="%~dp0sync-config.yml" --file="%~dp0redmine-export.xlsx" --log-dir="%~dp0logs"
echo.
echo Finished (exit code %ERRORLEVEL%). Output: %~dp0redmine-export.xlsx
pause
