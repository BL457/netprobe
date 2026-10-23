<#
.SYNOPSIS
  Writes latest.json next to a release APK (in-app updater spec 5).
.EXAMPLE
  .\tools\make-latest-json.ps1 -Apk app\build\outputs\apk\release\app-release.apk `
      -ApkUrl https://github.com/BL457/netprobe/releases/download/v0.3.1/app-release.apk -Notes notes.txt
.NOTES
  versionCode and versionName come from `aapt2 dump badging` when aapt2 is found under the Android SDK;
  otherwise pass -VersionCode and -VersionName.
#>
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [Parameter(Mandatory = $true)][string]$ApkUrl,
    [string]$Notes,
    [int]$VersionCode,
    [string]$VersionName,
    [int]$MinSdk
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "APK not found: $Apk" }
if ($ApkUrl -notmatch '^https://') { throw "ApkUrl must be https: $ApkUrl" }
$apkFile = Get-Item -LiteralPath $Apk

function Find-Aapt2 {
    $roots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk')) | Where-Object { $_ -and (Test-Path $_) }
    foreach ($root in $roots) {
        $tools = Join-Path $root 'build-tools'
        if (-not (Test-Path $tools)) { continue }
        $found = Get-ChildItem -Path $tools -Filter 'aapt2.exe' -Recurse -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    return $null
}

if (-not $VersionCode -or -not $VersionName) {
    $aapt2 = Find-Aapt2
    if (-not $aapt2) { throw 'aapt2 not found under the Android SDK: pass -VersionCode and -VersionName.' }
    $badging = & $aapt2 dump badging $apkFile.FullName | Out-String
    if ($badging -match "versionCode='(\d+)'") { if (-not $VersionCode) { $VersionCode = [int]$Matches[1] } }
    if ($badging -match "versionName='([^']+)'") { if (-not $VersionName) { $VersionName = $Matches[1] } }
    if ($badging -match "minSdkVersion:'(\d+)'") { if (-not $MinSdk) { $MinSdk = [int]$Matches[1] } }
}
if (-not $VersionCode -or -not $VersionName) { throw 'Could not determine versionCode and versionName.' }

$sha256 = (Get-FileHash -LiteralPath $apkFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$manifest = [ordered]@{
    versionCode = $VersionCode
    versionName = $VersionName
    apkUrl      = $ApkUrl
    sha256      = $sha256
    sizeBytes   = $apkFile.Length
}
if ($MinSdk) { $manifest['minSdk'] = $MinSdk }
if ($Notes) {
    if (-not (Test-Path -LiteralPath $Notes -PathType Leaf)) { throw "Notes file not found: $Notes" }
    $text = (Get-Content -LiteralPath $Notes -Raw).Trim()
    if ($text.Length -gt 2000) { $text = $text.Substring(0, 2000) }
    $manifest['notes'] = $text
}

$out = Join-Path $apkFile.DirectoryName 'latest.json'
$json = $manifest | ConvertTo-Json
[System.IO.File]::WriteAllText($out, $json, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Wrote $out"
Write-Host "  versionCode $VersionCode, versionName $VersionName, $($apkFile.Length) bytes, sha256 $sha256"
