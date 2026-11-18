param([string[]]$Tasks = @(':core:test', ':app:assembleDebug', ':app:lintDebug'))
$ErrorActionPreference = 'Stop'
$toolRoot = 'E:/CodexProjects/.tools/android'
$javaDirectory = Get-ChildItem "$toolRoot/jdk" -Directory | Select-Object -First 1
if (-not $javaDirectory) { throw 'Project JDK not installed. See README.' }
$env:JAVA_HOME = $javaDirectory.FullName
$env:ANDROID_HOME = "$toolRoot/sdk"
$env:GRADLE_USER_HOME = "$toolRoot/gradle-home"
$existingDebugKey = Join-Path $env:USERPROFILE '.android/debug.keystore'
if (Test-Path -LiteralPath $existingDebugKey) {
    $env:ANDROID_USER_HOME = Split-Path $existingDebugKey -Parent
}
Push-Location (Split-Path $PSScriptRoot -Parent)
try { & ./gradlew.bat @Tasks; if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" } }
finally { Pop-Location }
