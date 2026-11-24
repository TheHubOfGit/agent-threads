package com.agenthud.threads.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun MicIcon(color: Color, size: Dp = 14.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Capsule head
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.34f, h * 0.12f),
            size = Size(w * 0.32f, h * 0.44f),
            cornerRadius = CornerRadius(w * 0.16f, w * 0.16f),
            style = stroke
        )
        // U-cradle arc
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.20f, h * 0.28f),
            size = Size(w * 0.60f, h * 0.44f),
            style = stroke
        )
        // Stem & base
        drawLine(color, Offset(w * 0.50f, h * 0.72f), Offset(w * 0.50f, h * 0.88f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.34f, h * 0.88f), Offset(w * 0.66f, h * 0.88f), strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
}

@Composable
fun CheckIcon(color: Color, size: Dp = 13.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.18f, h * 0.52f)
            lineTo(w * 0.42f, h * 0.76f)
            lineTo(w * 0.84f, h * 0.26f)
        }
        drawPath(path, color = color, style = Stroke(width = w * 0.15f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun CloseIcon(color: Color, size: Dp = 12.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.15f
        drawLine(color, Offset(w * 0.24f, h * 0.24f), Offset(w * 0.76f, h * 0.76f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.76f, h * 0.24f), Offset(w * 0.24f, h * 0.76f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun RefreshIcon(color: Color, size: Dp = 15.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawArc(
            color = color,
            startAngle = -50f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = Offset(w * 0.20f, h * 0.20f),
            size = Size(w * 0.60f, h * 0.60f),
            style = stroke
        )
        drawLine(color, Offset(w * 0.68f, h * 0.16f), Offset(w * 0.80f, h * 0.32f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.80f, h * 0.32f), Offset(w * 0.62f, h * 0.34f), strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
}

/** A small keyboard: rounded body and three rows of keys. */
@Composable
fun KeyboardIcon(color: Color, size: Dp = 16.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.09f
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.08f, h * 0.24f),
            size = Size(w * 0.84f, h * 0.54f),
            cornerRadius = CornerRadius(w * 0.1f, w * 0.1f),
            style = Stroke(width = sw)
        )
        val r = w * 0.045f
        for (row in 0..1) for (col in 0..3) {
            drawCircle(color, r, Offset(w * (0.26f + col * 0.16f), h * (0.40f + row * 0.13f)))
        }
        drawLine(color, Offset(w * 0.32f, h * 0.66f), Offset(w * 0.68f, h * 0.66f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

/** Three short lines in a bubble: canned quick replies. */
@Composable
fun QuickReplyIcon(color: Color, size: Dp = 16.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.1f
        val bubble = Path().apply {
            moveTo(w * 0.2f, h * 0.18f)
            lineTo(w * 0.8f, h * 0.18f)
            quadraticTo(w * 0.9f, h * 0.18f, w * 0.9f, h * 0.28f)
            lineTo(w * 0.9f, h * 0.62f)
            quadraticTo(w * 0.9f, h * 0.72f, w * 0.8f, h * 0.72f)
            lineTo(w * 0.42f, h * 0.72f)
            lineTo(w * 0.24f, h * 0.88f)
            lineTo(w * 0.26f, h * 0.72f)
            lineTo(w * 0.2f, h * 0.72f)
            quadraticTo(w * 0.1f, h * 0.72f, w * 0.1f, h * 0.62f)
            lineTo(w * 0.1f, h * 0.28f)
            quadraticTo(w * 0.1f, h * 0.18f, w * 0.2f, h * 0.18f)
            close()
        }
        drawPath(bubble, color, style = Stroke(width = sw, join = StrokeJoin.Round))
        drawLine(color, Offset(w * 0.3f, h * 0.38f), Offset(w * 0.7f, h * 0.38f), strokeWidth = sw * 0.9f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.3f, h * 0.54f), Offset(w * 0.58f, h * 0.54f), strokeWidth = sw * 0.9f, cap = StrokeCap.Round)
    }
}

/** A plain gear: ring with six teeth. */
@Composable
fun GearIcon(color: Color, size: Dp = 14.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val c = Offset(w / 2f, w / 2f)
        val sw = w * 0.12f
        drawCircle(color, radius = w * 0.24f, center = c, style = Stroke(width = sw))
        for (i in 0 until 6) {
            val a = Math.toRadians(i * 60.0)
            val inner = w * 0.32f
            val outer = w * 0.46f
            drawLine(
                color,
                Offset(c.x + inner * kotlin.math.cos(a).toFloat(), c.y + inner * kotlin.math.sin(a).toFloat()),
                Offset(c.x + outer * kotlin.math.cos(a).toFloat(), c.y + outer * kotlin.math.sin(a).toFloat()),
                strokeWidth = sw * 1.3f,
                cap = StrokeCap.Round
            )
        }
    }
}
