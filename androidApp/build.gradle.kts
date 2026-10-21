import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.scanface"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.scanface.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Run the benchmark explicitly with adb and scanfaceBenchmark=true.
        // Excluding it avoids reporting its opt-in assumption as a CI failure.
        testInstrumentationRunnerArguments["notClass"] = "com.scanface.ml.FahrQuantizationBenchmark"
        // LiteRT ships these ABIs; exclude ML Kit's extra x86 slice so Android
        // cannot install a build whose selected ABI lacks the inference runtime.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { noCompress += "tflite" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    sourceSets["androidTest"].assets.directories.add(rootProject.file("models/fixtures").absolutePath)
}

val verifyBundledFaceAgeModel = tasks.register("verifyBundledFaceAgeModel") {
    group = "verification"
    description = "Checks that the offline FaceAge model and its notices were prepared."
    val modelDirectory = layout.projectDirectory.dir("src/main/assets/models")
    val requiredAssets = listOf(
        "fahr_faceage.tflite",
        "conversion.json",
        "licenses/NOTICE.md",
        "licenses/CC-BY-NC-SA-4.0.txt",
        "licenses/Apache-2.0.txt",
        "licenses/Pillow-MIT-CMU.txt",
        "licenses/RetinaFace-MIT.txt",
    ).map { modelDirectory.file(it).asFile }
    doLast {
        val missing = requiredAssets.filter { !it.isFile || it.length() == 0L }
        check(missing.isEmpty()) {
            "The bundled FaceAge model or its notices are missing: " +
                missing.joinToString { it.name } +
                ". Prepare them before building with: " +
                "conda run -n dl python scripts/models/install_quantized_models.py --runtime litert"
        }
        val selectionFile = rootProject.file("models/selected-models.json")
        check(selectionFile.isFile) { "The checked-in model selection lock is missing." }
        val selection = JsonSlurper().parse(selectionFile) as Map<*, *>
        val locked = (selection["runtimes"] as Map<*, *>)["litert"] as Map<*, *>
        val metadata = JsonSlurper().parse(modelDirectory.file("conversion.json").asFile) as Map<*, *>
        check(locked["model_id"] == "fahr-faceage-1fbb84e-litert-int8w-native-v1" &&
            listOf("model_id", "candidate", "sha256").all { metadata[it] == locked[it] }) {
            "The bundled model metadata differs from the selected LiteRT profile. " +
                "Run conda run -n dl python scripts/models/install_quantized_models.py --runtime litert"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        modelDirectory.file("fahr_faceage.tflite").asFile.inputStream().buffered().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == locked["sha256"]) { "The bundled LiteRT model differs from the checked-in selection hash." }
    }
}
tasks.named("preBuild") { dependsOn(verifyBundledFaceAgeModel) }

dependencies {
    implementation(project(":shared"))
    implementation(libs.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.koin.android)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.litert)
    implementation(libs.mlkit.face.detection)
    androidTestImplementation(libs.test.junit)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.uiautomator)
    // Same Android UI artifacts resolved by shared, visible only to the
    // instrumentation harness that hosts the real ResultScreen.
    androidTestImplementation(libs.test.compose.layout)
    androidTestImplementation(libs.test.compose.material3)
}
