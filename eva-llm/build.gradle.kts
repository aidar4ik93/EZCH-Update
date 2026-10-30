plugins { id("com.android.library") }
android {
    namespace = "com.example.homeezch.llm"
    compileSdk = 37
    ndkVersion = providers.gradleProperty("ezchNdkVersion").orNull ?: "27.2.12479018"
    defaultConfig {
        minSdk = 26
        // TVs: 64-bit and 32-bit ARM userlands; x86_64 only for the emulator test bench.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static") } }
        consumerProguardFiles("consumer-rules.pro")
    }
    externalNativeBuild { cmake { path("src/main/cpp/CMakeLists.txt"); version = providers.gradleProperty("ezchCmakeVersion").orNull ?: "3.22.1" } }
}
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
}
