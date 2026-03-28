plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    alias(libs.plugins.graalvm.native)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.coroutines.core)
    implementation(libs.clikt)
    implementation(libs.bouncycastle.bcprov)
}

graalvmNative {
    metadataRepository {
        enabled.set(true)
    }
    binaries {
        named("main") {
            imageName.set("server")
            mainClass.set("com.github.swerchansky.vkturnproxy.server.ServerMainKt")
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
    manifest {
        attributes["Main-Class"] = "com.github.swerchansky.vkturnproxy.server.ServerMainKt"
    }
}

