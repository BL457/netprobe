<#
.SYNOPSIS
  Writes latest.json next to a release APK (in-app updater spec 5).
.EXAMPLE
  .\tools\make-latest-json.ps1 -Apk app\build\outputs\apk\release\app-release.apk `
      -ApkUrl https://github.com/BL457/netprobe/releases/download/v0.3.3/app-release.apk
.NOTES
  Without -Notes, the notes are the CHANGELOG.md section "## <versionName>" (up to the next "## "); the script fails if there is none.
  -Notes <file> overrides that with a text file.
  versionCode and versionName come from `aapt2 dump badging` when aapt2 is found under the Android SDK;
  otherwise pass -VersionCode and -VersionName.
#>
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [Parameter(Mandatory = $true)][string]$ApkUrl,
    [string]$Notes,
    [string]$Changelog = (Join-Path $PSScriptRoot '..\CHANGELOG.md'),
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
$text = $null
if ($Notes) {
    if (-not (Test-Path -LiteralPath $Notes -PathType Leaf)) { throw "Notes file not found: $Notes" }
    $text = (Get-Content -LiteralPath $Notes -Raw -Encoding UTF8).Trim()
} else {
    if (-not (Test-Path -LiteralPath $Changelog -PathType Leaf)) { throw "CHANGELOG.md not found: $Changelog (or pass -Notes <file>)" }
    $inSection = $false
    $lines = New-Object System.Collections.Generic.List[string]
    foreach ($line in (Get-Content -LiteralPath $Changelog -Encoding UTF8)) {
        if ($line -match '^## ') {
            if ($inSection) { break }
            if ($line.Substring(3).Trim() -eq $VersionName) { $inSection = $true }
            continue
        }
        if ($inSection) { $lines.Add($line) }
    }
    if (-not $inSection) { throw "No section '## $VersionName' in $Changelog. Add one before cutting the release." }
    $text = ($lines -join "`n").Trim()
    if (-not $text) { throw "The '## $VersionName' section in $Changelog is empty." }
}
if ($text.Length -gt 2000) { $text = $text.Substring(0, 2000) }
$manifest['notes'] = $text

$out = Join-Path $apkFile.DirectoryName 'latest.json'
$json = $manifest | ConvertTo-Json
[System.IO.File]::WriteAllText($out, $json, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Wrote $out"
Write-Host "  versionCode $VersionCode, versionName $VersionName, $($apkFile.Length) bytes, sha256 $sha256"
