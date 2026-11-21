package com.jarvis.app.ui.main.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.jarvis.app.ui.theme.JarvisColors
import com.jarvis.core.state.JarvisState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * 음성 파형 애니메이션.
 *
 * [level](0..1)이 0이면 아주 낮은 대기 파형만 표시한다. PHASE 3에서 실제 마이크 입력 RMS와
 * TTS 출력 레벨을 [level]로 전달한다.
 */
@Composable
fun Waveform(state: JarvisState, level: Float, modifier: Modifier = Modifier, barCount: Int = 44) {
    val time = rememberAnimationSeconds()
    Canvas(modifier.clearAndSetSemantics { }) {
        val t = time.value
        val color = JarvisColors.forState(state)
        val baseAmplitude = if (state.isBusy) 0.12f else 0.04f
        // 애니메이션이 정지된 환경(미리보기·테스트)에서도 파형 모양이 보이도록 위상 기본값을 둔다.
        val phase = if (t == 0f) 1.3f else t
        val amplitude = baseAmplitude + level.coerceIn(0f, 1f) * 0.85f
        val speed = 2f + JarvisColors.energy(state) * 3f
        val spacing = size.width / barCount
        val barWidth = max(spacing * 0.45f, 1.5f)
        val midY = size.height / 2f

        for (i in 0 until barCount) {
            val position = i / (barCount - 1f)
            val envelope = sin(position * PI.toFloat())
            val wave = 0.55f * sin(phase * speed + i * 0.42f) +
                0.30f * sin(phase * speed * 1.7f + i * 0.19f) +
                0.15f * sin(phase * speed * 0.6f - i * 0.77f)
            val normalized = (abs(wave) * 0.8f + 0.2f) * envelope * amplitude
            val height = max(normalized * size.height, 2f)
            drawRoundRect(
                color = color.copy(alpha = 0.35f + 0.65f * envelope),
                topLeft = Offset(i * spacing + (spacing - barWidth) / 2f, midY - height / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}
