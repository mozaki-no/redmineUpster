@echo off
rem Full backup: writes everything in the Redmine project (tickets incl. closed, journals, relations,
rem attachments, time entries, versions, categories, members, wiki, ...) to a read-only Excel file
rem backup\<project>_yyyyMMdd_HHmmss.xlsx (+ backup\<same name>_files\ for attachments and long texts).
rem It is for reading when Redmine is lost; it cannot be dragged onto run.bat (use export.bat for that).
rem Folder / generations to keep / contents: fullBackup in sync-config.yml, or options such as
rem "--backup-dir=D:\backup" "--keep=7" "--all-projects" "--no-attachments" "--project=xxx".
rem Unattended use (Jenkins, Task Scheduler): /y (or --yes), REDMINEUPSTER_NO_PROMPT=1 or Jenkins (JENKINS_URL) skips the pause.
rem Other options starting with "--" (quote them) are passed to redmineUpster.exe. The exit code is redmineUpster.exe's (0 = OK, 1 = failed).
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
"%APPDIR%redmineUpster.exe" --backup --config="%APPDIR%sync-config.yml" --log-dir="%APPDIR%logs" %OPTS%
set "RC=%ERRORLEVEL%"
echo.
echo Finished (exit code %RC%). Output folder: fullBackup.dir in sync-config.yml (default: %APPDIR%backup)
if not defined NOPROMPT pause
exit /b %RC%
