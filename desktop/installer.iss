; =====================================================================
;  PDF Converter - Windows Setup installer (Inno Setup 6)
;  Build a proper setup.exe that installs the app on Windows
;  with Start Menu + Desktop shortcuts and uninstaller.
;  Requires: Inno Setup 6 (https://jrsoftware.org/isinfo.php)
;  Usage: build dist\PDFConverter.exe first, then compile this file
;         with the Inno Setup compiler (ISCmpl.exe installer.iss)
; =====================================================================

#define MyAppName "PDF Converter"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "tadkeera"
#define MyAppExeName "PDFConverter.exe"

[Setup]
AppId={{4A7C9E2B-8F3D-4B5A-9C1E-PDFCONVERTER01}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\PDF Converter
DefaultGroupName={#MyAppName}
AllowNoIcons=yes
OutputDir=installer
OutputBaseFilename=PDFConverter-Setup-{#MyAppVersion}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
SetupIconFile=assets\icon.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
PrivilegesRequired=admin

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "arabic";  MessagesFile: "compiler:Languages\Arabic.isl"

[Files]
Source: "dist\{#MyAppExeName}"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\Uninstall {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Additional icons:"

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Launch {#MyAppName}"; Flags: nowait postinstall skipifsilent
