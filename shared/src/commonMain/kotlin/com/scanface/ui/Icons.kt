package com.scanface.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class AppIcon { Face, Overview, Scan, History, Settings, Back, Chevron, Close, Share, Delete, Lock, Check, Sun, Moon, System, Info, Plus }

@Composable
fun Glyph(icon: AppIcon, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface, description: String? = null) {
    Canvas(modifier.size(24.dp).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        val stroke = Stroke(width = 1.7f, cap = StrokeCap.Round)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(tint, Offset(x, y), Offset(x2, y2), 1.7f, StrokeCap.Round)
        fun path(vararg points: Pair<Float, Float>, close: Boolean = false) {
            val p = Path().apply { moveTo(points[0].first, points[0].second); points.drop(1).forEach { lineTo(it.first, it.second) }; if (close) close() }
            drawPath(p, tint, style = stroke)
        }
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            when (icon) {
                AppIcon.Face -> {
                    drawArc(tint, 185f, 170f, false, Offset(4f, 1f), Size(16f, 22f), style = stroke)
                    drawArc(tint, 5f, 170f, false, Offset(4f, 1f), Size(16f, 22f), style = stroke)
                    line(8f, 11f, 9f, 11f); line(15f, 11f, 16f, 11f)
                    drawArc(tint, 20f, 140f, false, Offset(9f, 13f), Size(6f, 3f), style = stroke)
                }
                AppIcon.Overview -> path(3f to 11f, 12f to 3f, 21f to 11f, 21f to 21f, 15f to 21f, 15f to 15f, 9f to 15f, 9f to 21f, 3f to 21f, close = true)
                AppIcon.Scan -> {
                    path(8f to 3f, 3f to 3f, 3f to 8f); path(16f to 3f, 21f to 3f, 21f to 8f)
                    path(3f to 16f, 3f to 21f, 8f to 21f); path(16f to 21f, 21f to 21f, 21f to 16f)
                    drawOval(tint, Offset(8f, 7f), Size(8f, 10f), style = stroke)
                }
                AppIcon.History -> { drawCircle(tint, 9f, Offset(12f, 12f), style = stroke); path(12f to 6f, 12f to 12f, 16f to 15f) }
                AppIcon.Settings -> {
                    drawCircle(tint, 7.5f, Offset(12f, 12f), style = stroke); drawCircle(tint, 2.5f, Offset(12f, 12f), style = stroke)
                    line(12f, 2f, 12f, 4f); line(12f, 20f, 12f, 22f); line(2f, 12f, 4f, 12f); line(20f, 12f, 22f, 12f)
                    line(5f, 5f, 6.5f, 6.5f); line(17.5f, 17.5f, 19f, 19f); line(5f, 19f, 6.5f, 17.5f); line(17.5f, 6.5f, 19f, 5f)
                }
                AppIcon.Back -> path(15f to 4f, 7f to 12f, 15f to 20f)
                AppIcon.Chevron -> path(9f to 5f, 16f to 12f, 9f to 19f)
                AppIcon.Close -> { line(5f, 5f, 19f, 19f); line(19f, 5f, 5f, 19f) }
                AppIcon.Share -> { path(5f to 10f, 5f to 21f, 19f to 21f, 19f to 10f); path(8f to 6f, 12f to 2f, 16f to 6f); line(12f, 2f, 12f, 15f) }
                AppIcon.Delete -> { path(6f to 7f, 7f to 21f, 17f to 21f, 18f to 7f); line(4f, 7f, 20f, 7f); path(9f to 7f, 9f to 3f, 15f to 3f, 15f to 7f); line(10f, 11f, 10f, 17f); line(14f, 11f, 14f, 17f) }
                AppIcon.Lock -> { drawRoundRect(tint, Offset(5f, 10f), Size(14f, 12f), androidx.compose.ui.geometry.CornerRadius(2f), style = stroke); drawArc(tint, 180f, 180f, false, Offset(8f, 2f), Size(8f, 14f), style = stroke); line(12f, 15f, 12f, 18f) }
                AppIcon.Check -> path(4f to 12f, 9f to 17f, 20f to 6f)
                AppIcon.Sun -> { drawCircle(tint, 4f, Offset(12f, 12f), style = stroke); line(12f, 1f, 12f, 4f); line(12f, 20f, 12f, 23f); line(1f, 12f, 4f, 12f); line(20f, 12f, 23f, 12f); line(4f, 4f, 6f, 6f); line(18f, 18f, 20f, 20f); line(4f, 20f, 6f, 18f); line(18f, 6f, 20f, 4f) }
                AppIcon.Moon -> { val p = Path().apply { moveTo(17f, 3f); cubicTo(4f, 0f, 1f, 17f, 12f, 21f); cubicTo(17f, 23f, 22f, 19f, 22f, 15f); cubicTo(12f, 19f, 8f, 9f, 17f, 3f) }; drawPath(p, tint, style = stroke) }
                AppIcon.System -> { drawRoundRect(tint, Offset(2f, 3f), Size(20f, 14f), androidx.compose.ui.geometry.CornerRadius(2f), style = stroke); line(12f, 17f, 12f, 21f); line(7f, 21f, 17f, 21f) }
                AppIcon.Info -> { drawCircle(tint, 9f, Offset(12f, 12f), style = stroke); line(12f, 11f, 12f, 17f); drawCircle(tint, 1f, Offset(12f, 7f)) }
                AppIcon.Plus -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
            }
        }
    }
}
