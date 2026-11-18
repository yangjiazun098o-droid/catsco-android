param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$adb = 'E:/CodexProjects/.tools/android/sdk/platform-tools/adb.exe'
$phones = @((& $adb devices -l) | ForEach-Object {
    if ($_ -match '^([^\s]+)\s+device\b' -and $Matches[1] -notmatch '^emulator-') { $Matches[1] }
})
if ($phones.Count -ne 1) { throw "需要恰好一台已授权的安卓真机，当前检测到 $($phones.Count) 台。" }
if (-not $SkipBuild) { & "$PSScriptRoot/build-local.ps1" -Tasks ':app:assembleDebug' }
$apk = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw '尚无 Debug APK。' }
& $adb -s $phones[0] install -r $apk
if ($LASTEXITCODE -ne 0) { throw '真机 APK 更新失败；请检查手机上的 USB 安装确认。' }
& $adb -s $phones[0] shell am start -n cc.catsco.android.debug/cc.catsco.android.MainActivity
if ($LASTEXITCODE -ne 0) { throw '真机 App 启动失败。' }
Write-Output 'CatsCo 已更新到安卓真机。'
