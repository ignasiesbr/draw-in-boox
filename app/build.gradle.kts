plugins { id("com.android.application") }

val rendererBackend = providers.gradleProperty("booxRenderer").orElse("official").get()
require(rendererBackend in setOf("official", "recovered")) { "booxRenderer must be official or recovered" }
val labBuild = rendererBackend == "recovered" || providers.gradleProperty("booxLab").orNull == "true"

android {
    namespace = "art.boox.probe"
    compileSdk = 37
    defaultConfig {
        applicationId = "art.boox.probe"
        minSdk = 29
        targetSdk = 32
        versionCode = 1
        versionName = "0.1-probe"
        if (labBuild) applicationIdSuffix = ".lab"
        manifestPlaceholders["appLabel"] = "Draw in Boox"
        buildConfigField("boolean", "BOOX_LAB", labBuild.toString())
        buildConfigField("String", "RENDERER_BACKEND", "\"$rendererBackend\"")
        testInstrumentationRunner = "art.boox.probe.RasterInstrumentation"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    if (rendererBackend == "recovered") {
        sourceSets.getByName("main").jniLibs.srcDir(rootProject.file(".tools/recovered-renderer/isolated-jni"))
        sourceSets.getByName("main").assets.srcDir(rootProject.file(".tools/recovered-renderer/assets"))
    }
    packaging { jniLibs.pickFirsts += "**/libc++_shared.so" }
    lint {
        // Sideloaded Android 12 firmware probe; not a Google Play release.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.4.4")
    if (rendererBackend == "recovered") {
        require(rootProject.file(".tools/recovered-renderer/isolated-jni/arm64-v8a/libboox_art_engine.so").isFile) {
            "Run scripts/build-recovered-renderer.ps1 before building the recovered renderer."
        }
    }
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    // BOOX brings early AndroidX releases whose manifests share a namespace.
    implementation("androidx.vectordrawable:vectordrawable:1.2.0")
    implementation("androidx.vectordrawable:vectordrawable-animated:1.2.0")
}
