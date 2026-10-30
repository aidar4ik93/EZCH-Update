$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
$taskSdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$taskTools = Join-Path $taskSdk 'build-tools/36.0.0'
$taskDirectory = Join-Path $PSScriptRoot '../app/build/queue-fixtures'
New-Item -ItemType Directory -Force -Path $taskDirectory | Out-Null
foreach ($number in 1..10) {
    $manifest = Join-Path $taskDirectory "manifest$number.xml"
    $unsigned = Join-Path $taskDirectory "unsigned$number.apk"
    $signed = Join-Path $PSScriptRoot "../app/src/androidTest/assets/queuefixture$number.apk"
    [IO.File]::WriteAllText($manifest, "<manifest xmlns:android=`"http://schemas.android.com/apk/res/android`" package=`"com.example.ezchqueuefixture$number`" android:versionCode=`"1`" android:versionName=`"1.0`"><uses-sdk android:minSdkVersion=`"26`" android:targetSdkVersion=`"33`"/><application android:label=`"EZCH Queue Fixture $number`" android:hasCode=`"false`"><activity android:name=`"android.app.Activity`" android:exported=`"true`"><intent-filter><action android:name=`"android.intent.action.MAIN`"/><category android:name=`"android.intent.category.LAUNCHER`"/></intent-filter></activity></application></manifest>", [Text.UTF8Encoding]::new($false))
    & "$taskTools/aapt2.exe" link --manifest $manifest -I "$taskSdk/platforms/android-34/android.jar" -o $unsigned
    if ($LASTEXITCODE -ne 0) { throw 'Fixture link failed' }
    & "$taskTools/apksigner.bat" sign --ks (Join-Path $env:USERPROFILE '.android/debug.keystore') --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $signed $unsigned
    if ($LASTEXITCODE -ne 0) { throw 'Fixture signing failed' }
}
