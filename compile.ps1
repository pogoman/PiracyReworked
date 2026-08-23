# Builds jars/PiratePat.jar from src/. Requires a JDK (javac + jar) on PATH,
# or set $env:JAVA_HOME to a JDK install.
$ErrorActionPreference = "Stop"

$core = "C:\Program Files (x86)\Fractal Softworks\Starsector\starsector-core"
$mods = "C:\Program Files (x86)\Fractal Softworks\Starsector\mods"
$luna = "$mods\LunaLib-2.0.4\jars\LunaLib.jar"
$cp = "$core\starfarer.api.jar;$core\lwjgl.jar;$core\lwjgl_util.jar;$core\log4j-1.2.9.jar;$core\json.jar;$luna"

$javac = "javac"
$jar = "jar"
if ($env:JAVA_HOME) {
    $javac = Join-Path $env:JAVA_HOME "bin\javac.exe"
    $jar = Join-Path $env:JAVA_HOME "bin\jar.exe"
}

$out = Join-Path $PSScriptRoot "classes"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null

$sources = Get-ChildItem -Recurse (Join-Path $PSScriptRoot "src") -Filter *.java | ForEach-Object FullName

& $javac -cp $cp -d $out @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

New-Item -ItemType Directory -Force (Join-Path $PSScriptRoot "jars") | Out-Null
& $jar cf (Join-Path $PSScriptRoot "jars\PiratePat.jar") -C $out .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Write-Host "Built jars\PiratePat.jar"
