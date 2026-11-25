plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

// UnisonServer: the library, the sync engine and the API, as a plain JVM service. Built here with
// `gradlew :server:installDist` and shipped to the Docker host as a folder; the Docker image only adds
// a JRE and ffmpeg (see server/deploy).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

val ktor = "3.2.3"

dependencies {
    implementation(project(":core"))
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-server-partial-content:$ktor")
    implementation("io.ktor:ktor-server-auto-head-response:$ktor")
    implementation("io.ktor:ktor-server-call-logging:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("com.microsoft.onnxruntime:onnxruntime:1.31.1")
    implementation("org.json:json:20250517")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test-junit"))
}

application {
    mainClass.set("dev.unison.server.MainKt")
    applicationDefaultJvmArgs = listOf("-Xss4m", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=60")
}

// The web client's reader uses the same vendored foliate-js as the app; it rides along as a static resource.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("vendor/foliate-js")) { include("*.js", "LICENSE", "vendor/zip.js", "vendor/fflate.js"); into("web/foliate") }
}
