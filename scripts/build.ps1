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
    & (Join-Path $PSScriptRoot 'build-release.ps1')
    & (Join-Path $PSScriptRoot 'build-release.ps1') -Module launcher -Output (Join-Path $projectDirectory 'dist/EZCH_Launcher_0.17_USB.apk')
    return
}
Push-Location -LiteralPath $projectDirectory
try {
    & (Join-Path $PSScriptRoot 'validate-apps-json.ps1')
    $apkTask = if ($Release) { ':app:assembleRelease' } else { ':app:assembleDebug' }
    & './gradlew.bat' $apkTask ':app:assembleDebugAndroidTest' ':app:lintDebug' ':launcher:assembleDebug' ':launcher:assembleDebugAndroidTest' ':launcher:testDebugUnitTest' ':launcher:lintDebug' '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw "Android build failed with exit code $LASTEXITCODE." }
} finally { Pop-Location }
