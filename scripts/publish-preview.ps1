param(
    [Parameter(Mandatory)][ValidateRange(3, 999999999)][int]$PreviewNumber,
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$Notes
)

$ErrorActionPreference = 'Stop'
$repo = 'yangjiazun098o-droid/catsco-android'
$branch = 'preview-updates'
$feedPath = "repos/$repo/contents/update.json"
$tag = "v0.1.0-preview.$PreviewNumber"
$assetName = 'CatsCo-Android-Preview.apk'
$apkFile = Get-Item -LiteralPath $Apk
$notesFile = Get-Item -LiteralPath $Notes
if ($apkFile.Name -cne $assetName) { throw "The APK must be named $assetName." }
$digest = 'sha256:' + (Get-FileHash -LiteralPath $apkFile.FullName).Hash.ToLowerInvariant()

$sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, 'E:/CodexProjects/.tools/android/sdk') |
    Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'build-tools')) } | Select-Object -First 1
if (-not $sdk) { throw 'Android SDK build-tools are required to verify the APK.' }
$buildTools = Get-ChildItem -Directory (Join-Path $sdk 'build-tools') |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $buildTools) { throw 'Install Android SDK build-tools before publishing.' }
$aapt = Join-Path $buildTools.FullName 'aapt.exe'
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    $localJdk = Get-ChildItem -Directory 'E:/CodexProjects/.tools/android/jdk' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $localJdk) { throw 'Set JAVA_HOME to verify the APK signature.' }
    $env:JAVA_HOME = $localJdk.FullName
}
function Get-ApkSignerDigest([string]$File) {
    $details = & $apksigner verify --print-certs $File
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $signers = @($details | Select-String '^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$')
    if ($signers.Count -ne 1) { throw 'Expected one preview signing identity.' }
    return $signers[0].Matches[0].Groups[1].Value.ToLowerInvariant()
}
$signer = Get-ApkSignerDigest $apkFile.FullName
$badging = & $aapt dump badging $apkFile.FullName
$expectedVersion = [regex]::Escape("0.1.0-preview.$PreviewNumber")
if ($LASTEXITCODE -ne 0 -or -not ($badging -cmatch "^package: name='cc\.catsco\.android\.debug' versionCode='$PreviewNumber' versionName='$expectedVersion'")) {
    throw 'The APK package, version code or version name does not match this preview.'
}
$manifest = (& $aapt dump xmltree $apkFile.FullName AndroidManifest.xml) -join "`n"
if ($LASTEXITCODE -ne 0 -or $manifest -cnotmatch 'android:name[^\r\n]*="cc\.catsco\.preview\.SOURCE"[^\r\n]*\r?\n\s*A: android:value[^\r\n]*="git:([0-9a-f]{40})"') {
    throw 'Build this distribution APK from a clean commit with -PcatscoPreviewRevision=<full commit>.'
}
$sourceCommit = $Matches[1]
$remoteCommit = gh api "repos/$repo/commits/$sourceCommit" --jq '.sha'
if ($LASTEXITCODE -ne 0 -or $remoteCommit -cne $sourceCommit) { throw 'Publish the exact source commit to the repository before its APK.' }
$refs = gh api "repos/$repo/git/matching-refs/tags/$tag" | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'Unable to check the release tag.' }
$existingTag = @($refs | Where-Object { $_.ref -ceq "refs/tags/$tag" }).Count -gt 0
if ($existingTag) {
    $tagCommit = gh api "repos/$repo/commits/$tag" --jq '.sha'
    if ($LASTEXITCODE -ne 0 -or $tagCommit -cne $sourceCommit) { throw 'The release tag already points to a different source commit.' }
}

$feedFile = gh api "${feedPath}?ref=$branch" | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'Unable to read the preview update feed.' }
$feedText = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($feedFile.content))
$current = @(ConvertFrom-Json -InputObject $feedText)
foreach ($entry in $current) {
    if ($entry.tag_name -match '^v0\.1\.0-preview\.([0-9]+)$' -and [int]$Matches[1] -gt $PreviewNumber) {
        throw 'The update feed already points to a newer preview.'
    }
}

$previous = $current | Where-Object { $_.tag_name -match '^v0\.1\.0-preview\.([0-9]+)$' -and -not $_.draft } |
    Sort-Object { [int]($_.tag_name.Split('.')[-1]) } -Descending | Select-Object -First 1
