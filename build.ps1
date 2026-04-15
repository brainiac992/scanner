#Requires -Version 5.1
<#
.SYNOPSIS
    Packages scanner-bridge into a self-contained Windows application.

.DESCRIPTION
    Produces a folder under target/dist/Scanner Bridge/ that contains a
    bundled JRE and Scanner Bridge.exe.  No Java installation is required
    on the target machine.

    Prerequisites (developer machine only):
      - JDK 17 or later  (jpackage must be on PATH)
      - Maven 3.6+        (mvn must be on PATH)
      - jacob-1.21-x64.dll placed in ./lib/

.EXAMPLE
    .\build.ps1
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Catch any throw from the Fail helper and exit with code 1.
trap {
    Write-Host "Build aborted: $_" -ForegroundColor Red
    [System.Environment]::Exit(1)
}

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
function Write-Step([string]$msg) {
    Write-Host ""
    Write-Host "==> $msg" -ForegroundColor Cyan
}

function Fail([string]$msg) {
    Write-Host ""
    Write-Host "ERROR: $msg" -ForegroundColor Red
    # Use throw so this works correctly regardless of PowerShell hosting context.
    # The script's trap or the caller will see a non-zero exit code.
    throw "Build failed: $msg"
}

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
$ProjectRoot  = $PSScriptRoot
$DllSrc       = Join-Path $ProjectRoot "lib\jacob-1.21-x64.dll"
$JarOut       = Join-Path $ProjectRoot "target\scanner-bridge.jar"
$StageDir     = Join-Path $ProjectRoot "target\package-input"
$StageDllDir  = Join-Path $StageDir    "lib"
$DistDir      = Join-Path $ProjectRoot "target\dist"
$AppName      = "Scanner Bridge"
$AppVersion   = "1.0"
$MainClass    = "com.scanner.bridge.BridgeApplication"
$IcoPath      = Join-Path $ProjectRoot "src\main\resources\tray-icon.ico"

# ---------------------------------------------------------------------------
# Step 1: Verify jpackage is available
# ---------------------------------------------------------------------------
Write-Step "Step 1: Verifying jpackage is available"

$jpackageCmd = Get-Command jpackage -ErrorAction SilentlyContinue
if (-not $jpackageCmd) {
    Fail ("jpackage not found on PATH.`n" +
          "Install JDK 17 or later and ensure JAVA_HOME/bin is on your PATH.`n" +
          "jpackage ships with the JDK, not the JRE.")
}

$jpackageVersion = & jpackage --version 2>&1
Write-Host "    jpackage version: $jpackageVersion"

# ---------------------------------------------------------------------------
# Step 1b: Verify JACOB JAR integrity (SEC-12 supply chain protection)
# ---------------------------------------------------------------------------
# HOW TO POPULATE CHECKSUMS:
#   1. Download JACOB from: https://github.com/jacob-project/jacob/releases
#   2. Run: Get-FileHash -Algorithm SHA256 .\lib\jacob-1.21.jar
#   3. Run: Get-FileHash -Algorithm SHA256 .\lib\jacob-1.21-x64.dll
#   4. Replace the placeholder strings in this script with those values
#   5. Commit the updated build.ps1 â€" this pins the verified version
Write-Host ""
Write-Host "Step 1b: Verifying JACOB JAR integrity..." -ForegroundColor Cyan

$jacobJar = "$PSScriptRoot\lib\jacob-1.21.jar"
$jacobDll = "$PSScriptRoot\lib\jacob-1.21-x64.dll"

# SHA256 checksums â€" update these when upgrading JACOB
# Generate with: Get-FileHash -Algorithm SHA256 .\lib\jacob-1.21.jar
$expectedJarHash  = "4B41764013B264EA46B9F659DE7914559677B80C5918DAE0BEB6DCD42F7634EF"
$expectedDllHash  = "FBB3D16D2C3EE947FA3F9FB53E98B2FB4F0F4517ABEBC7005DFD082E928659AF"

$actualJarHash = (Get-FileHash -Algorithm SHA256 $jacobJar).Hash
$actualDllHash = (Get-FileHash -Algorithm SHA256 $jacobDll).Hash

