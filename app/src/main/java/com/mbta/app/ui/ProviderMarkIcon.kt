package com.mbta.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mbta.app.model.AiConnection
import com.mbta.app.model.ConnectionKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private enum class ProviderBrand {
    CLAUDE,
    GEMINI,
    OPENAI,
    ANTIGRAVITY,
    TERMUX,
    OTHER
}

private fun detectProviderBrand(connection: AiConnection?): ProviderBrand {
    if (connection == null) return ProviderBrand.OTHER
    val kind = connection.kind
    val name = connection.name.lowercase()
    val url = connection.endpointUrl.lowercase()
    return when {
        kind == ConnectionKind.TERMUX_CLI -> ProviderBrand.TERMUX
        kind == ConnectionKind.CLAUDE_WEB ||
            kind == ConnectionKind.CLAUDE_API ||
            name.contains("claude") ||
            url.contains("/claude") -> ProviderBrand.CLAUDE
        kind == ConnectionKind.GEMINI_WEB ||
            kind == ConnectionKind.GEMINI_API ||
            name.contains("gemini") -> ProviderBrand.GEMINI
        kind == ConnectionKind.CHATGPT_WEB ||
            kind == ConnectionKind.OPENAI_API ||
            name.contains("gpt") ||
            name.contains("openai") ||
            name.contains("codex") ||
            url.contains("/codex") -> ProviderBrand.OPENAI
        name.contains("antigravity") ||
            name.contains("agy") ||
            url.contains("/agy") -> ProviderBrand.ANTIGRAVITY
        else -> ProviderBrand.OTHER
    }
}

@Composable
fun ProviderMarkIcon(
    connection: AiConnection?,
    size: Dp = 16.dp
) {
    when (detectProviderBrand(connection)) {
        ProviderBrand.CLAUDE -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .background(Color(0xFFD97757), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(size)) {
                    val cx = this.size.width / 2f
                    val cy = this.size.height / 2f
                    val r = this.size.minDimension * 0.30f
                    val diag = r * 0.7071f
                    val stroke = (this.size.minDimension * 0.12f).coerceAtLeast(1.2f)
                    drawLine(Color.White, Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth = stroke, cap = StrokeCap.Round)
                    drawLine(Color.White, Offset(cx, cy - r), Offset(cx, cy + r), strokeWidth = stroke, cap = StrokeCap.Round)
                    drawLine(Color.White, Offset(cx - diag, cy - diag), Offset(cx + diag, cy + diag), strokeWidth = stroke, cap = StrokeCap.Round)
                    drawLine(Color.White, Offset(cx - diag, cy + diag), Offset(cx + diag, cy - diag), strokeWidth = stroke, cap = StrokeCap.Round)
                }
            }
        }
        ProviderBrand.GEMINI -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .background(Color(0xFF4285F4), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(size)) {
                    val cx = this.size.width / 2f
                    val cy = this.size.height / 2f
                    val r = this.size.minDimension * 0.34f
                    val path = Path().apply {
                        moveTo(cx, cy - r)
                        quadraticTo(cx, cy, cx + r, cy)
                        quadraticTo(cx, cy, cx, cy + r)
                        quadraticTo(cx, cy, cx - r, cy)
                        quadraticTo(cx, cy, cx, cy - r)
                        close()
                    }
                    drawPath(path, color = Color.White)
                }
            }
        }
        ProviderBrand.OPENAI -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .background(Color(0xFF10A37F), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(size)) {
                    val cx = this.size.width / 2f
                    val cy = this.size.height / 2f
                    val ringR = this.size.minDimension * 0.15f
                    val offsetR = this.size.minDimension * 0.14f
                    val stroke = (this.size.minDimension * 0.08f).coerceAtLeast(1f)
                    for (i in 0 until 6) {
                        val angle = (i * 60.0) * PI / 180.0
                        val px = cx + (offsetR * cos(angle)).toFloat()
                        val py = cy + (offsetR * sin(angle)).toFloat()
                        drawCircle(
                            color = Color.White,
                            radius = ringR,
                            center = Offset(px, py),
                            style = Stroke(width = stroke)
                        )
                    }
                }
            }
        }
        ProviderBrand.ANTIGRAVITY -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .background(Color(0xFF6366F1), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(size)) {
                    val cx = this.size.width / 2f
                    val cy = this.size.height / 2f
                    val r = this.size.minDimension * 0.28f
                    val stroke = (this.size.minDimension * 0.13f).coerceAtLeast(1.3f)
                    val path = Path().apply {
                        moveTo(cx - r, cy + r * 0.65f)
                        lineTo(cx, cy - r * 0.85f)
                        lineTo(cx + r, cy + r * 0.65f)
                    }
                    drawPath(path, color = Color.White, style = Stroke(width = stroke, cap = StrokeCap.Round))
                }
            }
        }
        ProviderBrand.TERMUX -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .background(Color(0xFF263238), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = ">_",
                    color = Color(0xFF4ADE80),
                    fontSize = (size.value * 0.48f).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        ProviderBrand.OTHER -> {
            val initial = connection?.name?.firstOrNull()?.uppercase() ?: "?"
            Box(
                modifier = Modifier
                    .size(size)
                    .background(Color(0xFF607D8B), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    color = Color.White,
                    fontSize = (size.value * 0.58f).sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
