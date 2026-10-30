plugins { id("com.android.library") }
android {
    namespace = "com.example.homeezch.ai.core"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
}
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