if ($expectedJarHash -ne "REPLACE_WITH_SHA256_OF_YOUR_JACOB_JAR") {
    if ($actualJarHash -ne $expectedJarHash) {
        Write-Host "ERROR: jacob-1.21.jar SHA256 mismatch!" -ForegroundColor Red
        Write-Host "  Expected: $expectedJarHash"
        Write-Host "  Actual:   $actualJarHash"
        Write-Host "Do not build with unverified dependencies."
        exit 1
    }
    Write-Host "JAR checksum OK." -ForegroundColor Green
} else {
    Write-Host "WARNING: JACOB JAR checksum not configured." -ForegroundColor Yellow
    Write-Host "Set expected hash in build.ps1 Step 1b after obtaining your JACOB files."
}

if ($expectedDllHash -ne "REPLACE_WITH_SHA256_OF_YOUR_JACOB_DLL") {
    if ($actualDllHash -ne $expectedDllHash) {
        Write-Host "ERROR: jacob-1.21-x64.dll SHA256 mismatch!" -ForegroundColor Red
        Write-Host "  Expected: $expectedDllHash"
        Write-Host "  Actual:   $actualDllHash"
        exit 1
    }
    Write-Host "DLL checksum OK." -ForegroundColor Green
} else {
    Write-Host "WARNING: JACOB DLL checksum not configured." -ForegroundColor Yellow
}

# ---------------------------------------------------------------------------
# Step 2: Build the fat JAR with Maven
# ---------------------------------------------------------------------------
Write-Step "Step 2: Running mvn clean package"

$mvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvnCmd) {
    Fail "mvn not found on PATH. Install Maven 3.6+ and add it to your PATH."
}

Push-Location $ProjectRoot
try {
    & mvn clean package -q
    if ($LASTEXITCODE -ne 0) {
        Fail "Maven build failed (exit code $LASTEXITCODE). Run 'mvn clean package' for full output."
    }
} finally {
    Pop-Location
}

if (-not (Test-Path $JarOut)) {
    Fail "Expected fat JAR not found at: $JarOut"
}
Write-Host "    Built: $JarOut"

# ---------------------------------------------------------------------------
# Step 3: Assemble the jpackage staging directory
# ---------------------------------------------------------------------------
Write-Step "Step 3: Assembling staging directory"

# Verify the DLL exists in lib/ before we try to copy it
if (-not (Test-Path $DllSrc)) {
    Fail ("JACOB DLL not found at: $DllSrc`n" +
          "Download jacob-1.21-x64.dll and place it in the ./lib/ folder.`n" +
          "See lib/README.txt for download instructions.")
}

# Recreate staging dir cleanly
if (Test-Path $StageDir) {
    Remove-Item $StageDir -Recurse -Force
}
New-Item -ItemType Directory -Path $StageDllDir -Force | Out-Null

Copy-Item $JarOut       -Destination $StageDir   -Force
Copy-Item $DllSrc       -Destination $StageDllDir -Force

Write-Host "    Staged JAR : $StageDir\scanner-bridge.jar"
Write-Host "    Staged DLL : $StageDllDir\jacob-1.21-x64.dll"

# ---------------------------------------------------------------------------
# Step 4: Run jpackage
# ---------------------------------------------------------------------------
Write-Step "Step 4: Running jpackage (this may take a minute)"

# Recreate dist dir cleanly
if (Test-Path $DistDir) {
    Remove-Item $DistDir -Recurse -Force
}
New-Item -ItemType Directory -Path $DistDir -Force | Out-Null

$jpackageArgs = @(
    "--name",          $AppName,
    "--app-version",   $AppVersion,
    "--input",         $StageDir,
    "--main-jar",      "scanner-bridge.jar",
    "--main-class",    $MainClass,
    "--type",          "app-image",
    "--dest",          $DistDir,
    "--java-options",  "`"`$APPDIR/lib`"",          # â† resolved by jpackage at install time
    "--java-options",  "-Dspring.profiles.active=prod"
)

# Prepend the library-path option separately so quoting is unambiguous
$jpackageArgs = @(
    "--name",          $AppName,
    "--app-version",   $AppVersion,
    "--input",         $StageDir,
    "--main-jar",      "scanner-bridge.jar",
    "--main-class",    $MainClass,
    "--type",          "app-image",
    "--dest",          $DistDir,
    "--java-options",  "-Djava.library.path=`$APPDIR/lib",
    "--java-options",  "-Dspring.profiles.active=prod"
)

