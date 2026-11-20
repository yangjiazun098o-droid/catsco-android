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

$feedFile = gh api "${feedPath}?ref=$branch" | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'Unable to read the preview update feed.' }
$feedText = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($feedFile.content))
$current = @(ConvertFrom-Json -InputObject $feedText)
foreach ($entry in $current) {
    if ($entry.tag_name -match '^v0\.1\.0-preview\.([0-9]+)$' -and [int]$Matches[1] -gt $PreviewNumber) {
        throw 'The update feed already points to a newer preview.'
    }
}

$releaseJson = gh release view $tag --repo $repo --json isDraft,isPrerelease,assets 2>$null
if ($LASTEXITCODE -ne 0) {
    gh release create $tag $apkFile.FullName --repo $repo --target main --draft --prerelease `
        --title "CatsCo Android 测试版 0.1.0 Preview $PreviewNumber" --notes-file $notesFile.FullName
    if ($LASTEXITCODE -ne 0) { throw 'Unable to create the preview release.' }
    $releaseJson = gh release view $tag --repo $repo --json isDraft,isPrerelease,assets
    if ($LASTEXITCODE -ne 0) { throw 'Unable to verify the preview release.' }
}
$release = $releaseJson | ConvertFrom-Json
if (-not $release.isPrerelease) { throw 'The tag belongs to a release outside the preview channel.' }
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
