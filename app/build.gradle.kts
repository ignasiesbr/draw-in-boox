plugins { id("com.android.application") }

android {
    namespace = "art.boox.probe"
    compileSdk = 37
    defaultConfig {
        applicationId = "art.boox.probe"
        minSdk = 29
        targetSdk = 32
        versionCode = 1
        versionName = "0.1-probe"
        testInstrumentationRunner = "art.boox.probe.RasterInstrumentation"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs.pickFirsts += "**/libc++_shared.so" }
    lint {
        // Sideloaded Android 12 firmware probe; not a Google Play release.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.4.4")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    // BOOX brings early AndroidX releases whose manifests share a namespace.
    implementation("androidx.vectordrawable:vectordrawable:1.2.0")
    implementation("androidx.vectordrawable:vectordrawable-animated:1.2.0")
}
