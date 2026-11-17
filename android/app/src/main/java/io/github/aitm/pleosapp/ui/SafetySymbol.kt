package io.github.aitm.pleosapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Small outline symbols share the visual language of the existing right-hand panel. */
@Composable internal fun SafetyShield(modifier: Modifier, color: Color, strokeWidth: Dp = 2.dp) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val outline = Path().apply {
            moveTo(w * .5f, h * .08f)
            lineTo(w * .84f, h * .23f)
            lineTo(w * .84f, h * .5f)
            cubicTo(w * .84f, h * .71f, w * .68f, h * .86f, w * .5f, h * .94f)
            cubicTo(w * .32f, h * .86f, w * .16f, h * .71f, w * .16f, h * .5f)
            lineTo(w * .16f, h * .23f)
            close()
        }
        drawPath(outline, color, style = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable internal fun WarningTriangle(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val outline = Path().apply {
            moveTo(w * .5f, h * .1f)
            lineTo(w * .92f, h * .86f)
            lineTo(w * .08f, h * .86f)
            close()
        }
        val line = 2.5.dp.toPx()
        drawPath(outline, color, style = Stroke(line, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(color, Offset(w * .5f, h * .35f), Offset(w * .5f, h * .57f), line, StrokeCap.Round)
        drawCircle(color, 1.5.dp.toPx(), Offset(w * .5f, h * .71f))
    }
}
