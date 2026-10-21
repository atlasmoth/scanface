package com.scanface.ml

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

internal data class DetectedFace(
    val bounds: Rect,
    val eyes: List<PointF>,
    val yawDegrees: Float,
    val pitchDegrees: Float,
)

internal class UnusableFaceException(message: String) : Exception(message)

internal data class FaceAlignment(val crop: Rect, val angleRadians: Double)

/**
 * Author-style eye alignment and center-transformed box, using native detector
 * landmarks. ML Kit replaces the author's RetinaFace detector; the resulting
 * pipeline is a research adaptation, not a validated reproduction of the study.
 */
internal object FacePreprocessing {
    private const val INPUT_SIZE = 112

    fun selectAndAlign(faces: List<DetectedFace>, width: Int, height: Int): FaceAlignment {
        if (faces.isEmpty()) throw UnusableFaceException("No face was found. Try a well-lit photo facing the camera.")
        if (faces.size != 1) throw UnusableFaceException("More than one face was found. Use a photo with only your face.")
        val face = faces.single()
        val box = face.bounds
        if (box.width() < 112 || box.height() < 112 ||
            box.width() < width * 0.08 || box.height() < height * 0.08) {
            throw UnusableFaceException("Your face is too small in this photo. Move closer and try again.")
        }
        if (box.left <= width * 0.01 || box.top <= height * 0.01 ||
            box.right >= width * 0.99 || box.bottom >= height * 0.99) {
            throw UnusableFaceException("Keep your whole face inside the photo and try again.")
        }
        if (!face.yawDegrees.isFinite() || !face.pitchDegrees.isFinite() ||
            abs(face.yawDegrees) > 25f || abs(face.pitchDegrees) > 25f) {
            throw UnusableFaceException("Look straight at the camera with your head level and try again.")
        }
        val eyes = face.eyes.sortedBy { it.x }
        if (eyes.size != 2 || eyes.any {
                !it.x.isFinite() || !it.y.isFinite() ||
                    it.x < box.left || it.x >= box.right || it.y < box.top || it.y >= box.bottom
            }) {
            throw UnusableFaceException("Both eyes need to be clearly visible. Try another photo.")
        }
        val dx = (eyes[1].x - eyes[0].x).toDouble()
        val dy = (eyes[1].y - eyes[0].y).toDouble()
        if (hypot(dx, dy) < 20.0) throw UnusableFaceException("Move closer so both eyes are clearly visible.")
        val angle = atan2(dy, dx)
        if (abs(Math.toDegrees(angle)) > 30.0) {
            throw UnusableFaceException("Keep your head level and try another photo.")
        }
        val cx = width / 2.0
        val cy = height / 2.0
        val cosine = cos(angle)
        val sine = sin(angle)
        val offsetX = box.exactCenterX() - cx
        val offsetY = box.exactCenterY() - cy
        val transformedX = cx + cosine * offsetX + sine * offsetY
        val transformedY = cy - sine * offsetX + cosine * offsetY
        // Preserve original box dimensions, as rotate_facial_area does. These are
        // deliberately not the enclosing bounds of the rotated rectangle corners.
        val crop = Rect(
            (transformedX - box.width() / 2.0).toInt().coerceAtLeast(0),
            (transformedY - box.height() / 2.0).toInt().coerceAtLeast(0),
            (transformedX + box.width() / 2.0).toInt().coerceAtMost(width),
            (transformedY + box.height() / 2.0).toInt().coerceAtMost(height),
        )
        if (crop.width() <= 0 || crop.height() <= 0) {
            throw UnusableFaceException("Center your whole face in the photo and try again.")
        }
        return FaceAlignment(crop, angle)
    }

    /** RGB float32 NHWC, raw 0..255. Normalization is inside the exported model. */
    fun pixels(source: Bitmap, alignment: FaceAlignment): FloatArray {
        val aligned = alignedCrop(source, alignment)
        try {
            return resizeBilinear(aligned)
        } finally {
            aligned.recycle()
        }
    }

    private data class Filter(val start: Int, val weights: IntArray)

