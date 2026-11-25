; =====================================================================
;  H2 Studio - instalador para Windows (Inno Setup 6.3 ou mais novo)
;
;  Normalmente é chamado pelo empacotar.ps1, que gera antes a pasta
;  dist\H2 Studio (programa + Java embutido) e passa a versão do pom.xml.
;  Para compilar à mão:
;    ISCC.exe /DAppVersion=1.10.0 instalador\h2studio.iss
; =====================================================================

#ifndef AppVersion
  #define AppVersion "1.10.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\dist\H2 Studio"
#endif
#ifndef OutputDir
  #define OutputDir "..\dist\instalador"
#endif

#define AppName "H2 Studio"
#define AppExe "H2 Studio.exe"
#define AppPublisher "Mariano Junior"
#define ProgId "H2Studio.Banco"

[Setup]
; NÃO mude o AppId: é por ele que o Windows reconhece uma atualização
AppId={{6F2B9C4E-3A71-4D58-9E0B-2C8F5A1D7E43}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
AppCopyright=© 2026 {#AppPublisher}
AppComments=Gerenciador visual de bancos H2, PostgreSQL, MySQL/MariaDB, Firebird, SQL Server e SQLite
VersionInfoVersion={#AppVersion}
VersionInfoDescription=Instalador do {#AppName}
VersionInfoProductName={#AppName}
VersionInfoProductVersion={#AppVersion}

; "Para todos os usuários" (pede administrador, vai para Arquivos de Programas) ou
; "Só para mim" (sem administrador, vai para %LOCALAPPDATA%\Programs)
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
DefaultDirName={autopf}\{#AppName}
DisableProgramGroupPage=yes
UsePreviousAppDir=yes

ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0

OutputDir={#OutputDir}
OutputBaseFilename=H2Studio-{#AppVersion}-instalador
Compression=lzma2/max
SolidCompression=yes

WizardStyle=modern
SetupIconFile=h2studio.ico
WizardImageFile=assistente-100.bmp,assistente-200.bmp
WizardSmallImageFile=assistente-pequeno-100.bmp,assistente-pequeno-200.bmp
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}

; fecha o H2 Studio aberto antes de atualizar os arquivos
CloseApplications=yes
RestartApplications=no
ChangesAssociations=yes
ShowLanguageDialog=no

[Languages]
Name: "ptbr"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[CustomMessages]
ptbr.AssocGroup=Abrir bancos com dois cliques:
ptbr.AssocSqlite=Arquivos SQLite (.sqlite, .sqlite3)
ptbr.AssocFbk=Backups do Firebird (.fbk): restaurar como banco
ptbr.FileTypeName=Banco de dados (H2 Studio)
ptbr.RemoveData=Apagar também os dados do H2 Studio deste usuário?%n%n%1%n%nLá ficam as conexões salvas, o histórico de SQL, os drivers baixados e os BACKUPS feitos pelo programa.%n%nEscolha "Não" para manter (por exemplo, se for reinstalar).

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"
Name: "assocsqlite"; Description: "{cm:AssocSqlite}"; GroupDescription: "{cm:AssocGroup}"; Flags: unchecked
Name: "assocfbk"; Description: "{cm:AssocFbk}"; GroupDescription: "{cm:AssocGroup}"; Flags: unchecked

[InstallDelete]
; numa atualização, tira o Java e os jars da versão anterior antes de copiar os novos
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"; Comment: "Gerenciador de bancos de dados"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Registry]
; tipo de arquivo "Banco de dados (H2 Studio)"
Root: HKA; Subkey: "Software\Classes\{#ProgId}"; ValueType: string; ValueName: ""; ValueData: "{cm:FileTypeName}"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\{#ProgId}\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: """{app}\{#AppExe}"",0"
Root: HKA; Subkey: "Software\Classes\{#ProgId}\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\{#AppExe}"" ""%1"""
; "Abrir com > H2 Studio" sem tomar o lugar de outro programa. O H2 grava "banco.mv.db",
; que para o Windows é extensão .db: por isso o .db só ganha a opção no "Abrir com".
Root: HKA; Subkey: "Software\Classes\.db\OpenWithProgids"; ValueType: string; ValueName: "{#ProgId}"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\.sqlite\OpenWithProgids"; ValueType: string; ValueName: "{#ProgId}"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\.sqlite3\OpenWithProgids"; ValueType: string; ValueName: "{#ProgId}"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\.fbk\OpenWithProgids"; ValueType: string; ValueName: "{#ProgId}"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\.gbk\OpenWithProgids"; ValueType: string; ValueName: "{#ProgId}"; ValueData: ""; Flags: uninsdeletevalue
; opcional: dois cliques em .sqlite/.sqlite3 abrem no H2 Studio
Root: HKA; Subkey: "Software\Classes\.sqlite"; ValueType: string; ValueName: ""; ValueData: "{#ProgId}"; Tasks: assocsqlite; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\.sqlite3"; ValueType: string; ValueName: ""; ValueData: "{#ProgId}"; Tasks: assocsqlite; Flags: uninsdeletevalue
; opcional: dois cliques em .fbk abrem a restauração no H2 Studio
Root: HKA; Subkey: "Software\Classes\.fbk"; ValueType: string; ValueName: ""; ValueData: "{#ProgId}"; Tasks: assocfbk; Flags: uninsdeletevalue
; o programa aparece na lista do "Abrir com"
Root: HKA; Subkey: "Software\Classes\Applications\{#AppExe}"; ValueType: string; ValueName: "FriendlyAppName"; ValueData: "{#AppName}"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\{#AppExe}\SupportedTypes"; ValueType: string; ValueName: ".db"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\Applications\{#AppExe}\SupportedTypes"; ValueType: string; ValueName: ".sqlite"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\Applications\{#AppExe}\SupportedTypes"; ValueType: string; ValueName: ".sqlite3"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\Applications\{#AppExe}\SupportedTypes"; ValueType: string; ValueName: ".fbk"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\Applications\{#AppExe}\SupportedTypes"; ValueType: string; ValueName: ".gbk"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\Applications\{#AppExe}\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\{#AppExe}"" ""%1"""

[Run]
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent

[Code]
// Ao desinstalar, pergunta (com "Não" como padrão) se apaga a pasta .h2studio do usuário.
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  Dir: String;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    Dir := ExpandConstant('{%USERPROFILE}\.h2studio');
    if DirExists(Dir) and not UninstallSilent then
    begin
      if MsgBox(FmtMessage(CustomMessage('RemoveData'), [Dir]), mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
        DelTree(Dir, True, True, True);
    end;
  end;
end;
