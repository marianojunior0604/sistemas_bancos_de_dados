# H2 Studio - gera o instalador para Windows
#
# Uso (PowerShell, na pasta do projeto):
#   .\empacotar.ps1                 -> compila, gera dist\H2 Studio (exe + Java embutido)
#                                      e o instalador dist\instalador\H2Studio-<versão>-instalador.exe
#   .\empacotar.ps1 -SemInstalador  -> só a pasta dist\H2 Studio (roda direto, sem instalar)
#   .\empacotar.ps1 -SoJar          -> só compila e monta dist\h2studio (jar + drivers)
#
# O instalador precisa do Inno Setup 6 (grátis):  winget install JRSoftware.InnoSetup
# A versão vem do <version> do pom.xml.
#
# Se o PowerShell bloquear o script:
#   Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass

param(
    [switch]$SoJar,
    [switch]$SemInstalador
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# ---------- versão (do pom.xml) ----------
[xml]$pom = Get-Content (Join-Path $PSScriptRoot "pom.xml") -Encoding UTF8
$versao = $pom.project.version
if (-not ($versao -match '^\d+\.\d+\.\d+$')) { throw "Versão inválida no pom.xml: '$versao' (use 1.2.3, sem -SNAPSHOT)." }
Write-Host "Versão: $versao"

# ---------- localizar JDK 21 ----------
$jdkCandidatos = @($env:JAVA_HOME, "C:\Program Files\OpenJDK\jdk-21", "C:\Program Files\Java\jdk-21")
$jdk = $jdkCandidatos | Where-Object { $_ -and (Test-Path (Join-Path $_ "bin\java.exe")) } | Select-Object -First 1
if (-not $jdk) {
    $jdk = Get-ChildItem "C:\Program Files\OpenJDK", "C:\Program Files\Java", "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match "21" -and (Test-Path (Join-Path $_.FullName "bin\jpackage.exe")) } |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $jdk) { throw "JDK 21 não encontrado. Defina JAVA_HOME." }
$env:JAVA_HOME = $jdk
Write-Host "JDK:   $jdk"

# ---------- localizar Maven ----------
$mvn = (Get-Command mvn.cmd -ErrorAction SilentlyContinue).Source
if (-not $mvn) {
    $mvn = Get-ChildItem "C:\Program Files\Apache NetBeans*", "C:\Program Files\NetBeans*" -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName "java\maven\bin\mvn.cmd" } |
        Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $mvn) { throw "Maven não encontrado (nem no PATH nem no NetBeans)." }
Write-Host "Maven: $mvn"

# ---------- build ----------
& $mvn -q clean package
if ($LASTEXITCODE -ne 0) { throw "Falha no build do Maven." }

$app = Join-Path $PSScriptRoot "dist\h2studio"
if (Test-Path $app) { Remove-Item $app -Recurse -Force }
New-Item -ItemType Directory -Path (Join-Path $app "drivers") -Force | Out-Null
Copy-Item "target\h2studio.jar" $app
Copy-Item "target\drivers\*.jar" (Join-Path $app "drivers")
Write-Host "Jar + drivers em: $app"

if ($SoJar) {
    Write-Host "Para rodar: java -jar `"$app\h2studio.jar`""
    exit 0
}

# ---------- jpackage (app-image com JRE embutido) ----------
$saida = Join-Path $PSScriptRoot "dist"
$imagem = Join-Path $saida "H2 Studio"
if (Test-Path $imagem) { Remove-Item $imagem -Recurse -Force }

# Módulos do Java embutido: os do programa e os que os drivers usam (levantados com jdeps),
# mais locale pt, charsets antigos (DOS850 do Firebird) e criptografia para conexões com SSL.
$modulos = @(
    "java.base", "java.desktop", "java.sql", "java.sql.rowset", "java.naming", "java.management",
    "java.security.jgss", "java.net.http", "java.compiler", "java.instrument", "java.scripting", "java.xml",
    "java.logging", "java.prefs", "java.datatransfer", "jdk.net", "jdk.unsupported", "jdk.crypto.ec",
    "jdk.crypto.cryptoki", "jdk.charsets", "jdk.localedata", "jdk.security.auth", "jdk.security.jgss",
    "jdk.naming.dns", "jdk.zipfs", "jdk.management", "jdk.accessibility"
) -join ","

& (Join-Path $jdk "bin\jpackage.exe") `
    --type app-image `
    --name "H2 Studio" `
    --app-version $versao `
    --vendor "Mariano Junior" `
    --copyright "© 2026 Mariano Junior" `
    --description "Gerenciador de bancos de dados" `
    --icon (Join-Path $PSScriptRoot "instalador\h2studio.ico") `
    --input $app `
    --main-jar h2studio.jar `
    --main-class br.com.marianojunior.h2studio.Launcher `
    --add-modules $modulos `
    --jlink-options "--strip-debug --no-man-pages --no-header-files --strip-native-commands --include-locales=en,pt" `
    --dest $saida `
    --java-options "-Xmx1g" `
    --java-options "-Dfile.encoding=UTF-8"
if ($LASTEXITCODE -ne 0) { throw "Falha no jpackage." }

Write-Host ""
Write-Host "Programa: $imagem\H2 Studio.exe"

if ($SemInstalador) {
    exit 0
}

# ---------- instalador (Inno Setup) ----------
$iscc = @(
    (Get-Command ISCC.exe -ErrorAction SilentlyContinue).Source,
    "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
    "$env:ProgramFiles\Inno Setup 6\ISCC.exe",
    "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe"
) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $iscc) {
    Write-Host ""
    Write-Host "Inno Setup 6 não encontrado: o instalador não foi gerado." -ForegroundColor Yellow
    Write-Host "Instale com:  winget install JRSoftware.InnoSetup   (ou baixe em jrsoftware.org)"
    Write-Host "e rode o script de novo. A pasta dist\H2 Studio já pode ser usada sem instalar."
    exit 0
}
Write-Host "Inno Setup: $iscc"
$saidaInstalador = Join-Path $saida "instalador"
& $iscc /Q "/DAppVersion=$versao" "/DSourceDir=$imagem" "/DOutputDir=$saidaInstalador" (Join-Path $PSScriptRoot "instalador\h2studio.iss")
if ($LASTEXITCODE -ne 0) { throw "Falha ao gerar o instalador (Inno Setup)." }

Write-Host ""
Write-Host "Instalador: $saidaInstalador\H2Studio-$versao-instalador.exe" -ForegroundColor Green
