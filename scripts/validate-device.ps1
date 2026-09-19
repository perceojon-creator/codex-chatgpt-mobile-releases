<#
.SYNOPSIS
  Arnes de validacion en dispositivo real / emulador.
.DESCRIPTION
  1. Verifica que hay un dispositivo conectado (arranca el AVD si no lo hay).
  2. Ejecuta la suite instrumentada completa.
  3. Lanza los 5 benchmarks headless via TuiBenchmarkActivity y recoge logcat.
#>
param([switch]$SkipBenchmarks)

$ErrorActionPreference = "Stop"
$ProjectDir = Split-Path -Parent $PSScriptRoot
$Sdk  = "C:\Users\Admin\AppData\Local\Android\Sdk"
$Adb  = "$Sdk\platform-tools\adb.exe"
$Emu  = "$Sdk\emulator\emulator.exe"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

$devices = & $Adb devices | Select-String -Pattern "device$"
if ($devices.Count -eq 0) {
    Write-Host "No hay dispositivos. Arrancando AVD Pixel_10_Pro_XL..." -ForegroundColor Yellow
    Start-Process -FilePath $Emu -ArgumentList "-avd","Pixel_10_Pro_XL","-no-snapshot-load" -WindowStyle Minimized
    & $Adb wait-for-device
    Write-Host "Esperando arranque completo del sistema..."
    do {
        Start-Sleep -Seconds 3
        $boot = (& $Adb shell getprop sys.boot_completed 2>$null).Trim()
    } while ($boot -ne "1")
    Start-Sleep -Seconds 5
}
Write-Host "Dispositivo listo:" -ForegroundColor Green
& $Adb devices

Write-Host ""
Write-Host "=== Suite instrumentada (connectedDebugAndroidTest) ===" -ForegroundColor Cyan
Push-Location $ProjectDir
& .\gradlew.bat connectedDebugAndroidTest --console=plain 2>&1 |
    Select-String -Pattern "FAILED|BUILD |tests completed|error:"
$instrExit = $LASTEXITCODE
Pop-Location

if ($SkipBenchmarks) { exit $instrExit }

Write-Host ""
Write-Host "=== Benchmarks headless (5 suites) ===" -ForegroundColor Cyan
& $Adb logcat -c
& $Adb shell am start -n com.codex.chat/.benchmark.TuiBenchmarkActivity --es suite "all" | Out-Null
Write-Host "Ejecutando... (~40 s en el dispositivo de referencia)"
Start-Sleep -Seconds 40
$log = & $Adb logcat -d -s "TuiBenchmark:*" "MasterBenchmark:*"
$log | ForEach-Object { Write-Host "  $_" }

$passed = ($log | Select-String -Pattern "PASS").Count
$failed = ($log | Select-String -Pattern "FAIL").Count
Write-Host ""
Write-Host ("Benchmarks: {0} PASS / {1} FAIL" -f $passed, $failed)

if ($instrExit -ne 0 -or $failed -gt 0) {
    Write-Host "VALIDACION DE DISPOSITIVO FALLIDA" -ForegroundColor Red
    exit 1
}
Write-Host "VALIDACION DE DISPOSITIVO CORRECTA" -ForegroundColor Green
exit 0