$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$adb = 'E:/CodexProjects/.tools/android/sdk/platform-tools/adb.exe'
& "$PSScriptRoot/start-emulator.ps1"
$ready = $false
for ($attempt = 0; $attempt -lt 120; $attempt++) {
    $boot = & $adb -s emulator-5556 shell getprop sys.boot_completed 2>$null
    if ($boot -and $boot.Trim() -eq '1') { $ready = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $ready) { throw 'Emulator did not boot within 4 minutes. Check build/emulator logs.' }
& $adb -s emulator-5556 shell wm size 540x1200
& $adb -s emulator-5556 shell wm density 210
& "$PSScriptRoot/update-device.ps1" -SkipBuild
$running = $false
try {
    $response = Invoke-WebRequest -Uri 'http://127.0.0.1:5189/' -TimeoutSec 3 -UseBasicParsing
    if ($response.Content -notmatch 'CatsCo · 安卓原生设备') { throw 'Port 5189 belongs to another service.' }
    $running = $true
} catch [System.Net.Http.HttpRequestException] { }
if (-not $running) {
    $node = Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe'
    $logRoot = "$projectRoot/build/emulator"
    Start-Process -FilePath $node -ArgumentList "$PSScriptRoot/native-preview.mjs" -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput "$logRoot/preview.log" -RedirectStandardError "$logRoot/preview-error.log" | Out-Null
}
Write-Output 'Native device preview: http://127.0.0.1:5189/'
