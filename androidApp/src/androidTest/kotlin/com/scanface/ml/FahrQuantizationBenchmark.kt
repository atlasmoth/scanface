package com.scanface.ml

import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly enabled with `-e scanfaceBenchmark true`; no extra shipping resources. */
@RunWith(AndroidJUnit4::class)
class FahrQuantizationBenchmark {
    @Test
    fun comparePreparedModels() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in model benchmark", arguments.getString("scanfaceBenchmark") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "model-benchmark").canonicalFile
        val manifestFile = File(directory, "manifest.json")
        val manifest = JSONObject(manifestFile.readText())
        check(manifest.getInt("schema") == 1)
        val cases = manifest.getJSONArray("cases").objects().map { entry ->
            val file = checkedFile(directory, entry)
            val bytes = file.readBytes()
            check(bytes.size == 112 * 112 * 3 * 4)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val pixels = FloatArray(112 * 112 * 3).also { buffer.get(it) }
            check(pixels.all { it.isFinite() && it in 0f..255f })
            Case(entry.getString("name"), entry.getString("kind"), entry.getDouble("reference_years"), pixels)
        }
        check(cases.size == 24 && cases.map { it.name }.toSet().size == 24)
        val timingCase = cases.single { it.name == manifest.getString("timing_case") }
        val models = manifest.getJSONArray("models").objects().map { entry ->
            entry to checkedFile(directory, entry)
        }
        check(models.isNotEmpty() && models.map { it.first.getString("name") }.toSet().size == models.size)
        val warmups = manifest.getInt("warmups").also { check(it in 1..100) }
        val iterations = manifest.getInt("iterations").also { check(it in 5..1000) }
        val rounds = manifest.getInt("rounds").also { check(it in 1..20) }
        val results = JSONArray()
        val report = JSONObject()
            .put("schema", 1).put("manifest_sha256", sha256(manifestFile))
            .put("created_epoch_ms", System.currentTimeMillis())
            .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("hardware", Build.HARDWARE).put("fingerprint", Build.FINGERPRINT)
                .put("api", Build.VERSION.SDK_INT).put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())))
            .put("runtime", "com.google.ai.edge.litert.CompiledModel")
            .put("declared_runtime_version", manifest.getString("litert_dependency_version"))
            .put("accelerator", "CPU").put("threads", 2)
            .put("timing_scope", manifest.getString("timing_scope"))
            .put("timing_case", timingCase.name).put("warmups_per_round", warmups)
            .put("iterations_per_round", iterations).put("rounds", rounds)
            .put("order", "Model order rotates by one each round; only one model loaded at a time")
            .put("results", results)
        val reportFile = File(directory, "native-android.json")
        val power = context.getSystemService(PowerManager::class.java)
        for (round in 0 until rounds) {
            for (position in models.indices) {
                val (metadata, file) = models[(position + round) % models.size]
                val result = JSONObject().put("name", metadata.getString("name"))
                    .put("sha256", metadata.getString("sha256")).put("bytes", file.length())
                    .put("round", round).put("position", position)
                    .put("thermal_status_before", if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1)
                results.put(result)
                var model: CompiledModel? = null
                var inputs = emptyList<TensorBuffer>()
                var outputs = emptyList<TensorBuffer>()
                try {
                    val started = SystemClock.elapsedRealtimeNanos()
                    model = CompiledModel.create(file.absolutePath, CompiledModel.Options(Accelerator.CPU).apply {
                        cpuOptions = CompiledModel.CpuOptions(numThreads = 2)
                    })
                    result.put("model_creation_ms", elapsedMs(started))
                    val input = model.getInputTensorType("args_0", LiteRtAgeModel.SIGNATURE)
                    val output = model.getOutputTensorType("output_0", LiteRtAgeModel.SIGNATURE)
                    check(input.elementType == TensorType.ElementType.FLOAT && input.layout?.dimensions == listOf(1, 112, 112, 3))
                    check(output.elementType == TensorType.ElementType.FLOAT && output.layout?.dimensions == listOf(1))
                    val bufferStarted = SystemClock.elapsedRealtimeNanos()
                    inputs = model.createInputBuffers(LiteRtAgeModel.SIGNATURE)
                    outputs = model.createOutputBuffers(LiteRtAgeModel.SIGNATURE)
                    check(inputs.size == 1 && outputs.size == 1)
                    result.put("buffer_creation_ms", elapsedMs(bufferStarted))
                    fun predict(pixels: FloatArray): Double {
                        inputs.single().writeFloat(pixels)
                        model.run(inputs, outputs, LiteRtAgeModel.SIGNATURE)
                        val values = outputs.single().readFloat()
                        check(values.size == 1 && values[0].isFinite()) { "Invalid prediction" }
                        return values[0].toDouble()
                    }
                    val firstStarted = SystemClock.elapsedRealtimeNanos()
                    result.put("first_prediction_years", predict(timingCase.pixels))
                    result.put("first_inference_ms", elapsedMs(firstStarted))
                    if (round == 0) {
                        val predictions = JSONArray()
                        cases.forEach { case ->
                            val age = predict(case.pixels)
                            predictions.put(JSONObject().put("name", case.name).put("kind", case.kind)
                                .put("reference_years", case.reference).put("prediction_years", age)
                                .put("signed_drift_years", age - case.reference)
                                .put("absolute_drift_years", abs(age - case.reference)))
                        }
                        result.put("predictions", predictions)
                    }
                    repeat(warmups) { predict(timingCase.pixels) }
                    val durations = DoubleArray(iterations) {
                        val start = SystemClock.elapsedRealtimeNanos()
                        predict(timingCase.pixels)
                        elapsedMs(start)
                    }
                    val sorted = durations.sorted()
                    val median = if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2 else sorted[sorted.size / 2]
                    result.put("warm_ms", JSONArray(durations.toList())).put("warm_median_ms", median)
                        .put("warm_p90_ms", sorted[ceil(sorted.size * .9).toInt() - 1])
                        .put("status", "ok")
                } catch (failure: Exception) {
                    result.put("status", "error").put("error", "${failure.javaClass.simpleName}: ${failure.message}")
                } finally {
                    inputs.forEach { it.close() }
                    outputs.forEach { it.close() }
                    model?.close()
                    result.put("thermal_status_after", if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1)
                    reportFile.writeText(report.toString(2) + "\n")
                }
            }
        }
        check(results.objects().all { it.getString("status") == "ok" }) { "At least one candidate failed; inspect native-android.json" }
    }

    private data class Case(val name: String, val kind: String, val reference: Double, val pixels: FloatArray)
    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
    private fun elapsedMs(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
    private fun checkedFile(directory: File, entry: JSONObject): File {
        val file = File(directory, entry.getString("file")).canonicalFile
        check(file.toPath().startsWith(directory.toPath()) && file.isFile)
        check(sha256(file) == entry.getString("sha256")) { "Benchmark file hash mismatch: ${file.name}" }
        return file
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