$previousAsset = $previous.assets | Where-Object { $_.name -ceq $assetName }
if (-not $previous -or $previousAsset.digest -notmatch '^sha256:[0-9a-f]{64}$') { throw 'No verified channel APK was available for the upgrade-signature check.' }
$verificationDir = [IO.Directory]::CreateDirectory((Join-Path ([IO.Path]::GetTempPath()) ("catsco-signature-" + [guid]::NewGuid())))
$baseline = Join-Path $verificationDir.FullName $assetName
try {
    gh release download $previous.tag_name --repo $repo --pattern $assetName --dir $verificationDir.FullName
    if ($LASTEXITCODE -ne 0) { throw 'Unable to download the previous preview for verification.' }
    $baselineDigest = 'sha256:' + (Get-FileHash -LiteralPath $baseline).Hash.ToLowerInvariant()
    if ($baselineDigest -cne $previousAsset.digest -or (Get-ApkSignerDigest $baseline) -cne $signer) {
        throw 'The APK does not match the signing identity of the published update channel.'
    }
} finally {
    if (Test-Path -LiteralPath $baseline) { Remove-Item -LiteralPath $baseline -Force }
    $verificationDir.Delete()
}

$releaseJson = gh release view $tag --repo $repo --json isDraft,isPrerelease,assets,targetCommitish 2>$null
if ($LASTEXITCODE -ne 0) {
    gh release create $tag $apkFile.FullName --repo $repo --target $sourceCommit --draft --prerelease `
        --title "CatsCo Android 测试版 0.1.0 Preview $PreviewNumber" --notes-file $notesFile.FullName
    if ($LASTEXITCODE -ne 0) { throw 'Unable to create the preview release.' }
    $releaseJson = gh release view $tag --repo $repo --json isDraft,isPrerelease,assets,targetCommitish
    if ($LASTEXITCODE -ne 0) { throw 'Unable to verify the preview release.' }
}
$release = $releaseJson | ConvertFrom-Json
if (-not $release.isPrerelease) { throw 'The tag belongs to a release outside the preview channel.' }
if ($release.isDraft -and -not $existingTag -and $release.targetCommitish -cne $sourceCommit) { throw 'The draft release targets a different source commit.' }
$asset = $release.assets | Where-Object { $_.name -ceq $assetName }
if ($asset.digest -ne $digest -and $release.isDraft) {
    gh release upload $tag $apkFile.FullName --repo $repo --clobber
    if ($LASTEXITCODE -ne 0) { throw 'Unable to upload the APK to the draft.' }
    $release = gh release view $tag --repo $repo --json isDraft,isPrerelease,assets | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Unable to verify the uploaded APK.' }
    $asset = $release.assets | Where-Object { $_.name -ceq $assetName }
}
if ($asset.digest -ne $digest -or $asset.state -ne 'uploaded') { throw 'GitHub APK verification failed.' }
if ($release.isDraft) {
    gh release edit $tag --repo $repo --draft=false
    if ($LASTEXITCODE -ne 0) { throw 'Unable to publish the preview release.' }
}

$release = gh release view $tag --repo $repo --json isDraft,isPrerelease,assets | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or $release.isDraft -or -not $release.isPrerelease) {
    throw 'Unable to confirm that the preview release is public.'
}
$tagCommit = gh api "repos/$repo/commits/$tag" --jq '.sha'
if ($LASTEXITCODE -ne 0 -or $tagCommit -cne $sourceCommit) { throw 'Published tag does not match the APK source.' }
$asset = $release.assets | Where-Object { $_.name -ceq $assetName }
$expectedUrl = "https://github.com/$repo/releases/download/$tag/$assetName"
if ($asset.digest -ne $digest -or $asset.url -cne $expectedUrl -or $asset.size -ne $apkFile.Length) {
    throw 'Published APK metadata differs from the verified build.'
}

if ($current.Count -ne 1 -or $current[0].tag_name -ne $tag -or $current[0].assets[0].digest -ne $digest) {
    $updated = @(@{
        tag_name = $tag
        draft = $false
        prerelease = $true
        assets = @(@{
            name = $assetName
            state = 'uploaded'
            browser_download_url = $asset.url
            size = $asset.size
            digest = $asset.digest
        })
    })
    $json = ConvertTo-Json -InputObject $updated -Depth 6
    $content = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("$json`n"))
    gh api $feedPath --method PUT -f "branch=$branch" -f "sha=$($feedFile.sha)" `
        -f "message=Offer CatsCo Android Preview $PreviewNumber" -f "content=$content" --jq '.content.download_url'
    if ($LASTEXITCODE -ne 0) { throw 'The release is public, but the update feed still needs to be updated. Retry this command.' }
}
Write-Output $asset.url
