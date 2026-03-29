# Scanner Bridge — Shipping Guide
> For developers and IT administrators responsible for building and distributing the installer.

---

## Overview

The build pipeline produces a single file: **`ScannerBridgeSetup.exe`**.

Ship that file to end users. They run it once. Scanner Bridge installs as a Windows Service and starts automatically on every boot — users never interact with it again.

```
Developer machine
  └── build.ps1
        ├── mvn clean package        → fat JAR (Spring Boot + bundled deps)
        ├── jpackage                 → self-contained app (bundled JRE, no Java needed on user machine)
        └── Inno Setup               → ScannerBridgeSetup.exe  ← ship this
```

---

## Prerequisites

These are required on the **developer/build machine only** — not on user machines.

| Requirement | Version | Notes |
|---|---|---|
| JDK | 17 or later | Must include `jpackage` (JDK 14+). Verify: `jpackage --version` |
| Maven | 3.6+ | Verify: `mvn --version` |
| Inno Setup | 6.x | [Download](https://jrsoftware.org/isdl.php). Used to build the installer exe. |
| `jacob-1.21.jar` | 1.21 | Place in `./lib/`. See `lib/README.txt` for source. |
| `jacob-1.21-x64.dll` | 1.21 | Place in `./lib/`. Same source as JAR. |
| `ScannerBridgeService.exe` | latest | Download `WinSW-x64.exe` from [github.com/winsw/winsw/releases](https://github.com/winsw/winsw/releases). Rename to `ScannerBridgeService.exe` and place in the project root. |

### Verify your setup before building

```powershell
jpackage --version        # should print 17 or higher
mvn --version             # should print 3.6+
& "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" /?   # should print Inno Setup help
Test-Path .\lib\jacob-1.21.jar              # should print True
Test-Path .\lib\jacob-1.21-x64.dll         # should print True
Test-Path .\ScannerBridgeService.exe        # should print True
```

---

## Step 1 — Configure for Production

Before building, set the production domain so the bridge accepts WebSocket connections from your web application.

Open `src/main/resources/application.yml` and add your domain:

```yaml
scanner:
  allowed-origins:
    - "https://your-app.agency.gov"      # ← add this
    - "http://localhost:3000"             # keep for local dev
```

You can add multiple origins if needed (e.g. staging and production).

---

## Step 1b — Set the Authentication Token

Scanner Bridge requires a shared secret token to authenticate the webapp. Any WebSocket connection without this token is rejected.

**Generate a strong token:**
```powershell
# Run this once to generate a secure random token
[Convert]::ToBase64String((1..32 | ForEach-Object { [byte](Get-Random -Max 256) }))
```

**Set it in `application.yml`:**
```yaml
scanner:
  auth:
    token: "paste-your-generated-token-here"
```

**Set the same token in your webapp** — add this to the HTML page that hosts the scanner, rendered server-side so the token is never in source control:
```html
<script>
  window.SCANNER_BRIDGE_URL   = 'ws://localhost:8765/scanner';
  window.SCANNER_BRIDGE_TOKEN = '{{ your_server_renders_this }}';
</script>
```

**Never commit the token to source control.** Use environment variables or a secrets manager.

---

## Step 2 — Build

From the project root in PowerShell:

```powershell
.\build.ps1
```

The script runs automatically:
1. Verifies all prerequisites
2. Compiles and packages the Spring Boot fat JAR (`mvn clean package`)
3. Runs `jpackage` to bundle the JRE and create a self-contained Windows app
4. Verifies the WinSW service wrapper is present
5. Compiles the Inno Setup script into the final installer

**Output:** `target\installer\ScannerBridgeSetup.exe`

Build time is typically 2–5 minutes on first run (Maven downloads dependencies), under a minute on subsequent builds.

---

## Step 3 — Test the Installer (Recommended)

Before shipping, test the installer on a clean Windows machine or VM:

1. Run `ScannerBridgeSetup.exe` — approve the UAC prompt, complete the wizard
2. Open `services.msc` → verify "Scanner Bridge" is listed and **Running**
3. Open your web application → attempt a scan → verify the WebSocket connects
4. Reboot the machine → verify the service starts automatically without user action
5. Uninstall via Control Panel → verify the service is removed cleanly

---

## Step 4 — Ship

Distribute `target\installer\ScannerBridgeSetup.exe` to users via your standard software distribution channel (email, internal portal, MDM/SCCM deployment, shared drive, etc.).

Include the `USER_INSTALL_GUIDE.md` (or its contents) as user-facing instructions. A one-paragraph summary suitable for an email:

> *Please download and run ScannerBridgeSetup.exe. When prompted, approve the administrator access request and follow the on-screen steps. Installation takes under a minute. Once installed, you will be able to scan documents directly from [App Name] — no further setup is required.*

---

## Updating / Re-shipping

When you need to push an update (configuration change, bug fix, new version):

1. Make your changes
2. Re-run `.\build.ps1`
3. Ship the new `ScannerBridgeSetup.exe`

The installer handles upgrades cleanly — it stops the running service, replaces the files, and restarts the service. Users do not need to uninstall first.

**Token rotation:** If you are updating because the auth token needs to be rotated (e.g. a developer with access has left, or per your organisation's security policy), generate a new token, update `application.yml`, rebuild, redeploy to users, and update the webapp configuration at the same time.

---

## Pre-Ship Security Checklist

Before building the final installer, verify:

- [ ] `scanner.auth.token` in `application.yml` is set to a strong random value (not the placeholder)
- [ ] `scanner.allowed-origins` contains only your production domain (not localhost)
- [ ] JACOB SHA256 checksums are set in `build.ps1` Step 1b (see below)
- [ ] `mvn dependency-check:check` has been run and passes (no HIGH/CRITICAL CVEs)
- [ ] The installer `.exe` is signed with your organisation's code signing certificate
- [ ] The token is configured in the webapp and rendered server-side
- [ ] The build machine is trusted and access-controlled

**Setting the JACOB checksums** — do this once after you download the JACOB files, then commit the updated `build.ps1`:
```powershell
Get-FileHash -Algorithm SHA256 .\lib\jacob-1.21.jar
Get-FileHash -Algorithm SHA256 .\lib\jacob-1.21-x64.dll
# Paste both Hash values into build.ps1 Step 1b, replacing the REPLACE_WITH_SHA256... placeholders
```

**Running the dependency vulnerability scan:**
```powershell
mvn dependency-check:check
# First run downloads the NVD database (~300 MB) and takes 5-10 minutes
# Subsequent runs are faster
# Set NVD_API_KEY environment variable for faster database updates (free key at nvd.nist.gov)
```

---

## Managing the Service Remotely

For IT administrators managing multiple machines:

```powershell
# Check service status
Get-Service -Name "ScannerBridge"

# Start / stop / restart
Start-Service  "ScannerBridge"
Stop-Service   "ScannerBridge"
Restart-Service "ScannerBridge"

# Silent install (no UI, no reboot prompt)
.\ScannerBridgeSetup.exe /VERYSILENT /SUPPRESSMSGBOXES

# Silent uninstall
"C:\Program Files\Scanner Bridge\unins000.exe" /VERYSILENT
```

---

## Logs

Service logs are written alongside the installed files:

```
C:\Program Files\Scanner Bridge\ScannerBridgeService.out.log   ← stdout
C:\Program Files\Scanner Bridge\ScannerBridgeService.err.log   ← stderr
```

Logs roll at 10 MB, keeping 3 files. Check these first when diagnosing issues.

---

## Troubleshooting (Build)

| Problem | Fix |
|---|---|
| `jpackage: command not found` | Ensure JDK 17+ is installed and `JAVA_HOME` is set. JRE alone does not include `jpackage`. |
| `artifact not found: jacob` | Ensure `lib/jacob-1.21.jar` exists. The stub JAR in the repo is for testing only — replace with the real JAR for a production build. |
| `ISCC.exe not found` | Install Inno Setup 6 from jrsoftware.org. The build script checks common install paths automatically. |
| `ScannerBridgeService.exe not found` | Download WinSW-x64.exe, rename it, place it in the project root. |
| Build succeeds but installer crashes on user machine | Verify the target machine is Windows 10 or later (64-bit). The bundled JRE requires 64-bit Windows. |
| `401 Unauthorized` on WebSocket | Token mismatch — verify `scanner.auth.token` in `application.yml` matches `window.SCANNER_BRIDGE_TOKEN` in the webapp. |
| Dependency check fails (CVE found) | Review finding at nvd.nist.gov — update the affected dependency or add a suppression with justification to `dependency-check-suppressions.xml`. |
