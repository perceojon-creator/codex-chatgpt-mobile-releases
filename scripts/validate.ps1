<#
.SYNOPSIS
  Arnes de validacion JVM en tiempo real para Codex ChatGPT Mobile.
.DESCRIPTION
  Ejecuta la suite de tests unitarios, parsea los XML de resultados,
  imprime metricas reales y compara contra scripts/baseline.json.
  Sale con codigo 1 si hay fallos o si el numero de tests baja respecto
  a la linea base (deteccion de regresion por borrado de tests).
.PARAMETER Filter
  Patron --tests de Gradle para ejecutar un subconjunto.
.PARAMETER UpdateBaseline
  Reescribe scripts/baseline.json con el resultado actual.
#>
param(
    [string]$Filter = "",
    [switch]$UpdateBaseline
)

$ErrorActionPreference = "Stop"
$ProjectDir   = Split-Path -Parent $PSScriptRoot
$ResultsDir   = Join-Path $ProjectDir "app\build\test-results\testDebugUnitTest"
$BaselineFile = Join-Path $PSScriptRoot "baseline.json"

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

# Los XML viejos falsean el conteo si Gradle no reejecuta todo.
if (Test-Path $ResultsDir) { Remove-Item "$ResultsDir\*.xml" -Force -ErrorAction SilentlyContinue }

$gradleArgs = @("testDebugUnitTest", "--continue", "--console=plain")
if ($Filter -ne "") { $gradleArgs += @("--tests", $Filter) }

Write-Host "=== Ejecutando: gradlew $($gradleArgs -join ' ') ===" -ForegroundColor Cyan
$sw = [System.Diagnostics.Stopwatch]::StartNew()
Push-Location $ProjectDir
& .\gradlew.bat @gradleArgs 2>&1 | Select-String -Pattern "FAILED|BUILD |error:|Task :app:testDebugUnitTest"
$gradleExit = $LASTEXITCODE
Pop-Location
$sw.Stop()

if (-not (Test-Path $ResultsDir)) {
    Write-Host "ERROR: no se generaron resultados en $ResultsDir" -ForegroundColor Red
    exit 1
}

$xmlFiles = Get-ChildItem -Path $ResultsDir -Filter "*.xml"
$tests = 0; $failures = 0; $errors = 0; $skipped = 0; $suiteTime = 0.0
$failedNames = @()

foreach ($f in $xmlFiles) {
    $doc = [xml](Get-Content -LiteralPath $f.FullName -Encoding UTF8)
    $ts  = $doc.testsuite
    $tests    += [int]$ts.tests
    $failures += [int]$ts.failures
    $errors   += [int]$ts.errors
    $skipped  += [int]$ts.skipped
    $suiteTime += [double]$ts.time
    foreach ($tc in $ts.testcase) {
        if ($tc.failure -or $tc.error) { $failedNames += "$($ts.name).$($tc.name)" }
    }
}

Write-Host ""
Write-Host "=== RESULTADO DE VALIDACION ===" -ForegroundColor Cyan
Write-Host ("  Clases de test : {0}" -f $xmlFiles.Count)
Write-Host ("  Tests          : {0}" -f $tests)
Write-Host ("  Fallos         : {0}" -f $failures)
Write-Host ("  Errores        : {0}" -f $errors)
Write-Host ("  Omitidos       : {0}" -f $skipped)
Write-Host ("  Tiempo suite   : {0:N2} s" -f $suiteTime)
Write-Host ("  Tiempo total   : {0:N1} s" -f $sw.Elapsed.TotalSeconds)

if ($failedNames.Count -gt 0) {
    Write-Host ""
    Write-Host "--- TESTS FALLIDOS ---" -ForegroundColor Red
    $failedNames | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
}

if ($UpdateBaseline) {
    @{ tests = $tests; classes = $xmlFiles.Count; updatedAt = (Get-Date -Format "o") } |
        ConvertTo-Json | Set-Content -LiteralPath $BaselineFile -Encoding UTF8
    Write-Host ""
    Write-Host "Linea base actualizada: $tests tests / $($xmlFiles.Count) clases" -ForegroundColor Yellow
    exit 0
}

$regression = $false
if ((Test-Path $BaselineFile) -and ($Filter -eq "")) {
    $base = Get-Content -LiteralPath $BaselineFile -Raw | ConvertFrom-Json
    Write-Host ""
    Write-Host ("  Linea base     : {0} tests / {1} clases" -f $base.tests, $base.classes)
    if ($tests -lt $base.tests) {
        Write-Host ("REGRESION: {0} tests ahora vs {1} en linea base." -f $tests, $base.tests) -ForegroundColor Red
        $regression = $true
    }
}

if ($failures -gt 0 -or $errors -gt 0 -or $regression -or $gradleExit -ne 0) {
    Write-Host ""
    Write-Host "VALIDACION FALLIDA" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "VALIDACION CORRECTA" -ForegroundColor Green
exit 0