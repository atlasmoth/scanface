package com.scanface.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.scanface.core.ScanRecord
import com.scanface.platform.AppHaptic
import com.scanface.platform.LocalAppHaptics
import com.scanface.platform.rememberScanEnvironment
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.math.abs

/** Feedback follows visible work, never a fabricated inference percentage. */
internal suspend fun scanPulseLoop(pulse: () -> Unit) {
    // Let the capture tap settle; very quick inference needs only its outcome cue.
    delay(180)
    while (true) {
        currentCoroutineContext().ensureActive()
        pulse()
        delay(650)
    }
}

@Composable
internal fun ScanPhoto(record: ScanRecord, analyzing: Boolean, feedbackEnabled: Boolean, modifier: Modifier) {
    if (!analyzing) {
        PhotoThumbnail(record, modifier)
        return
    }
    val environment = rememberScanEnvironment()
    val active = environment.isForeground && feedbackEnabled
    val haptics = LocalAppHaptics.current
    val currentHaptics = rememberUpdatedState(haptics)
    val pulseEnabled = active && !environment.reduceMotion
    LaunchedEffect(record.id, pulseEnabled) {
        if (pulseEnabled) scanPulseLoop { currentHaptics.value.perform(AppHaptic.ScanTick) }
    }
    Box(modifier.clip(RoundedCornerShape(14.dp))) {
        PhotoThumbnail(record, Modifier.fillMaxSize())
        if (active) ScanOverlay(environment.reduceMotion, Modifier.matchParentSize())
    }
}

/** Edge instruments and a narrow light plane keep the original face unobscured. */
@Composable
private fun ScanOverlay(reduceMotion: Boolean, modifier: Modifier) {
    val sweep: State<Float> = if (reduceMotion) rememberUpdatedState(.5f) else {
        rememberInfiniteTransition(label = "Face scan").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse),
            label = "Scan light",
        )
    }
    Canvas(modifier.testTag("scan.overlay").semantics {
        contentDescription = "Scan in progress"
        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
    }) {
        val mint = Color(0xFFA6F0D1)
        val white = Color(0xFFE0FFF2)
        val inset = minOf(14.dp.toPx(), size.minDimension * .08f)
        val left = inset
        val right = size.width - inset
        val top = inset
        val bottom = size.height - inset
        val arm = minOf(23.dp.toPx(), size.minDimension * .12f)
        val corner = Path().apply {
            moveTo(left, top + arm); lineTo(left, top); lineTo(left + arm, top)
            moveTo(right - arm, top); lineTo(right, top); lineTo(right, top + arm)
            moveTo(left, bottom - arm); lineTo(left, bottom); lineTo(left + arm, bottom)
            moveTo(right - arm, bottom); lineTo(right, bottom); lineTo(right, bottom - arm)
        }
        // A fine dark keyline keeps mint legible against both pale and dark photos.
        drawPath(corner, Color.Black.copy(alpha = .18f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        drawPath(corner, mint.copy(alpha = .82f), style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))

        if (!reduceMotion) {
            // Read animation state in the draw phase: the photo never reloads at frame rate.
            val position = sweep.value
            val travelTop = top + arm + 6.dp.toPx()
            val travelBottom = bottom - arm - 6.dp.toPx()
            val y = travelTop + (travelBottom - travelTop) * position
            val halfBand = minOf(18.dp.toPx(), size.height * .065f)
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent, .5f to mint.copy(alpha = .075f), 1f to Color.Transparent,
                    startY = y - halfBand, endY = y + halfBand,
                ),
                topLeft = Offset(left, y - halfBand), size = Size(right - left, halfBand * 2),
            )
            val start = Offset(left + 4.dp.toPx(), y)
            val end = Offset(right - 4.dp.toPx(), y)
            drawLine(Color.Black.copy(alpha = .16f), start, end, strokeWidth = 2.dp.toPx())
            drawLine(
                Brush.horizontalGradient(
                    listOf(mint.copy(alpha = .25f), white.copy(alpha = .7f), mint.copy(alpha = .25f)),
                    startX = start.x, endX = end.x,
                ), start, end, strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round,
            )
            for (x in listOf(left, right)) {
                drawCircle(mint.copy(alpha = .13f), 5.dp.toPx(), Offset(x, y))
                drawCircle(white.copy(alpha = .9f), 1.6.dp.toPx(), Offset(x, y))
                for (index in 1..7) {
                    val tickPosition = index / 8f
                    val tickY = travelTop + (travelBottom - travelTop) * tickPosition
                    val proximity = (1f - abs(position - tickPosition) * 7).coerceIn(0f, 1f)
                    val direction = if (x == left) 1 else -1
                    drawLine(mint.copy(alpha = .18f + proximity * .38f),
                        Offset(x, tickY), Offset(x + direction * 3.dp.toPx(), tickY),
                        strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }
    }
}
