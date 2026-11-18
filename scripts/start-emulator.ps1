param([switch]$ShowWindow)
$ErrorActionPreference = 'Stop'
$toolRoot = 'E:/CodexProjects/.tools/android'
$env:ANDROID_HOME = "$toolRoot/sdk"
$env:ANDROID_AVD_HOME = "$toolRoot/avd"
$env:ANDROID_EMULATOR_HOME = "$toolRoot/emulator-home"
$emulator = "$toolRoot/sdk/emulator/emulator.exe"
$adb = "$toolRoot/sdk/platform-tools/adb.exe"
$serial = 'emulator-5556'
$deviceList = & $adb devices
if ($deviceList -match "^$serial\s+device") { Write-Output "$serial already running"; exit 0 }
if ($deviceList -match "^$serial\s+offline") { Write-Output "$serial is still starting"; exit 0 }
$logRoot = Join-Path (Split-Path $PSScriptRoot -Parent) 'build/emulator'
New-Item -ItemType Directory -Force $logRoot | Out-Null
$arguments = @('-avd', 'CatsCo_API_36', '-port', '5556', '-memory', '2048', '-cores', '2', '-gpu', 'swiftshader', '-feature', '-Vulkan', '-no-boot-anim', '-no-snapshot', '-no-audio', '-no-metrics')
if (-not $ShowWindow) { $arguments += '-no-window' }
$windowStyle = if ($ShowWindow) { 'Normal' } else { 'Hidden' }
Start-Process -FilePath $emulator -ArgumentList $arguments -WindowStyle $windowStyle -RedirectStandardOutput "$logRoot/stdout.log" -RedirectStandardError "$logRoot/stderr.log" | Out-Null
Write-Output 'Starting CatsCo_API_36 on emulator-5556. Boot readiness must be checked with adb.'
