@echo off
rem Settings: double-click to open the settings window and edit sync-config.yml in this folder.
rem Comments in sync-config.yml are replaced when you save; the previous file is kept as sync-config.yml.bak.
setlocal
cd /d "%~dp0"
"%~dp0redmineUpster.exe" --settings --config="%~dp0sync-config.yml"
if errorlevel 1 (
  echo.
  echo Failed to open the settings window ^(exit code %ERRORLEVEL%^).
  pause
)
