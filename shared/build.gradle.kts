import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp)
}

kotlin {
    android {
        namespace = "com.scanface.shared"
        compileSdk = 37
        minSdk = 26
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        withHostTestBuilder {}.configure {}
    }
    jvm("desktop") // Fast, hardware-independent shared-domain tests.
    iosArm64()
    iosSimulatorArm64()
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "ScanFaceKit"
            isStatic = true
            binaryOption("bundleId", "com.scanface.shared")
        }
        compilations.configureEach {
            compilerOptions.options.freeCompilerArgs.add("-Xoverride-konan-properties=osVersionMin.ios_arm64=17.0;osVersionMin.ios_simulator_arm64=17.0")
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation("io.ktor:ktor-client-mock:3.6.0")
        }
        androidMain.dependencies {
            implementation(libs.activity.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.androidx.core)
            implementation(libs.androidx.exif)
            implementation(libs.camera.core)
            implementation(libs.camera.camera2)
            implementation(libs.camera.lifecycle)
            implementation(libs.camera.view)
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies { implementation(libs.ktor.client.darwin) }
        val desktopMain by getting { dependencies { implementation(compose.desktop.currentOs) } }
    }
}
