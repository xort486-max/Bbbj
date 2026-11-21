package com.jarvis.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.core.model.ConnectionStatus
import com.jarvis.core.state.JarvisState

/** JARVIS 디자인 시스템 — 블랙/네이비 배경, 시안/블루 포인트. */
object JarvisColors {
    val BackgroundTop = Color(0xFF03060E)
    val BackgroundBottom = Color(0xFF051229)
    val Panel = Color(0x8C081A33)
    val PanelBorder = Color(0x4740CCFF)

    val Cyan = Color(0xFF00D9FF)
    val Blue = Color(0xFF338CFF)
    val TextPrimary = Color(0xFFE0F7FF)
    val TextSecondary = Color(0xFF8CB8D9)
    val TextDim = Color(0xFF5A7A99)
    val Ok = Color(0xFF4DFFB3)
    val Warning = Color(0xFFFFC74D)
    val Failure = Color(0xFFFF5261)

    val backgroundBrush: Brush get() = Brush.verticalGradient(listOf(BackgroundTop, BackgroundBottom))

    fun forState(state: JarvisState): Color = when (state) {
        JarvisState.Idle -> Cyan
        JarvisState.Listening -> Color(0xFF40FFD9)
        JarvisState.Analyzing -> Color(0xFF66A6FF)
        JarvisState.NvidiaResponding -> Color(0xFF73FFA6)
        JarvisState.GoogleSearching -> Color(0xFF4DBFFF)
        JarvisState.ClaudeReasoning -> Color(0xFF9E8CFF)
        JarvisState.ExecutingTask -> Blue
        JarvisState.Completed -> Ok
        is JarvisState.Error -> Failure
    }

    fun forLevel(level: ConnectionStatus.Level): Color = when (level) {
        ConnectionStatus.Level.NEUTRAL -> TextDim
        ConnectionStatus.Level.OK -> Ok
        ConnectionStatus.Level.WARNING -> Warning
        ConnectionStatus.Level.FAILURE -> Failure
    }

    /** 상태별 링 회전 속도 배율. */
    fun energy(state: JarvisState): Float = when (state) {
        JarvisState.Idle, JarvisState.Completed -> 0.35f
        is JarvisState.Error -> 0.15f
        JarvisState.Listening -> 0.8f
        JarvisState.Analyzing, JarvisState.ExecutingTask -> 1.3f
        JarvisState.NvidiaResponding, JarvisState.GoogleSearching -> 1.1f
        JarvisState.ClaudeReasoning -> 1.6f
    }
}

object JarvisType {
    fun hud(size: TextUnit, weight: FontWeight = FontWeight.Medium, color: Color = JarvisColors.TextSecondary) =
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = size, fontWeight = weight, color = color)

    fun body(size: TextUnit = 15.sp, weight: FontWeight = FontWeight.Normal, color: Color = JarvisColors.TextPrimary) =
        TextStyle(fontSize = size, fontWeight = weight, color = color)
}

/** HUD 패널 스타일 (반투명 네이비 + 시안 테두리). */
fun Modifier.hudPanel(padding: Dp = 10.dp, corner: Dp = 14.dp): Modifier {
    val shape = RoundedCornerShape(corner)
    return this
        .background(JarvisColors.Panel, shape)
        .border(1.dp, JarvisColors.PanelBorder, shape)
        .padding(padding)
}

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = JarvisColors.Cyan,
            onPrimary = Color.Black,
            secondary = JarvisColors.Blue,
            background = JarvisColors.BackgroundTop,
            surface = JarvisColors.BackgroundBottom,
            onSurface = JarvisColors.TextPrimary,
            onBackground = JarvisColors.TextPrimary,
            error = JarvisColors.Failure,
        ),
        content = content,
    )
}
