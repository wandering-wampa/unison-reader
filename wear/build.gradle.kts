import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Unison for Wear OS: listens to the audiobooks on a UnisonServer from the watch alone (Wi-Fi or LTE), with the
// place kept in step with the phone and the web. It is its own app with its own id, so a watch build can never be
// installed over the phone app by mistake, or the other way round.

// A server address of your own stays out of the repository, as in the phone app: local.properties may carry
// `unison.defaultServer=host.name`, which the watch then signs in to without the address being typed on it.
// `-Punison.defaultServer=…` on the command line takes its place for one build (a watch emulator against the local
// test server: gradlew :wear:assembleDebug -Punison.defaultServer=http://10.0.2.2:8099).
// `-PpublicBuild` ignores local.properties, for an APK that is handed to other people; the address is then typed on the watch.
val publicBuild = providers.gradleProperty("publicBuild").isPresent
val defaultServer = (providers.gradleProperty("unison.defaultServer").orNull
    ?: if (publicBuild) null else rootProject.file("local.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use { load(it) } }.getProperty("unison.defaultServer") }).orEmpty().trim()
require(Regex("[A-Za-z0-9.:/_-]*").matches(defaultServer)) { "unison.defaultServer in local.properties is not a host name or address" }

// One version for the phone app and the watch app: the phone app's build file is where a release sets it.
val appBuild = rootProject.file("app/build.gradle.kts").readText()
val appVersionCode = Regex("versionCode = (\\d+)").find(appBuild)!!.groupValues[1].toInt()
val appVersionName = Regex("versionName = \"([^\"]+)\"").find(appBuild)!!.groupValues[1]

android {
    namespace = "dev.homescreenreader.wear"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.homescreenreader.wear"
        // Wear OS 6 and later.
        minSdk = 36
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "DEFAULT_SERVER", "\"$defaultServer\"")
    }
    buildFeatures { buildConfig = true; compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.wear.compose:compose-foundation:1.5.6")
    implementation("androidx.wear.compose:compose-material3:1.5.6")
    implementation("androidx.wear.compose:compose-navigation:1.5.6")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
