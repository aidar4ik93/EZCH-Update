[CmdletBinding()]
param(
    [ValidateSet('app','launcher')][string]$Module = 'app',
    [string]$SigningProject = (Join-Path $env:USERPROFILE 'AndroidStudioProjects\EZCHUpdate'),
    [string]$Output = (Join-Path $PSScriptRoot '..\dist\EZCH_Update_1.5.1_r19_original.apk'),
    [string]$ExpectedCertificate = 'ea0e9a1aad77b1fb42f32644a7386131ceed43e07464dcde22754691e70cc602',
    [string]$AndroidSdk = (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
    [string]$JavaHome = (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr')
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outputPath = [IO.Path]::GetFullPath($Output)
$signingProjectPath = [IO.Path]::GetFullPath($SigningProject)
$expectedDigest = ($ExpectedCertificate -replace '[:\s]', '').ToLowerInvariant()
if ($expectedDigest -notmatch '^[a-f0-9]{64}$') {
    throw 'ExpectedCertificate must be a SHA-256 certificate fingerprint.'
}

$gradle = Join-Path $projectRoot 'gradlew.bat'
$propertiesPath = Join-Path $signingProjectPath 'gradle.properties'
$keystorePath = Join-Path $signingProjectPath 'ezch-update-release.jks'
$buildToolsPath = Join-Path $AndroidSdk 'build-tools\36.0.0'
$zipalign = Join-Path $buildToolsPath 'zipalign.exe'
$apksigner = Join-Path $buildToolsPath 'apksigner.bat'
$aapt = Join-Path $buildToolsPath 'aapt2.exe'
$java = Join-Path $JavaHome 'bin\java.exe'

foreach ($requiredPath in @($gradle, $propertiesPath, $keystorePath, $zipalign, $apksigner, $aapt, $java)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "Required local file is missing: $requiredPath"
    }
}

# Signing values stay in memory and are passed by environment variable, never
# by command-line argument or by copying them into this repository.
$signingProperties = @{}
foreach ($line in [IO.File]::ReadAllLines($propertiesPath)) {
    if ($line -match '^\s*(EZCH_STORE_PASSWORD|EZCH_KEY_PASSWORD)\s*=(.*)$') {
        $signingProperties[$matches[1]] = $matches[2].Trim()
    }
}
foreach ($propertyName in @('EZCH_STORE_PASSWORD', 'EZCH_KEY_PASSWORD')) {
    if ([string]::IsNullOrEmpty($signingProperties[$propertyName])) {
        throw "Required signing property is missing: $propertyName"
    }
}

$environmentNames = @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'EZCH_RELEASE_STORE_PASSWORD', 'EZCH_RELEASE_KEY_PASSWORD')
$previousEnvironment = @{}
foreach ($environmentName in $environmentNames) {
    $previousEnvironment[$environmentName] = [Environment]::GetEnvironmentVariable($environmentName, 'Process')
}
$temporaryParent = [IO.Path]::GetFullPath((Join-Path $projectRoot 'app\build\tmp\release-signing'))
$temporaryDirectory = Join-Path $temporaryParent ([Guid]::NewGuid().ToString('N'))
$locationPushed = $false

try {
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $JavaHome, 'Process')
    [Environment]::SetEnvironmentVariable('ANDROID_HOME', $AndroidSdk, 'Process')
    [Environment]::SetEnvironmentVariable('ANDROID_SDK_ROOT', $AndroidSdk, 'Process')
    Push-Location -LiteralPath $projectRoot
    $locationPushed = $true

    Write-Host 'Building the release APK...'
    & $gradle ":${Module}:assembleRelease" '--no-daemon' '--console=plain'
    if ($LASTEXITCODE -ne 0) {
        throw "Release build failed (exit code $LASTEXITCODE)."
    }
    $moduleDirectory = if ($Module -eq 'launcher') { 'launcher\app' } else { 'app' }
    $unsignedName = if ($Module -eq 'launcher') { 'launcher-release-unsigned.apk' } else { 'app-release-unsigned.apk' }
    $unsignedApk = Join-Path $projectRoot "$moduleDirectory\build\outputs\apk\release\$unsignedName"
    if (-not (Test-Path -LiteralPath $unsignedApk -PathType Leaf)) {
        throw "The build did not produce $unsignedName."
    }

    New-Item -ItemType Directory -Path $temporaryDirectory -Force | Out-Null
    $alignedApk = Join-Path $temporaryDirectory 'aligned.apk'
    $signedApk = Join-Path $temporaryDirectory 'signed.apk'
    & $zipalign '-P' '16' '-f' '4' $unsignedApk $alignedApk
    if ($LASTEXITCODE -ne 0) {
        throw "APK alignment failed (exit code $LASTEXITCODE)."
    }

    [Environment]::SetEnvironmentVariable('EZCH_RELEASE_STORE_PASSWORD', $signingProperties['EZCH_STORE_PASSWORD'], 'Process')
    [Environment]::SetEnvironmentVariable('EZCH_RELEASE_KEY_PASSWORD', $signingProperties['EZCH_KEY_PASSWORD'], 'Process')
    Write-Host 'Signing with the original local release key...'
    & $apksigner 'sign' '--ks' $keystorePath '--ks-key-alias' 'ezch-update' '--ks-pass' 'env:EZCH_RELEASE_STORE_PASSWORD' '--key-pass' 'env:EZCH_RELEASE_KEY_PASSWORD' '--out' $signedApk $alignedApk
    if ($LASTEXITCODE -ne 0) {
        throw "Release signing failed (exit code $LASTEXITCODE)."
    }

    $verificationOutput = @(& $apksigner 'verify' '--verbose' '--print-certs' $signedApk 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw 'The signed APK failed signature verification.'
    }
    $verificationText = ($verificationOutput | ForEach-Object { $_.ToString() }) -join "`n"
    $certificateMatches = [regex]::Matches($verificationText, '(?m)^Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9]+)\s*$')
    if ($certificateMatches.Count -ne 1) {
        throw 'Expected exactly one verified release signer.'
    }
    $actualDigest = $certificateMatches[0].Groups[1].Value.ToLowerInvariant()
    if ($actualDigest -ne $expectedDigest) {
        throw "Release signer mismatch. Expected $expectedDigest; received $actualDigest. No final APK was written."
    }

    & $zipalign '-c' '-P' '16' '4' $signedApk
    if ($LASTEXITCODE -ne 0) {
        throw 'The signed APK failed alignment verification.'
    }
    $badgingOutput = @(& $aapt 'dump' 'badging' $signedApk 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw 'The signed APK metadata could not be read.'
    }
    $badgingText = ($badgingOutput | ForEach-Object { $_.ToString() }) -join "`n"
    $packageMatch = [regex]::Match($badgingText, "(?m)^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'")
    $expectedPackage = if ($Module -eq 'launcher') { 'com.example.homeezch.usb' } else { 'com.example.ezchupdate' }
    $expectedCode = if ($Module -eq 'launcher') { '15' } else { '19' }
    $expectedVersion = if ($Module -eq 'launcher') { '0.15-USB' } else { '1.5.1' }
    if (-not $packageMatch.Success -or $packageMatch.Groups[1].Value -ne $expectedPackage) {
        throw 'The release APK has an unexpected application ID.'
    }
    if ($packageMatch.Groups[2].Value -ne $expectedCode -or $packageMatch.Groups[3].Value -ne $expectedVersion) {
        throw "Expected versionCode $expectedCode and versionName $expectedVersion."
    }

    $outputDirectory = Split-Path -Parent $outputPath
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    Copy-Item -LiteralPath $signedApk -Destination $outputPath -Force
    Write-Host "Release ready: $outputPath"
    Write-Host "Package: $expectedPackage; version: $expectedVersion; versionCode: $expectedCode; original release identity"
    Write-Host "Certificate SHA-256: $actualDigest"
    Write-Host ('APK SHA-256: ' + (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash.ToLowerInvariant())
}
finally {
    foreach ($environmentName in $environmentNames) {
        [Environment]::SetEnvironmentVariable($environmentName, $previousEnvironment[$environmentName], 'Process')
    }
    $signingProperties.Clear()
    if ($locationPushed) {
        Pop-Location
    }
    if (Test-Path -LiteralPath $temporaryDirectory -PathType Container) {
        $resolvedTemporaryDirectory = (Resolve-Path -LiteralPath $temporaryDirectory).Path
        $allowedTemporaryPrefix = $temporaryParent.TrimEnd('\') + '\'
        if (-not $resolvedTemporaryDirectory.StartsWith($allowedTemporaryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing to remove a signing temporary directory outside app/build/tmp/release-signing.'
        }
        Remove-Item -LiteralPath $resolvedTemporaryDirectory -Recurse -Force
    }
}
