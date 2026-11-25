import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The default build reads and listens from a UnisonServer: the server does the sentence sync, so the speech
// model (21 MB) and ONNX Runtime (about 75 MB for the two ABIs) stay out of the APK, and importing files is
// off. `-Pstandalone` builds the app that also imports books and syncs them on the device, as it did up to
// 0.9: gradlew :app:assembleDebug -Pstandalone
val standalone = providers.gradleProperty("standalone").isPresent

// A server address of your own stays out of the repository: local.properties may carry
// `unison.defaultServer=host.name`, which the sign-in form then starts with. Without it the form starts empty.
// `-PpublicBuild` ignores it, for an APK that is handed to other people: gradlew :app:assembleDebug -PpublicBuild
val publicBuild = providers.gradleProperty("publicBuild").isPresent
val defaultServer = if (publicBuild) "" else rootProject.file("local.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use { load(it) } }.getProperty("unison.defaultServer") }.orEmpty().trim()
require(Regex("[A-Za-z0-9.:/_-]*").matches(defaultServer)) { "unison.defaultServer in local.properties is not a host name or address" }

android {
    namespace = "dev.homescreenreader"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.homescreenreader"
        minSdk = 35
        targetSdk = 36
        versionCode = 14
        versionName = "0.12.0"
        testInstrumentationRunner = "dev.homescreenreader.ReaderInstrumentation"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { targets += "reader"; arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON") } }
        buildConfigField("boolean", "STANDALONE", standalone.toString())
        buildConfigField("String", "DEFAULT_SERVER", "\"$defaultServer\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    ndkVersion = "27.2.12479018"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    androidResources {
        noCompress += listOf("onnx")
        // The speech model rides along only in the standalone build.
        if (!standalone) ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~:!*.onnx"
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/readerAssets"))
}

val prepareReaderAssets by tasks.registering(Copy::class) {
    from(rootProject.file("vendor/foliate-js")) { include("*.js", "LICENSE", "vendor/zip.js", "vendor/fflate.js") }
    into(layout.buildDirectory.dir("generated/readerAssets/foliate"))
}
tasks.named("preBuild").configure { dependsOn(prepareReaderAssets) }

dependencies {
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation(project(":core"))
    implementation("androidx.documentfile:documentfile:1.1.0")
    // The code that syncs on the device is compiled either way and only ever runs in the standalone build.
    if (standalone) implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    else { compileOnly("com.microsoft.onnxruntime:onnxruntime-android:1.31.1"); androidTestCompileOnly("com.microsoft.onnxruntime:onnxruntime-android:1.31.1") }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
