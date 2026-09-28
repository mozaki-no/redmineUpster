@echo off
rem Preview only: drag and drop a WBS file (.xlsx / .csv) onto this file.
rem Redmine is NOT changed. The log is saved in the "logs" folder.
setlocal
cd /d "%~dp0"
if "%~1"=="" (
  echo Drag and drop the WBS file onto run-dry-run.bat.
  pause
  exit /b 1
)
"%~dp0redmineUpster.exe" --sync --config="%~dp0sync-config.yml" --file="%~1" --log-dir="%~dp0logs" --dry-run
echo.
echo Finished (exit code %ERRORLEVEL%). Redmine was not changed.
pause
