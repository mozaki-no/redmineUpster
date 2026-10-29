@echo off
rem Sync: drag and drop one or more WBS files (.xlsx / .xlsm / .csv) onto this file.
rem Several files are synced one after another, in the order they are passed.
rem With no file, the files listed under "files:" in sync-config.yml are synced.
rem Close the files in Excel first (new ticket IDs are written back to them).
rem
rem Unattended use (Jenkins, Task Scheduler): no "Continue (Y/N)" question and no pause when
rem   - /y (or --yes) is passed, e.g.  run.bat /y "C:\wbs\wbs.xlsx"
rem   - the environment variable REDMINEUPSTER_NO_PROMPT=1 is set, or
rem   - it runs under Jenkins (JENKINS_URL is set).
rem Other options starting with "--" are passed to redmineUpster.exe; quote them when they contain "=",
rem e.g.  run.bat /y "--project=xxx" "--root-issue=123" "C:\wbs\wbs.xlsx"
rem The exit code is redmineUpster.exe's (0 = success, 1 = any error), so Jenkins can detect failures.
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
set FILES=%FILES% --file="%~1"
shift
goto collect
:collected
if %COUNT%==0 echo No file was dropped. The files listed under "files:" in sync-config.yml will be used.
if not %COUNT%==0 echo This will UPDATE Redmine using:%FILES%
if defined NOPROMPT goto run
choice /c YN /m "Continue"
if errorlevel 2 exit /b 1
:run
"%APPDIR%redmineUpster.exe" --sync --config="%APPDIR%sync-config.yml" %FILES% --log-dir="%APPDIR%logs" %OPTS%
set "RC=%ERRORLEVEL%"
echo.
echo Finished (exit code %RC%). See the "logs" folder for details.
if not defined NOPROMPT pause
exit /b %RC%
