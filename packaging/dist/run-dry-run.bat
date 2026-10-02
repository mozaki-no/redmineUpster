@echo off
rem Preview only: drag and drop one or more WBS files (.xlsx / .xlsm / .csv) onto this file.
rem With no file, the files listed under "files:" in sync-config.yml are used.
rem Redmine is NOT changed. The log is saved in the "logs" folder.
setlocal
cd /d "%~dp0"
rem "shift" also shifts %0, so remember this folder first
set "APPDIR=%~dp0"
set FILES=
if "%~1"=="" echo No file was dropped. The files listed under "files:" in sync-config.yml will be used.
:collect
if "%~1"=="" goto run
echo   "%~1"
set FILES=%FILES% --file="%~1"
shift
goto collect
:run
"%APPDIR%redmineUpster.exe" --sync --config="%APPDIR%sync-config.yml" %FILES% --log-dir="%APPDIR%logs" --dry-run
echo.
echo Finished (exit code %ERRORLEVEL%). Redmine was not changed.
pause