# Append icon if it exists
if (Test-Path $IcoPath) {
    $jpackageArgs += "--icon"
    $jpackageArgs += $IcoPath
    Write-Host "    Using icon: $IcoPath"
} else {
    Write-Host "    No tray-icon.ico found - skipping --icon flag"
}

# Initialise $jpackageRC so Set-StrictMode cannot complain about it being
# undefined, then run jpackage with strict mode and error-preference relaxed.
[int]$jpackageRC = 0
Set-StrictMode -Off
$ErrorActionPreference = "Continue"
& jpackage @jpackageArgs
[int]$jpackageRC = $LASTEXITCODE
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
if ($jpackageRC -ne 0) {
    Fail "jpackage failed (exit code $jpackageRC)."
}

# ---------------------------------------------------------------------------
# Step 4b: Verify WinSW is present
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "Step 4b: Checking for WinSW service wrapper..." -ForegroundColor Cyan
$winswPath = "$PSScriptRoot\ScannerBridgeService.exe"
if (-not (Test-Path $winswPath)) {
    Write-Host ""
    Write-Host "ERROR: ScannerBridgeService.exe not found." -ForegroundColor Red
    Write-Host "Download WinSW-x64.exe from: https://github.com/winsw/winsw/releases"
    Write-Host "Rename it to ScannerBridgeService.exe and place it in: $PSScriptRoot"
    exit 1
}
Write-Host "WinSW found." -ForegroundColor Green

# ---------------------------------------------------------------------------
# Step 4c: Generate integrity manifest for installer contents (SEC-15)
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "Step 4c: Generating integrity manifest..." -ForegroundColor Cyan

$appImageDir = "$PSScriptRoot\target\dist\Scanner Bridge"
$manifestPath = "$PSScriptRoot\target\dist\MANIFEST.sha256"

Get-ChildItem -Recurse -File $appImageDir |
    ForEach-Object {
        $hash = (Get-FileHash -Algorithm SHA256 $_.FullName).Hash
        $rel  = $_.FullName.Substring($appImageDir.Length + 1)
        "$hash  $rel"
    } | Set-Content -Encoding UTF8 $manifestPath

Write-Host "Manifest written to: $manifestPath" -ForegroundColor Green
Write-Host "Keep this file alongside the installer for post-install verification."

# ---------------------------------------------------------------------------
# Step 5: Build installer with Inno Setup (if ISCC is available)
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "Step 5: Building installer..." -ForegroundColor Cyan

$iscc = $null
# Common Inno Setup install locations
$innoLocations = @(
    "C:\Program Files (x86)\Inno Setup 6\ISCC.exe",
    "C:\Program Files\Inno Setup 6\ISCC.exe",
    "C:\Program Files (x86)\Inno Setup 5\ISCC.exe"
)
foreach ($loc in $innoLocations) {
    if (Test-Path $loc) { $iscc = $loc; break }
}
# Also check PATH
if (-not $iscc) {
    $isccCmd = Get-Command ISCC -ErrorAction SilentlyContinue
    if ($isccCmd) { $iscc = $isccCmd.Source }
}

if ($iscc) {
    New-Item -ItemType Directory -Force -Path "$PSScriptRoot\target\installer" | Out-Null
    & $iscc "$PSScriptRoot\installer.iss"
    if ($LASTEXITCODE -eq 0) {
        Write-Host ""
        Write-Host "SUCCESS" -ForegroundColor Green
        Write-Host "Installer: $PSScriptRoot\target\installer\ScannerBridgeSetup.exe"
        Write-Host "Ship this single file to users."
    } else {
        Write-Host "Inno Setup compilation failed." -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "Inno Setup not found - skipping installer build." -ForegroundColor Yellow
    Write-Host "App image is available at: $PSScriptRoot\target\dist\Scanner Bridge" -ForegroundColor Yellow
    Write-Host "Download Inno Setup 6 from https://jrsoftware.org/isdl.php and re-run build.ps1"
}
