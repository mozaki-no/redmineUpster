@echo off
rem Users and groups only, preview: drag and drop an Excel file (.xlsx / .xlsm) with the
rem user / group / group member sheets (as written by export-users.bat) onto this file.
rem With no file, users.xlsx in this folder is used. Tickets are NOT synced and Redmine is NOT changed.
rem The log is saved in the "logs" folder. Needs an administrator API key.
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
if "%~1"=="" goto collected
if /i "%~1"=="/y" (set "NOPROMPT=1" & shift & goto collect)
if /i "%~1"=="--yes" (set "NOPROMPT=1" & shift & goto collect)
set "ARG=%~1"
if "%ARG:~0,2%"=="--" (set OPTS=%OPTS% "%~1" & shift & goto collect)
set /a COUNT+=1
echo   "%~1"
set FILES=%FILES% --file="%~1"
shift
goto collect
:collected
if %COUNT%==0 set FILES= --file="%APPDIR%users.xlsx"
if %COUNT%==0 echo No file was dropped. Using "%APPDIR%users.xlsx".
"%APPDIR%redmineUpster.exe" --sync --targets=users,groups --config="%APPDIR%sync-config.yml" %FILES% --log-dir="%APPDIR%logs" --dry-run %OPTS%
set "RC=%ERRORLEVEL%"
echo.
echo Finished (exit code %RC%). Redmine was not changed.
if not defined NOPROMPT pause
exit /b %RC%
