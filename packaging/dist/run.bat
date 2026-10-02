@echo off
rem Sync: drag and drop one or more WBS files (.xlsx / .xlsm / .csv) onto this file.
rem Several files are synced one after another, in the order they are passed.
rem With no file, the files listed under "files:" in sync-config.yml are synced.
rem Close the files in Excel first (new ticket IDs are written back to them).
setlocal
cd /d "%~dp0"
rem "shift" also shifts %0, so remember this folder first
set "APPDIR=%~dp0"
set FILES=
if "%~1"=="" (
  echo No file was dropped. The files listed under "files:" in sync-config.yml will be used.
) else (
  echo This will UPDATE Redmine using:
)
:collect
if "%~1"=="" goto confirm
echo   "%~1"
set FILES=%FILES% --file="%~1"
shift
goto collect
:confirm
choice /c YN /m "Continue"
if errorlevel 2 exit /b 1
"%APPDIR%redmineUpster.exe" --sync --config="%APPDIR%sync-config.yml" %FILES% --log-dir="%APPDIR%logs"
echo.
echo Finished (exit code %ERRORLEVEL%). See the "logs" folder for details.
pause
