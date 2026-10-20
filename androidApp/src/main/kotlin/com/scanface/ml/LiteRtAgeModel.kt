package com.scanface.ml

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import java.io.FileNotFoundException

internal interface AgeModel : AutoCloseable {
    fun estimate(pixels: FloatArray): Double
}

internal class MissingAgeModelException : Exception("The bundled FAHR-FaceAge model is missing.")

/** All calls, including close, are confined to the analyzer's serialized worker. */
internal class LiteRtAgeModel(
    private val context: Context,
    private val assetName: String = ASSET_NAME,
) : AgeModel {
    private var compiled: CompiledModel? = null
    private var inputs: List<TensorBuffer> = emptyList()
    private var outputs: List<TensorBuffer> = emptyList()

    override fun estimate(pixels: FloatArray): Double {
        require(pixels.size == 112 * 112 * 3 && pixels.all { it.isFinite() && it in 0f..255f }) {
            "Invalid FAHR-FaceAge RGB tensor"
        }
        val model = compiled ?: load()
        inputs.single().writeFloat(pixels)
        model.run(inputs, outputs, SIGNATURE)
        val result = outputs.single().readFloat()
        check(result.size == 1 && result[0].isFinite() && result[0] in 0f..130f) {
            "FAHR-FaceAge returned an invalid estimate"
        }
        return result[0].toDouble()
    }

    private fun load(): CompiledModel {
        try {
            context.assets.open(assetName).use { /* Confirm packaging before loading JNI. */ }
        } catch (_: FileNotFoundException) {
            throw MissingAgeModelException()
        }
        val model = CompiledModel.create(
            context.assets,
            assetName,
            CompiledModel.Options(Accelerator.CPU).apply {
                cpuOptions = CompiledModel.CpuOptions(numThreads = 2)
            },
        )
        try {
            val input = model.getInputTensorType("args_0", SIGNATURE)
            val output = model.getOutputTensorType("output_0", SIGNATURE)
            check(input.elementType == TensorType.ElementType.FLOAT &&
                input.layout?.dimensions == listOf(1, 112, 112, 3)) { "Unexpected model input contract" }
            check(output.elementType == TensorType.ElementType.FLOAT &&
                output.layout?.dimensions == listOf(1)) { "Unexpected model output contract" }
            inputs = model.createInputBuffers(SIGNATURE)
            outputs = model.createOutputBuffers(SIGNATURE)
            check(inputs.size == 1 && outputs.size == 1) { "Unexpected model tensor count" }
            compiled = model
            return model
        } catch (failure: Throwable) {
            inputs.forEach { it.close() }
            outputs.forEach { it.close() }
            inputs = emptyList()
            outputs = emptyList()
            model.close()
            throw failure
        }
    }

    override fun close() {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        inputs = emptyList()
        outputs = emptyList()
        compiled?.close()
        compiled = null
    }

    companion object {
        const val ASSET_NAME = "models/fahr_faceage.tflite"
        const val SIGNATURE = "serving_default"
        const val MODEL_ID = "fahr-faceage-1fbb84e-litert-native-v1"
    }
}
