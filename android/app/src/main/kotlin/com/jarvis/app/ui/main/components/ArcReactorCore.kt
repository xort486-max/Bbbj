package com.jarvis.app.ui.main.components

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import com.jarvis.app.ui.theme.JarvisColors
import com.jarvis.core.state.JarvisState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 애니메이션 시간(초). 시스템 "애니메이션 삭제" 설정이 켜져 있거나 미리보기/테스트 환경이면 0에 고정된다.
 */
@Composable
fun rememberAnimationSeconds(): State<Float> {
    val inspection = LocalInspectionMode.current
    val context = LocalContext.current
    val animationsEnabled = remember(context) { ValueAnimator.areAnimatorsEnabled() }
    return produceState(0f, inspection, animationsEnabled) {
        if (inspection || !animationsEnabled) return@produceState
        val start = withInfiniteAnimationFrameNanos { it }
        while (true) {
            withInfiniteAnimationFrameNanos { now -> value = (now - start) / 1_000_000_000f }
        }
    }
}

/**
 * 중앙 AI 코어 — 상태에 따라 색상·회전 속도·맥동이 바뀌는 반응형 표시 장치.
 * [audioLevel](0..1)은 PHASE 3에서 마이크 입력/TTS 출력 레벨과 연결된다.
 */
@Composable
fun ArcReactorCore(state: JarvisState, audioLevel: Float, modifier: Modifier = Modifier) {
    val time = rememberAnimationSeconds()
    Canvas(
        modifier
            .aspectRatio(1f)
            .semantics {
                contentDescription = "JARVIS 코어"
                stateDescription = state.displayName
            },
    ) {
        drawCore(state, audioLevel.coerceIn(0f, 1f), time.value)
    }
}

private fun DrawScope.drawCore(state: JarvisState, level: Float, t: Float) {
    val color = JarvisColors.forState(state)
    val energy = JarvisColors.energy(state)
    val c = center
    val radius = min(size.width, size.height) / 2f
    val pulse = 0.5f + 0.5f * sin(t * (1.2f + energy * 1.5f))

    // 1. 외곽 광채
    val glowRadius = radius * (0.95f + 0.04f * pulse + 0.08f * level)
    drawCircle(
        brush = Brush.radialGradient(
            0.2f to color.copy(alpha = 0.28f),
            0.6f to color.copy(alpha = 0.06f),
            1f to Color.Transparent,
            center = c,
            radius = glowRadius,
        ),
        radius = glowRadius,
        center = c,
    )

    // 2. 바깥 눈금 링 (천천히 회전)
    val tickRotation = t * 0.12f * energy
    for (i in 0 until 72) {
        val angle = i / 72f * 2f * PI.toFloat() + tickRotation
        val major = i % 6 == 0
        drawLine(
            color = color.copy(alpha = 0.55f),
            start = polar(c, radius * if (major) 0.84f else 0.87f, angle),
            end = polar(c, radius * 0.91f, angle),
            strokeWidth = radius * 0.012f,
        )
    }

    // 3. 분할 호 링 (반대 방향 회전, 겹친 선으로 글로우 표현)
    val arcRotation = -t * 0.55f * energy
    drawSegments(c, radius * 0.76f, 3, 0.9f, arcRotation, color.copy(alpha = 0.18f), radius * 0.11f)
    drawSegments(c, radius * 0.76f, 3, 0.9f, arcRotation, color.copy(alpha = 0.35f), radius * 0.065f)
    drawSegments(c, radius * 0.76f, 3, 0.9f, arcRotation, color, radius * 0.03f)

    // 4. 안쪽 링 (빠른 회전 짧은 호)
    drawSegments(c, radius * 0.63f, 6, 0.35f, t * 0.9f * energy, color.copy(alpha = 0.7f), radius * 0.02f)
    drawCircle(color.copy(alpha = 0.25f), radius = radius * 0.56f, center = c, style = Stroke(radius * 0.01f))

    // 5. 중심 코어 (맥동 + 오디오 레벨)
    val coreRadius = radius * (0.30f + 0.025f * pulse + 0.10f * level)
    drawCircle(
        brush = Brush.radialGradient(
            0f to color.copy(alpha = 0.55f),
            1f to Color.Transparent,
            center = c,
            radius = coreRadius * 1.6f,
        ),
        radius = coreRadius * 1.6f,
        center = c,
    )
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.95f),
            0.55f to color.copy(alpha = 0.85f),
            1f to color.copy(alpha = 0.15f),
            center = c,
            radius = coreRadius,
        ),
        radius = coreRadius,
        center = c,
    )

    // 6. 코어 내부 삼각 구조
    val triRadius = coreRadius * 0.62f
    val triRotation = -PI.toFloat() / 2f + t * 0.2f * energy
    val triangle = Path().apply {
        for (k in 0 until 3) {
            val p = polar(c, triRadius, triRotation + k * 2f * PI.toFloat() / 3f)
            if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }
    drawPath(triangle, Color.White.copy(alpha = 0.55f), style = Stroke(radius * 0.015f, join = StrokeJoin.Round))
}

private fun DrawScope.drawSegments(
    c: Offset,
    r: Float,
    count: Int,
    sweep: Float,
    rotationRad: Float,
    color: Color,
    width: Float,
) {
    val step = 360f / count
    val rotationDeg = Math.toDegrees(rotationRad.toDouble()).toFloat()
    for (i in 0 until count) {
        drawArc(
            color = color,
            startAngle = i * step + rotationDeg,
            sweepAngle = step * sweep,
            useCenter = false,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2, r * 2),
            style = Stroke(width = width, cap = StrokeCap.Round),
        )
    }
}

private fun polar(c: Offset, r: Float, angle: Float) = Offset(c.x + r * cos(angle), c.y + r * sin(angle))
