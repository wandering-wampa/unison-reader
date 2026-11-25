plugins {
    id("org.jetbrains.kotlin.jvm")
}

// The book model, EPUB parsing and the sync engine, shared by the Android app and UnisonServer.
// ONNX Runtime and org.json are compileOnly: the app supplies onnxruntime-android and the
// platform's org.json; the server supplies the JVM artifacts.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api("org.jsoup:jsoup:1.21.2")
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.31.1")
    compileOnly("org.json:json:20250517")
}
