# Build the Windows distribution (Java runtime bundled, no install needed).
# Requirements: JDK 17+ (includes jpackage) and Maven (mvn) on PATH.
# Usage:
#   powershell -ExecutionPolicy Bypass -File packaging\package-windows.ps1 [-SkipBuild] [-NoZip] [-AppVersion 1.0.0]
# Output:
#   target\package\redmineUpster\             (app-image: redmineUpster.exe, app\, runtime\, sync-config.yml, *.bat)
#   target\package\redmineUpster-windows.zip  (the folder above, zipped; skipped with -NoZip)
param(
    [switch]$SkipBuild,
    [switch]$NoZip,
    [string]$AppVersion = "1.0.0"
)
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$AppName = "redmineUpster"
# Same module list as Linux, plus jdk.crypto.mscapi (Windows certificate store; exists only on Windows JDKs)
$Modules = ((Get-Content (Join-Path $Root "packaging\modules.txt") -Raw) -replace '\s', '') + ",jdk.crypto.mscapi"
$Out = Join-Path $Root "target\package"

if (-not $SkipBuild) {
    mvn -B -q package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }
}
$Jar = Get-ChildItem (Join-Path $Root "target") -Filter "$AppName-*.jar" |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $Jar) { throw "Jar not found under target\. Run without -SkipBuild." }

if (Test-Path $Out) { Remove-Item $Out -Recurse -Force }
New-Item -ItemType Directory -Path (Join-Path $Out "input") | Out-Null
Copy-Item $Jar.FullName (Join-Path $Out "input\$AppName.jar")

& jpackage --type app-image `
    --name $AppName `
    --app-version $AppVersion `
    --vendor "redmineUpster" `
    --description "Sync a WBS (Excel/CSV) to Redmine issues" `
    --input (Join-Path $Out "input") `
    --main-jar "$AppName.jar" `
    --dest $Out `
    --add-modules $Modules `
    --jlink-options "--strip-debug --no-man-pages --no-header-files" `
    --java-options "-XX:TieredStopAtLevel=1" `
    --java-options "-XX:+UseSerialGC" `
    --win-console
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

$AppDir = Join-Path $Out $AppName
Copy-Item (Join-Path $Root "packaging\dist\sync-config.yml") $AppDir
Copy-Item (Join-Path $Root "packaging\dist\run.bat") $AppDir
Copy-Item (Join-Path $Root "packaging\dist\run-dry-run.bat") $AppDir
Copy-Item (Join-Path $Root "docs\USER_GUIDE.md") $AppDir
Copy-Item (Join-Path $Root "LICENSE") $AppDir
Copy-Item (Join-Path $Root "samples\wbs_hierarchy.csv") (Join-Path $AppDir "sample-wbs.csv")
Copy-Item (Join-Path $Root "samples\sample-wbs.xlsx") (Join-Path $AppDir "sample-wbs.xlsx")

# Smoke test: the bundled runtime must start the app and print the usage
& (Join-Path $AppDir "$AppName.exe") --help
if ($LASTEXITCODE -ne 0) { throw "Launcher smoke test failed (exit $LASTEXITCODE)" }

if (-not $NoZip) {
    $Zip = Join-Path $Out "$AppName-windows.zip"
    Compress-Archive -Path $AppDir -DestinationPath $Zip -Force
    Write-Host "Created: $Zip"
}
Write-Host "Created: $AppDir"
