@echo off
rem Sync: drag and drop a WBS file (.xlsx / .csv) onto this file.
rem Close the file in Excel first (new ticket IDs are written back to it).
setlocal
cd /d "%~dp0"
if "%~1"=="" (
  echo Drag and drop the WBS file onto run.bat.
  pause
  exit /b 1
)
echo This will UPDATE Redmine using: %~1
choice /c YN /m "Continue"
if errorlevel 2 exit /b 1
"%~dp0redmineUpster.exe" --sync --config="%~dp0sync-config.yml" --file="%~1" --log-dir="%~dp0logs"
echo.
echo Finished (exit code %ERRORLEVEL%). See the "logs" folder for details.
pause