    private fun filters(sourceSize: Int): List<Filter> {
        val scale = sourceSize.toDouble() / INPUT_SIZE
        val filterScale = maxOf(1.0, scale)
        return List(INPUT_SIZE) { destination ->
            val center = (destination + 0.5) * scale
            val start = (center - filterScale + 0.5).toInt().coerceAtLeast(0)
            val end = (center + filterScale + 0.5).toInt().coerceAtMost(sourceSize)
            val weights = DoubleArray(end - start) { index ->
                maxOf(0.0, 1.0 - abs((start + index - center + 0.5) / filterScale))
            }
            val sum = weights.sum()
            Filter(start, IntArray(weights.size) { (0.5 + weights[it] / sum * (1 shl 22)).toInt() })
        }
    }

    /** Pillow's antialiased BILINEAR uses a widened filter for downsampling and
     * rounds each pass separately with 22-bit coefficients. Android's ordinary
     * two-tap bitmap scaling does not reproduce that model input contract. */
    private fun resizeBilinear(source: Bitmap): FloatArray {
        val original = IntArray(source.width * source.height)
        source.getPixels(original, 0, source.width, 0, 0, source.width, source.height)
        val horizontal = IntArray(INPUT_SIZE * source.height)
        val xFilters = filters(source.width)
        for (y in 0 until source.height) {
            xFilters.forEachIndexed { x, filter ->
                var red = 1 shl 21
                var green = 1 shl 21
                var blue = 1 shl 21
                filter.weights.forEachIndexed { offset, weight ->
                    val pixel = original[y * source.width + filter.start + offset]
                    red += Color.red(pixel) * weight
                    green += Color.green(pixel) * weight
                    blue += Color.blue(pixel) * weight
                }
                horizontal[y * INPUT_SIZE + x] = Color.rgb(
                    (red shr 22).coerceIn(0, 255), (green shr 22).coerceIn(0, 255), (blue shr 22).coerceIn(0, 255),
                )
            }
        }
        val output = FloatArray(INPUT_SIZE * INPUT_SIZE * 3)
        filters(source.height).forEachIndexed { y, filter ->
            for (x in 0 until INPUT_SIZE) {
                var red = 1 shl 21
                var green = 1 shl 21
                var blue = 1 shl 21
                filter.weights.forEachIndexed { offset, weight ->
                    val pixel = horizontal[(filter.start + offset) * INPUT_SIZE + x]
                    red += Color.red(pixel) * weight
                    green += Color.green(pixel) * weight
                    blue += Color.blue(pixel) * weight
                }
                val index = (y * INPUT_SIZE + x) * 3
                output[index] = (red shr 22).coerceIn(0, 255).toFloat()
                output[index + 1] = (green shr 22).coerceIn(0, 255).toFloat()
                output[index + 2] = (blue shr 22).coerceIn(0, 255).toFloat()
            }
        }
        return output
    }

    /**
     * Sample the crop from the author's nearest-neighbour, black-filled WxH
     * rotation canvas. Computing only its retained pixels avoids another full
     * image allocation and is equivalent to rotating first, then cropping.
     */
    private fun alignedCrop(source: Bitmap, alignment: FaceAlignment): Bitmap {
        val width = source.width
        val height = source.height
        val crop = alignment.crop
        require(crop.width() > 0 && crop.height() > 0)
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val output = IntArray(crop.width() * crop.height()) { Color.BLACK }
        val cx = width / 2.0
        val cy = height / 2.0
        val cosine = cos(alignment.angleRadians)
        val sine = sin(alignment.angleRadians)
        // Pillow's nearest affine sampler uses 16.16 fixed point, including the
        // pixel-center offset. Unquantized doubles choose a different neighbour
        // at some boundaries and measurably change the final age-model pixels.
        fun fixed(value: Double) = floor(value * 65_536.0 + 0.5).toLong()
        val a0 = fixed(cosine)
        val a1 = fixed(-sine)
        val a3 = fixed(sine)
        val a4 = fixed(cosine)
        val xBase = fixed(cx - cosine * cx + sine * cy + 0.5 * cosine - 0.5 * sine)
        val yBase = fixed(cy - sine * cx - cosine * cy + 0.5 * sine + 0.5 * cosine)
        for (y in 0 until crop.height()) {
            for (x in 0 until crop.width()) {
                val globalX = crop.left + x
                val globalY = crop.top + y
                val sourceX = ((globalX * a0 + globalY * a1 + xBase) shr 16).toInt()
                val sourceY = ((globalX * a3 + globalY * a4 + yBase) shr 16).toInt()
                if (sourceX in 0 until width && sourceY in 0 until height) {
                    output[y * crop.width() + x] = pixels[sourceY * width + sourceX]
                }
            }
        }
        return Bitmap.createBitmap(output, crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
    }
}
