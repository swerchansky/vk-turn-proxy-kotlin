plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

kotlin {
    jvm()
    androidTarget {
        compilations.all {
            kotlinOptions.jvmTarget = "11"
        }
    }

    sourceSets {
        val jvmAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.bouncycastle.bcprov)
                implementation(libs.bouncycastle.bctls)
                implementation(libs.bouncycastle.bcpkix)
                implementation(libs.ktor.client.cio)
            }
        }

        val jvmMain by getting {
            dependsOn(jvmAndroidMain)
            dependencies {
                implementation(libs.dnsjava)
            }
        }

        val androidMain by getting {
            dependsOn(jvmAndroidMain)
        }

        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.github.swerchansky.vkturnproxy.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
