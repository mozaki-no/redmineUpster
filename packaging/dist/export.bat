@echo off
rem Export: double-click to write the project's tickets, users and groups to redmine-export.xlsx.
rem The exported file can be dragged onto run-dry-run.bat / run.bat as it is.
rem Users and groups need an administrator API key (otherwise only tickets are exported).
rem Unattended use: /y (or --yes), REDMINEUPSTER_NO_PROMPT=1 or Jenkins (JENKINS_URL) skips the pause.
rem Other options starting with "--" (quote them, e.g. "--project=xxx") are passed to redmineUpster.exe. The exit code is redmineUpster.exe's.
setlocal
cd /d "%~dp0"
set "APPDIR=%~dp0"
set "NOPROMPT="
if "%REDMINEUPSTER_NO_PROMPT%"=="1" set "NOPROMPT=1"
if defined JENKINS_URL set "NOPROMPT=1"
set OPTS=
:collect
if "%~1"=="" goto run
if /i "%~1"=="/y" (set "NOPROMPT=1" & shift & goto collect)
if /i "%~1"=="--yes" (set "NOPROMPT=1" & shift & goto collect)
set OPTS=%OPTS% "%~1"
shift
goto collect
:run
"%APPDIR%redmineUpster.exe" --export --config="%APPDIR%sync-config.yml" --file="%APPDIR%redmine-export.xlsx" --log-dir="%APPDIR%logs" %OPTS%
set "RC=%ERRORLEVEL%"
echo.
echo Finished (exit code %RC%). Output: %APPDIR%redmine-export.xlsx
if not defined NOPROMPT pause
exit /b %RC%
