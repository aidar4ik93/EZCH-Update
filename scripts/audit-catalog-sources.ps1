[CmdletBinding()]
param(
    [string]$Path = (Join-Path $PSScriptRoot '..\apps.json'),
    [string]$ApkDirectory,
    [string]$Aapt2,
    [switch]$CheckRemote
)

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'validate-apps-json.ps1') -Path $Path
$catalog = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
$publicResources = @{}
foreach ($app in $catalog.apps) {
    if ($ApkDirectory) {
        if (-not $Aapt2) { throw 'Aapt2 is required when ApkDirectory is supplied.' }
        $apkFile = Join-Path $ApkDirectory $app.apkPath.TrimStart('/')
        if (-not (Test-Path -LiteralPath $apkFile)) { throw "APK not found: $apkFile" }
        if ($app.sha256 -and (Get-FileHash -LiteralPath $apkFile -Algorithm SHA256).Hash -ine $app.sha256) {
            throw "Checksum mismatch for $($app.name)"
        }
        if ($app.sizeBytes -and (Get-Item -LiteralPath $apkFile).Length -ne $app.sizeBytes) {
            throw "Size mismatch for $($app.name)"
        }
        $badging = & $Aapt2 dump badging $apkFile
        $packageLine = $badging | Where-Object { $_.StartsWith('package:') } | Select-Object -First 1
        $packageMatch = [Regex]::Match($packageLine, "name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'")
        if ($LASTEXITCODE -ne 0 -or -not $packageMatch.Success -or
            $packageMatch.Groups[1].Value -cne $app.packageName -or [long]$packageMatch.Groups[2].Value -ne $app.versionCode -or
            $packageMatch.Groups[3].Value -cne $app.versionName) {
            throw "APK manifest does not match catalog: $($app.name): $packageLine"
        }
    }
    if ($CheckRemote) {
        $uri = [Uri]$app.apkUrl
        if ($uri.Host -in @('disk.yandex.ru', 'yadi.sk')) {
            $publicKey = [Uri]::EscapeDataString($app.apkUrl)
            $apkPath = [Uri]::EscapeDataString($app.apkPath)
            if (-not $publicResources.ContainsKey($app.apkUrl)) {
                $publicResources[$app.apkUrl] = Invoke-RestMethod -Uri "https://cloud-api.yandex.net/v1/disk/public/resources?public_key=$publicKey&limit=1000" -TimeoutSec 30
            }
            $resource = $publicResources[$app.apkUrl]
            $metadata = if ($resource.type -eq 'file') { $resource }
                else { $resource._embedded.items | Where-Object path -CEQ $app.apkPath | Select-Object -First 1 }
            if ($null -eq $metadata) { throw "Source file not found: $($app.name) ($($app.apkPath))" }
            if ($metadata.type -ne 'file') { throw "Source is not a file: $($app.name)" }
            if ($app.sha256 -and $metadata.sha256 -ine $app.sha256) { throw "Remote checksum differs: $($app.name)" }
            if ($app.sizeBytes -and $metadata.size -ne $app.sizeBytes) { throw "Remote size differs: $($app.name)" }
            $download = Invoke-RestMethod -Uri "https://cloud-api.yandex.net/v1/disk/public/resources/download?public_key=$publicKey&path=$apkPath" -TimeoutSec 30
            $downloadUri = [Uri]$download.href
            if ($downloadUri.Scheme -ne 'https') { throw "Non-HTTPS source download: $($app.name)" }
            $response = Invoke-WebRequest -Method Head -Uri $download.href -TimeoutSec 30
            if ($response.StatusCode -ne 200) { throw "Download unavailable: $($app.name)" }
        } else {
            $response = Invoke-WebRequest -Method Head -Uri $app.apkUrl -TimeoutSec 30
            if ($response.StatusCode -ne 200) { throw "Source unavailable: $($app.name)" }
        }
    }
    Write-Host "Audited $($app.name) $($app.versionName)"
}
