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

if ($null -eq $catalog.apps -or $catalog.apps.Count -eq 0) {
    throw 'The catalog must contain at least one app.'
}

$requiredProperties = @('name', 'packageName', 'versionCode', 'versionName', 'apkUrl', 'apkPath')
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
}

Write-Host "Validated $($catalog.apps.Count) catalog entries in $Path."
