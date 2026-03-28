plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kotlinx.serialization.json)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.bouncycastle.bcprov)
            implementation(libs.bouncycastle.bctls)
            implementation(libs.bouncycastle.bcpkix)
            implementation(libs.dnsjava)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
