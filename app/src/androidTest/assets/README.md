# Signed APK regression fixture

`wrong-signer-fixture.apk` uses the same disposable test key, with package `com.example.ezchupdate`, versionCode 4242 and versionName 42.42. It tests production rejection of a correctly parsed APK attempting to replace the installed app with a different signer. Tests do not install it.

`signed-fixture.apk` is a minimal, generated, no-code APK for `org.example.ezch.fixture`, versionCode 42, versionName 4.2, minSdk 28. It is signed with a disposable RSA test certificate (CN=EZCH Regression Fixture), unrelated to the application release key. It is never part of the shipped application APK.

The fixture tests the production archive validation against Android's real PackageManager. Android 13's archive API requires GET_SIGNATURES to collect certificates even when GET_SIGNING_CERTIFICATES is requested. Repacking the fixture without META-INF also removes its signature blocks and provides an unsigned negative case.

Source manifest:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="org.example.ezch.fixture" android:versionCode="42" android:versionName="4.2">
    <uses-sdk android:minSdkVersion="28" android:targetSdkVersion="33" />
    <application android:label="EZCH Test Fixture" android:hasCode="false" />
</manifest>
```

To regenerate, use Android Build Tools: `aapt2 link` with this manifest and android.jar; `zipalign -f 4`; generate a disposable RSA certificate with `keytool`; sign the aligned file with `apksigner sign --ks <test-keystore>`. Keep the test keystore outside tracked files. The fixture certificate must differ from the debug application certificate to test real signer mismatch.
