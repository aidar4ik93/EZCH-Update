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
    $launcherBuild = Get-Content -Raw -LiteralPath (Join-Path $projectDirectory 'launcher/app/build.gradle.kts')
    $launcherVersion = [regex]::Match($launcherBuild, 'versionName\s*=\s*"([^"\r\n]+)"').Groups[1].Value.Replace('-USB', '')
    if ([string]::IsNullOrWhiteSpace($launcherVersion)) { throw 'Launcher release version is missing.' }
    & (Join-Path $PSScriptRoot 'build-release.ps1') -Module launcher -Output (Join-Path $projectDirectory "dist/EZCH_Launcher_${launcherVersion}_USB.apk")
    return
}
Push-Location -LiteralPath $projectDirectory
try {
    & (Join-Path $PSScriptRoot 'validate-apps-json.ps1')
    $apkTask = if ($Release) { ':app:assembleRelease' } else { ':app:assembleDebug' }
    & './gradlew.bat' $apkTask ':app:testDebugUnitTest' ':app:assembleDebugAndroidTest' ':app:lintDebug' ':launcher:assembleDebug' ':launcher:assembleDebugAndroidTest' ':launcher:testDebugUnitTest' ':launcher:lintDebug' '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw "Android build failed with exit code $LASTEXITCODE." }
} finally { Pop-Location }
