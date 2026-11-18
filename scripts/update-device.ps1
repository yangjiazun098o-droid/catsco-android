param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$adb = 'E:/CodexProjects/.tools/android/sdk/platform-tools/adb.exe'
if (-not $SkipBuild) { & "$PSScriptRoot/build-local.ps1" -Tasks ':app:assembleDebug' }
if ((& $adb -s emulator-5556 shell getprop sys.boot_completed).Trim() -ne '1') { throw 'Emulator not ready. Run start-emulator.ps1 and wait for boot.' }
& $adb -s emulator-5556 install -r "$projectRoot/app/build/outputs/apk/debug/app-debug.apk"
if ($LASTEXITCODE -ne 0) { throw 'APK install failed.' }
& $adb -s emulator-5556 shell am start -n cc.catsco.android.debug/cc.catsco.android.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'Activity launch failed.' }
