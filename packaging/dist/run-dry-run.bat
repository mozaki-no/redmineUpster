@echo off
rem Preview only: drag and drop one or more WBS files (.xlsx / .xlsm / .csv) onto this file.
rem With no file, the files listed under "files:" in sync-config.yml are used.
rem Redmine is NOT changed. The log is saved in the "logs" folder.
rem Unattended use: /y (or --yes), REDMINEUPSTER_NO_PROMPT=1 or Jenkins (JENKINS_URL) skips the pause.
rem Other options starting with "--" (quote them, e.g. "--project=xxx") are passed to redmineUpster.exe. The exit code is redmineUpster.exe's.
setlocal
cd /d "%~dp0"
rem "shift" also shifts %0, so remember this folder first
set "APPDIR=%~dp0"
set "NOPROMPT="
if "%REDMINEUPSTER_NO_PROMPT%"=="1" set "NOPROMPT=1"
if defined JENKINS_URL set "NOPROMPT=1"
set FILES=
set OPTS=
set COUNT=0
:collect
if "%~1"=="" goto run
if /i "%~1"=="/y" (set "NOPROMPT=1" & shift & goto collect)
if /i "%~1"=="--yes" (set "NOPROMPT=1" & shift & goto collect)
set "ARG=%~1"
if "%ARG:~0,2%"=="--" (set OPTS=%OPTS% "%~1" & shift & goto collect)
set /a COUNT+=1
echo   "%~1"
set FILES=%FILES% --file="%~1"
shift
goto collect
:run
if %COUNT%==0 echo No file was dropped. The files listed under "files:" in sync-config.yml will be used.
"%APPDIR%redmineUpster.exe" --sync --config="%APPDIR%sync-config.yml" %FILES% --log-dir="%APPDIR%logs" --dry-run %OPTS%
set "RC=%ERRORLEVEL%"
echo.
echo Finished (exit code %RC%). Redmine was not changed.
if not defined NOPROMPT pause
exit /b %RC%
