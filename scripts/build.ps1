[CmdletBinding()]
param([switch]$Release)

$ErrorActionPreference = 'Stop'
$projectDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $env:JAVA_HOME) {
    $bundledJdk = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
    if (Test-Path -LiteralPath (Join-Path $bundledJdk 'bin/java.exe')) { $env:JAVA_HOME = $bundledJdk }
}
if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    $installedSdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
    if (Test-Path -LiteralPath $installedSdk) { $env:ANDROID_HOME = $installedSdk }
}
if ($Release) {
    foreach ($variable in @('EZCH_KEYSTORE_FILE','EZCH_STORE_PASSWORD','EZCH_KEY_ALIAS','EZCH_KEY_PASSWORD')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($variable))) {
            throw "Set environment variable $variable before building a signed release."
        }
    }
    if (-not (Test-Path -LiteralPath $env:EZCH_KEYSTORE_FILE -PathType Leaf)) { throw 'Release keystore file was not found.' }
}
Push-Location -LiteralPath $projectDirectory
try {
    & (Join-Path $PSScriptRoot 'validate-apps-json.ps1')
    $apkTask = if ($Release) { ':app:assembleRelease' } else { ':app:assembleDebug' }
    & './gradlew.bat' $apkTask ':app:assembleDebugAndroidTest' ':app:testDebugUnitTest' ':app:lintDebug' '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw "Android build failed with exit code $LASTEXITCODE." }
} finally { Pop-Location }
