[CmdletBinding()]
param(
    [string]$Path = (Join-Path $PSScriptRoot '..\\apps.json')
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    throw "Catalog file was not found: $Path"
}

try {
    $catalog = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}
catch {
    throw "Invalid JSON in ${Path}: $($_.Exception.Message)"
}

if ($null -eq $catalog.apps) {
    throw 'The catalog must contain an apps array.'
}

$requiredProperties = @('name', 'packageName', 'versionCode', 'versionName', 'apkUrl', 'apkPath', 'sha256', 'sizeBytes', 'signerSha256')
$seenPackages = @{}

foreach ($app in $catalog.apps) {
    foreach ($property in $requiredProperties) {
        if ($null -eq $app.$property -or [string]::IsNullOrWhiteSpace([string]$app.$property)) {
            throw "An app is missing required property '$property'."
        }
    }

    if ($app.packageName -notmatch '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$') {
        throw "Invalid Android package name '$($app.packageName)'."
    }

    if ($seenPackages.ContainsKey($app.packageName)) {
        throw "Duplicate package name '$($app.packageName)'."
    }
    $seenPackages[$app.packageName] = $true

    $versionCode = 0L
    if (-not [Int64]::TryParse([string]$app.versionCode, [ref]$versionCode) -or $versionCode -lt 1) {
        throw "versionCode for '$($app.name)' must be a positive integer."
    }

    $uri = $null
    if (-not [Uri]::TryCreate([string]$app.apkUrl, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -ne 'https') {
        throw "apkUrl for '$($app.name)' must be an absolute HTTPS URL."
    }

    if (-not $app.apkPath.StartsWith('/')) {
        throw "apkPath for '$($app.name)' must start with '/'."
    }

    foreach ($hashProperty in @('sha256', 'signerSha256')) {
        if ($app.PSObject.Properties.Name -contains $hashProperty -and
            [string]$app.$hashProperty -notmatch '^[a-fA-F0-9]{64}$') {
            throw "$hashProperty for '$($app.name)' must be a 64-character SHA-256 hex digest."
        }
    }

    $hasIconUrl = $null -ne $app.iconUrl
    $hasIconHash = $null -ne $app.iconSha256
    if ($hasIconUrl -ne $hasIconHash) { throw "Both icon fields are required for '$($app.name)'." }
    if ($hasIconUrl) {
        $expected = "https://raw.githubusercontent.com/aidar4ik93/EZCH-Update/main/catalog-icons/$($app.sha256).png"
        if ($app.sha256 -cnotmatch '^[a-f0-9]{64}$' -or $app.iconUrl -cne $expected -or
            $app.iconSha256 -cnotmatch '^[a-f0-9]{64}$') { throw "Invalid icon metadata for '$($app.name)'." }
        $iconPath = Join-Path (Join-Path $PSScriptRoot '..') "catalog-icons/$($app.sha256).png"
        $bytes = [IO.File]::ReadAllBytes($iconPath)
        if ($bytes.Length -lt 33 -or $bytes.Length -gt 1MB -or
            [BitConverter]::ToString($bytes[0..7]) -ne '89-50-4E-47-0D-0A-1A-0A' -or
            [Text.Encoding]::ASCII.GetString($bytes, 12, 4) -ne 'IHDR') { throw "Invalid PNG: $iconPath" }
        $width = [uint64]$bytes[16] * 16777216 + [uint64]$bytes[17] * 65536 + [uint64]$bytes[18] * 256 + $bytes[19]
        $height = [uint64]$bytes[20] * 16777216 + [uint64]$bytes[21] * 65536 + [uint64]$bytes[22] * 256 + $bytes[23]
        if ($width -lt 1 -or $width -gt 512 -or $height -lt 1 -or $height -gt 512) { throw "PNG dimensions exceed 512: $iconPath" }
        if ((Get-FileHash -LiteralPath $iconPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $app.iconSha256) {
            throw "PNG checksum mismatch: $iconPath"
        }
    }

    if ($app.PSObject.Properties.Name -contains 'sizeBytes') {
        $sizeBytes = 0L
        if (-not [Int64]::TryParse([string]$app.sizeBytes, [ref]$sizeBytes) -or $sizeBytes -lt 1) {
            throw "sizeBytes for '$($app.name)' must be a positive integer."
        }
    }
}

Write-Host "Validated $($catalog.apps.Count) catalog entries in $Path."
