[CmdletBinding()]
param([string]$Path = (Join-Path $PSScriptRoot '..\apps.json'))

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Catalog file was not found: $Path" }
if ((Get-Item -LiteralPath $Path).Length -gt 1048576) { throw 'Catalog exceeds the 1 MiB limit.' }
try { $catalog = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json }
catch { throw "Invalid JSON in ${Path}: $($_.Exception.Message)" }
if ($catalog.apps -isnot [Array] -or $catalog.apps.Count -lt 1 -or $catalog.apps.Count -gt 500) {
    throw 'The catalog must contain an apps array with 1 to 500 entries.'
}

function Test-CatalogText($Value, [string]$Name, [int]$MaximumLength) {
    if ($Value -isnot [string] -or [string]::IsNullOrWhiteSpace($Value) -or
        $Value.Length -gt $MaximumLength -or $Value -cne $Value.Trim() -or $Value -match '[\x00-\x1f\x7f-\x9f]') {
        throw "Invalid string property '$Name'."
    }
}
function Test-PositiveInteger($Value, [string]$Name) {
    if ($Value -isnot [int] -and $Value -isnot [long]) { throw "$Name must be an integer JSON number." }
    if ($Value -lt 1) { throw "$Name must be positive." }
}
function Test-HttpsUrl($Value, [string]$Name) {
    Test-CatalogText $Value $Name 4096
    $uri = $null
    if (-not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -ne 'https' -or
        [string]::IsNullOrWhiteSpace($uri.Host) -or $uri.UserInfo -or $uri.Fragment -or $Value -match '\s') {
        throw "$Name must be an absolute HTTPS URL without credentials or fragments."
    }
}

$seenPackages = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($app in $catalog.apps) {
    Test-CatalogText $app.name 'name' 160
    Test-CatalogText $app.packageName 'packageName' 255
    Test-CatalogText $app.versionName 'versionName' 128
    Test-CatalogText $app.apkPath 'apkPath' 1024
    Test-PositiveInteger $app.versionCode 'versionCode'
    Test-HttpsUrl $app.apkUrl 'apkUrl'
    if ($app.packageName -cnotmatch '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$') {
        throw "Invalid Android package name '$($app.packageName)'."
    }
    if (-not $seenPackages.Add($app.packageName)) { throw "Duplicate package name '$($app.packageName)'." }
    if (-not $app.apkPath.StartsWith('/') -or $app.apkPath.Contains('\') -or $app.apkPath -match '(^|/)\.{1,2}(/|$)') {
        throw "Invalid apkPath for '$($app.name)'."
    }
    if ($null -ne $app.iconUrl) { Test-HttpsUrl $app.iconUrl 'iconUrl' }
    if ($null -ne $app.sha256) {
        Test-CatalogText $app.sha256 'sha256' 64
        if ($app.sha256 -cnotmatch '^[a-fA-F0-9]{64}$') { throw "Invalid SHA-256 for '$($app.name)'." }
    }
    if ($null -ne $app.sizeBytes) { Test-PositiveInteger $app.sizeBytes 'sizeBytes' }
}

$projectRoot = Split-Path $PSScriptRoot -Parent
if ([IO.Path]::GetFullPath($Path) -eq [IO.Path]::GetFullPath((Join-Path $projectRoot 'apps.json'))) {
    $bundledPath = Join-Path $projectRoot 'app\src\main\assets\apps.json'
    if (-not (Test-Path -LiteralPath $bundledPath) -or
        (Get-FileHash -LiteralPath $Path).Hash -ne (Get-FileHash -LiteralPath $bundledPath).Hash) {
        throw 'The bundled app/src/main/assets/apps.json must match the published apps.json.'
    }
}
Write-Host "Validated $($catalog.apps.Count) catalog entries in $Path."
