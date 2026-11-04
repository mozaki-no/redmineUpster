@echo off
rem Export users and groups only: double-click to write the users, groups and group members to users.xlsx.
rem Edit users.xlsx, then drag it onto users-dry-run.bat (preview) and users.bat (update Redmine).
rem Needs an administrator API key. Tickets are not exported.
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
"%APPDIR%redmineUpster.exe" --export --targets=users,groups --config="%APPDIR%sync-config.yml" --file="%APPDIR%users.xlsx" --log-dir="%APPDIR%logs" %OPTS%
set "RC=%ERRORLEVEL%"
echo.
echo Finished (exit code %RC%). Output: %APPDIR%users.xlsx
if not defined NOPROMPT pause
exit /b %RC%
