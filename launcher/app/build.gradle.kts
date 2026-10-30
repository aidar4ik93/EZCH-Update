plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.example.homeezch"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.example.homeezch.usb"
        minSdk = 26
        targetSdk = 37
        versionCode = 111
        versionName = "0.49.1-EVA"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        // R8 + resource shrinking: optimised Compose bytecode is what makes HOME smooth on 2 GB TVs.
        // Baseline profile (src/main/baseline-prof.txt) is installed by profileinstaller on sideload.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            testProguardFiles("proguard-android-test.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    testBuildType = if (providers.gradleProperty("ezchUpgradeAudit").orNull == "true") "release" else "debug"
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(project(":eva-core"))
    implementation(project(":eva-llm"))   // EVA brain on the TV (llama.cpp, runs in the ":llm" process)
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.02.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
