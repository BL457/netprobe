<#
.SYNOPSIS
  Rewrites catalog\index.json from the scripts in catalog\ (the "Update tools from GitHub" action reads that index).
.EXAMPLE
  .\tools\make-catalog-index.ps1
.NOTES
  Nothing is evaluated: each field is read from the `tool = { ... }` literal with a small text parser. The listed fields (id, name, version,
  description, network, broadcast, and the optional minVersionCode and minVersionName) must be written as plain string, `true` or `false` literals in the part of the object before `inputs:` or
  `run:`, and a string must not contain a backslash or a double quote. A script that breaks these rules stops the run with its file name.
  The sha256 is taken over the file with CRLF turned into LF, which is what GitHub serves. Run the unit tests afterwards: CatalogTest
  evaluates every script in the sandbox and checks it against the index.
#>
param(
    [string]$Catalog = (Join-Path $PSScriptRoot '..\catalog')
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Catalog -PathType Container)) { throw "Catalog folder not found: $Catalog" }

function Get-Head([string]$text) {
    $cut = $text.Length
    foreach ($marker in @('inputs\s*:', 'run\s*:')) {
        $m = [regex]::Match($text, "(?m)^\s*$marker|[,{]\s*$marker")
        if ($m.Success -and $m.Index -lt $cut) { $cut = $m.Index }
    }
    return $text.Substring(0, $cut)
}

function Get-StringField([string]$head, [string]$field, [string]$file, [bool]$required) {
    $m = [regex]::Match($head, '\b' + $field + '\s*:\s*"([^"\\\r\n]*)"')
    if (-not $m.Success) {
        if ($required) { throw "${file}: no plain string for '$field' before inputs/run" }
        return $null
    }
    return $m.Groups[1].Value
}

$entries = New-Object System.Collections.Generic.List[object]
foreach ($file in (Get-ChildItem -LiteralPath $Catalog -Filter '*.js' | Sort-Object Name)) {
    $raw = [System.IO.File]::ReadAllText($file.FullName, (New-Object System.Text.UTF8Encoding($false)))
    $text = $raw.Replace("`r`n", "`n")
    if ($text.Length -gt 0 -and [int][char]$text[0] -eq 0xFEFF) { $text = $text.Substring(1) }
    $head = Get-Head $text
    $id = Get-StringField $head 'id' $file.Name $true
    if ($id -notmatch '^[a-z0-9-]{3,40}$') { throw "$($file.Name): the id '$id' must be 3 to 40 characters from a-z, 0-9 and -" }
    if ($file.BaseName -ne $id) { throw "$($file.Name): the file name must be the id plus .js ($id.js)" }
    $name = Get-StringField $head 'name' $file.Name $true
    $version = Get-StringField $head 'version' $file.Name $false
    if (-not $version) { $version = '1' }
    $description = Get-StringField $head 'description' $file.Name $false
    if ($null -eq $description) { $description = '' }
    $network = Get-StringField $head 'network' $file.Name $false
    if (-not $network) { $network = 'target' }
    $broadcast = $head -match '\bbroadcast\s*:\s*true\b'
    $minCode = $null
    $mc = [regex]::Match($head, '\bminVersionCode\s*:\s*([0-9]{1,9})\b')
    if ($mc.Success) { $minCode = [int]$mc.Groups[1].Value }
    $minName = Get-StringField $head 'minVersionName' $file.Name $false
    $bytes = (New-Object System.Text.UTF8Encoding($false)).GetBytes($text)
    $sha = [System.BitConverter]::ToString([System.Security.Cryptography.SHA256]::Create().ComputeHash($bytes)).Replace('-', '').ToLowerInvariant()
    $entry = [ordered]@{
        id = $id; file = $file.Name; name = $name; version = $version; sha256 = $sha
        network = $network; description = $description; broadcast = [bool]$broadcast
    }
    if ($null -ne $minCode) { $entry['minVersionCode'] = $minCode }
    if ($minName) { $entry['minVersionName'] = $minName }
    $entries.Add($entry)
}

$index = [ordered]@{ format = 1; tools = $entries.ToArray() }
$json = ($index | ConvertTo-Json -Depth 5).Replace("`r`n", "`n") + "`n"
$out = Join-Path $Catalog 'index.json'
[System.IO.File]::WriteAllText($out, $json, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Wrote $out ($($entries.Count) tools)"
foreach ($e in $entries) { Write-Host "  $($e.id) $($e.version) $($e.network) $($e.sha256)" }
