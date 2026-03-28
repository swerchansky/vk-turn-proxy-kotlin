plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    alias(libs.plugins.graalvm.native)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.coroutines.core)
    implementation(libs.clikt)
    // Ktor (needed to build HttpClient in CLI — shared exposes it as implementation, not api)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("client")
            mainClass.set("com.github.swerchansky.vkturnproxy.client.ClientMainKt")
            buildArgs.addAll(
                "--no-fallback",
                "--enable-preview",
                "-H:+ReportExceptionStackTraces",
                "-march=compatibility",
                "--initialize-at-build-time=kotlin",
            )
        }
    }
    toolchainDetection.set(true)
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
}

