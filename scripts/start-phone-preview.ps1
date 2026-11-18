$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$adb = 'E:/CodexProjects/.tools/android/sdk/platform-tools/adb.exe'
$node = Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe'
$port = 5190

$rows = & $adb devices -l
$phones = @($rows | ForEach-Object {
    if ($_ -match '^([^\s]+)\s+device\b' -and $Matches[1] -notmatch '^emulator-') { $Matches[1] }
})
if ($phones.Count -ne 1) {
    throw "需要恰好一台已授权的安卓真机，当前检测到 $($phones.Count) 台。请连接 USB 数据线，在手机上开启 USB 调试并允许这台电脑调试。"
}
$serial = $phones[0]
$sizeText = (& $adb -s $serial shell wm size) -join "`n"
if ($sizeText -match 'Override size:\s*(\d+)x(\d+)') {
    $width = [int]$Matches[1]; $height = [int]$Matches[2]
} elseif ($sizeText -match 'Physical size:\s*(\d+)x(\d+)') {
    $width = [int]$Matches[1]; $height = [int]$Matches[2]
} else { throw '无法读取手机屏幕尺寸。' }
if ($height -le $width) { throw '请将手机旋转为竖屏后重试。' }
$videoWidth = [Math]::Min(540, [int]([Math]::Floor($width / 2) * 2))
$videoHeight = [int]([Math]::Floor(($height * $videoWidth / $width) / 2) * 2)

$listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
if ($listener) { throw "端口 $port 已被使用。请先确认现有真机预览服务的设备，再重新启动。" }

$apk = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw '尚无 Debug APK，请先执行 scripts/build-local.ps1。' }
& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw '真机 APK 安装失败；不会清除手机上的应用数据。' }
& $adb -s $serial shell am start -n cc.catsco.android.debug/cc.catsco.android.MainActivity
if ($LASTEXITCODE -ne 0) { throw '真机 App 启动失败。' }

$env:CATSCO_ADB_SERIAL = $serial
$env:CATSCO_PREVIEW_PORT = "$port"
$env:CATSCO_DEVICE_WIDTH = "$width"
$env:CATSCO_DEVICE_HEIGHT = "$height"
$env:CATSCO_VIDEO_WIDTH = "$videoWidth"
$env:CATSCO_VIDEO_HEIGHT = "$videoHeight"
$logRoot = Join-Path $projectRoot 'build/emulator'
New-Item -ItemType Directory -Force $logRoot | Out-Null
Start-Process -FilePath $node -ArgumentList "$PSScriptRoot/native-preview.mjs" -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput "$logRoot/phone-preview.log" -RedirectStandardError "$logRoot/phone-preview-error.log" | Out-Null
Write-Output "CatsCo 真机预览：http://127.0.0.1:$port/"
