# H2 Studio - gera a pasta dist\H2 Studio (executável .exe com JRE embutido)
#
# Uso (PowerShell, na pasta do projeto):
#   .\empacotar.ps1              -> compila e gera o app-image com jpackage
#   .\empacotar.ps1 -SoJar       -> só compila e monta dist\h2studio (jar + drivers)
#
# Se o PowerShell bloquear o script:
#   Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass

param(
    [switch]$SoJar
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

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

& (Join-Path $jdk "bin\jpackage.exe") `
    --type app-image `
    --name "H2 Studio" `
    --app-version "1.6.0" `
    --vendor "Mariano Junior" `
    --input $app `
    --main-jar h2studio.jar `
    --main-class br.com.marianojunior.h2studio.Launcher `
    --dest $saida `
    --java-options "-Xmx1g" `
    --java-options "-Dfile.encoding=UTF-8"
if ($LASTEXITCODE -ne 0) { throw "Falha no jpackage." }

Write-Host ""
Write-Host "Pronto: $imagem\H2 Studio.exe"
