package com.scanface.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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

internal data class ScanPresentation(val active: Boolean, val reduceMotion: Boolean)

@Composable
internal fun rememberScanPresentation(analyzing: Boolean, scanComplete: Boolean, feedbackEnabled: Boolean): ScanPresentation {
    val environment = rememberScanEnvironment()
    val active = (analyzing || scanComplete) && environment.isForeground && feedbackEnabled
    val haptics = LocalAppHaptics.current
    val currentHaptics = rememberUpdatedState(haptics)
    val pulseEnabled = active && analyzing && !scanComplete && !environment.reduceMotion
    LaunchedEffect(pulseEnabled) {
        if (pulseEnabled) scanPulseLoop { currentHaptics.value.perform(AppHaptic.ScanTick) }
    }
    return ScanPresentation(active, environment.reduceMotion)
}

@Composable
internal fun CameraScanOverlay(analyzing: Boolean, scanComplete: Boolean, feedbackEnabled: Boolean, modifier: Modifier) {
    val presentation = rememberScanPresentation(analyzing, scanComplete, feedbackEnabled)
    if (presentation.active) ScanOverlay(scanComplete, presentation.reduceMotion, modifier)
}

/** Edge instruments and a narrow light plane keep the camera preview unobscured. */
@Composable
private fun ScanOverlay(scanComplete: Boolean, reduceMotion: Boolean, modifier: Modifier) {
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(scanComplete, reduceMotion) {
        when {
            reduceMotion -> sweep.snapTo(if (scanComplete) 1f else .5f)
            // Finish the sweep during the outgoing camera fade. Navigation never waits for it.
            scanComplete -> sweep.animateTo(1f, tween(160, easing = FastOutSlowInEasing))
            else -> {
                while (true) {
                    sweep.animateTo(1f, tween(2600, easing = LinearEasing))
                    sweep.animateTo(0f, tween(2600, easing = LinearEasing))
                }
            }
        }
    }
    Canvas(modifier.clipToBounds().testTag("scan.overlay").semantics {
        contentDescription = if (scanComplete) "Scan complete" else "Scan in progress"
        progressBarRangeInfo = if (scanComplete) ProgressBarRangeInfo(1f, 0f..1f) else ProgressBarRangeInfo.Indeterminate
    }) {
        if (size.minDimension <= 0f) return@Canvas
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
            val travelTop = minOf(1.dp.toPx(), size.height / 2)
            val travelBottom = size.height - travelTop
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
