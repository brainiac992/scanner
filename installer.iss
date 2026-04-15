[Setup]
AppName=Scanner Bridge
AppVersion=1.0
AppPublisher=Your Organization
DefaultDirName={autopf}\Scanner Bridge
DefaultGroupName=Scanner Bridge
OutputDir=target\installer
OutputBaseFilename=ScannerBridgeSetup
Compression=lzma2
SolidCompression=yes
; Windows Services require admin rights
PrivilegesRequired=admin
WizardStyle=modern
AppMutex=ScannerBridgeMutex
; Service name for reference in [Run] and [UninstallRun]
#define ServiceName "ScannerBridge"

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
; jpackage app-image (the Spring Boot app with bundled JRE)
Source: "target\dist\Scanner Bridge\*"; DestDir: "{app}\app"; Flags: ignoreversion recursesubdirs createallsubdirs

; WinSW service wrapper — developer must place this in project root before building
; Download from: https://github.com/winsw/winsw/releases (WinSW-x64.exe), rename to ScannerBridgeService.exe
Source: "ScannerBridgeService.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "ScannerBridgeService.xml"; DestDir: "{app}"; Flags: ignoreversion

; Include integrity manifest for post-install verification
Source: "target\dist\MANIFEST.sha256"; DestDir: "{app}"; Flags: ignoreversion

[Run]
; Install the Windows Service
Filename: "{app}\ScannerBridgeService.exe"; Parameters: "install"; Flags: runhidden waituntilterminated; StatusMsg: "Installing Scanner Bridge service..."
; Start the service immediately (no need to reboot)
Filename: "{app}\ScannerBridgeService.exe"; Parameters: "start"; Flags: runhidden waituntilterminated; StatusMsg: "Starting Scanner Bridge service..."
; SEC-16: lock down install directory — grant full control to Administrators only,
; grant read+execute to LocalService (the service account), deny write to everyone else.
Filename: "icacls.exe"; Parameters: """{app}"" /inheritance:r /grant:r ""BUILTIN\Administrators:(OI)(CI)F"" /grant:r ""NT AUTHORITY\LocalService:(OI)(CI)RX"""; Flags: runhidden waituntilterminated; StatusMsg: "Securing installation directory..."

[UninstallRun]
; Stop the service before uninstall
Filename: "{app}\ScannerBridgeService.exe"; Parameters: "stop"; Flags: runhidden waituntilterminated; RunOnceId: "StopService"
; Uninstall the service
Filename: "{app}\ScannerBridgeService.exe"; Parameters: "uninstall"; Flags: runhidden waituntilterminated; RunOnceId: "UninstallService"

[Code]
// Stop and uninstall any existing service before upgrading
procedure StopExistingService();
var
  ResultCode: Integer;
begin
  Exec(ExpandConstant('{app}\ScannerBridgeService.exe'), 'stop', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec(ExpandConstant('{app}\ScannerBridgeService.exe'), 'uninstall', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssInstall then
    StopExistingService();
end;
