[CmdletBinding()]
param(
    [string]$PasswordFile,
    [string]$Keystore = (Join-Path $env:USERPROFILE 'EZCH_Signing_Keys\ezch-update-1.5.jks'),
    [string]$Output = (Join-Path $PSScriptRoot '..\dist\EZCH_Update_1.5.2_r20.apk')
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$buildTools = Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools\36.0.0'
$unsigned = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release-unsigned.apk'
$expectedCertificate = '00db34ed554705b5e4749fe61aaf39b16036e47c1b6f2a71ec1f86d9c8413afb'
$outputPath = [IO.Path]::GetFullPath($Output)
$temporaryParent = Join-Path $projectRoot 'app\build\tmp\v15-signing'
$temporaryDirectory = Join-Path $temporaryParent ([Guid]::NewGuid().ToString('N'))
$previousPassword = [Environment]::GetEnvironmentVariable('EZCH_V15_PASSWORD', 'Process')
$previousJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    foreach ($requiredFile in @($unsigned, $Keystore, "$buildTools\zipalign.exe", "$buildTools\apksigner.bat", "$buildTools\aapt2.exe")) {
        if (!(Test-Path -LiteralPath $requiredFile -PathType Leaf)) { throw "Missing local file: $requiredFile" }
    }
    $metadata = (& "$buildTools\aapt2.exe" dump badging $unsigned) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $metadata -notmatch "package: name='com.example.ezchupdate' versionCode='20' versionName='1.5.2'") {
        throw 'Build Update 1.5.2 revision 20 before signing.'
    }
    if ($PasswordFile) {
        $password = [IO.File]::ReadAllText([IO.Path]::GetFullPath($PasswordFile)).TrimEnd("`r", "`n")
    } else {
        $securePassword = Read-Host 'Password for the existing EZCH 1.5 key' -AsSecureString
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
        try { $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $securePassword.Dispose() }
    }
    if ([string]::IsNullOrEmpty($password)) { throw 'The signing password is empty.' }
    [Environment]::SetEnvironmentVariable('EZCH_V15_PASSWORD', $password, 'Process')
    $password = $null
    New-Item -ItemType Directory -Path $temporaryDirectory -Force | Out-Null
    $aligned = Join-Path $temporaryDirectory 'aligned.apk'
    $signed = Join-Path $temporaryDirectory 'signed.apk'
    & "$buildTools\zipalign.exe" -P 16 -f 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
    & "$buildTools\apksigner.bat" sign --ks $Keystore --ks-type PKCS12 --ks-key-alias ezch-release-v15 --ks-pass env:EZCH_V15_PASSWORD --key-pass env:EZCH_V15_PASSWORD --out $signed $aligned
    if ($LASTEXITCODE -ne 0) { throw 'Signing failed. The existing APK has not been overwritten.' }
    $verification = (& "$buildTools\apksigner.bat" verify --verbose --print-certs $signed) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $verification -notmatch "Signer #1 certificate SHA-256 digest:\s*$expectedCertificate") {
        throw 'The signature does not match the existing 1.5 v16 key.'
    }
    & "$buildTools\zipalign.exe" -c -P 16 4 $signed
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK alignment failed.' }
    New-Item -ItemType Directory -Path (Split-Path -Parent $outputPath) -Force | Out-Null
    Copy-Item -LiteralPath $signed -Destination $outputPath -Force
    Write-Host "Ready: $outputPath"
    Write-Host "Certificate: $expectedCertificate"
    Write-Host ('SHA256: ' + (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash.ToLowerInvariant())
}
finally {
    [Environment]::SetEnvironmentVariable('EZCH_V15_PASSWORD', $previousPassword, 'Process')
    $env:JAVA_HOME = $previousJava
    $password = $null
    if (Test-Path -LiteralPath $temporaryDirectory) {
        $resolvedDirectory = (Resolve-Path -LiteralPath $temporaryDirectory).Path
        if (!$resolvedDirectory.StartsWith([IO.Path]::GetFullPath($temporaryParent).TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Temporary signing path is outside the allowed project directory.'
        }
        Remove-Item -LiteralPath $resolvedDirectory -Recurse -Force
    }
}
