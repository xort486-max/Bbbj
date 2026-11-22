package com.jarvis.app.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.app.assistant.MainUiState
import com.jarvis.app.ui.main.components.ArcReactorCore
import com.jarvis.app.ui.main.components.CommandInputBar
import com.jarvis.app.ui.main.components.ConversationPanel
import com.jarvis.app.ui.main.components.EngineStatusStrip
import com.jarvis.app.ui.main.components.QuickActionsBar
import com.jarvis.app.ui.main.components.StatusHeader
import com.jarvis.app.ui.main.components.TaskStatusPanel
import com.jarvis.app.ui.main.components.Waveform
import com.jarvis.app.ui.theme.JarvisColors
import com.jarvis.app.ui.theme.JarvisType
import com.jarvis.core.model.JarvisFeature

/** 메인 화면 동작 콜백. */
data class MainActions(
    val onSettings: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onSubmit: (String) -> Unit = {},
    val onFeature: (JarvisFeature) -> Unit = {},
    val onMicrophone: () -> Unit = {},
)

/**
 * JARVIS 메인 인터페이스 (상태 없는 화면 — 미리보기와 스냅샷 테스트에서 그대로 사용).
 *
 * 구성: 헤더(상태·마이크) / 엔진 상태·사용량 / 중앙 AI 코어 + 음성 파형 /
 * 현재 작업 / 대화 기록 / 빠른 기능 / 명령 입력.
 */
@Composable
fun MainScreen(ui: MainUiState, actions: MainActions) {
    val state = ui.state
    val stateColor by animateColorAsState(JarvisColors.forState(state), label = "stateColor")

    Box(Modifier.fillMaxSize().background(JarvisColors.backgroundBrush)) {
        HudGrid()
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusHeader(state, ui.microphone, actions.onSettings)
            EngineStatusStrip(ui.backend, ui.engines, state.activeEngine, ui.usage, actions.onRefresh)

            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                ArcReactorCore(state, ui.audioLevel, Modifier.size(200.dp))
                Text(ui.statusLabel, style = JarvisType.body(15.sp, FontWeight.SemiBold, stateColor))
                if (state is com.jarvis.core.state.JarvisState.Error) {
                    Text(
                        state.message,
                        style = JarvisType.body(12.sp, color = JarvisColors.Failure),
                        textAlign = TextAlign.Center,
                    )
                }
                Waveform(state, ui.audioLevel, Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(32.dp))
            }

            TaskStatusPanel(ui.visibleTasks, state, actions.onCancel)
            ConversationPanel(ui.log.messages, Modifier.weight(1f).heightIn(min = 100.dp))
            if (ui.isListening) ListeningBanner(ui.partialTranscript)
            QuickActionsBar(actions.onFeature)
            CommandInputBar(
                microphoneAvailable = ui.voiceAvailable,
                voiceActive = ui.isListening || ui.isSpeaking || (state.isBusy && ui.voiceSessionActive),
                busy = state.isBusy,
                placeholder = if (ui.pendingRoute == com.jarvis.core.routing.Route.SEARCH) "검색어를 입력하세요 (Google 검색)" else "명령을 입력하세요",
                onSubmit = actions.onSubmit,
                onMicrophone = actions.onMicrophone,
            )
        }
    }
}

/** 음성 인식 중 — 들리는 말을 실시간으로 보여 준다. */
@Composable
private fun ListeningBanner(partial: String?) {
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .background(JarvisColors.Failure.copy(alpha = 0.10f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("● ", style = JarvisType.body(13.sp, color = JarvisColors.Failure))
        Text(
            partial?.takeIf { it.isNotBlank() } ?: "듣는 중… 말씀하세요",
            style = JarvisType.body(14.sp, color = if (partial.isNullOrBlank()) JarvisColors.TextSecondary else JarvisColors.TextPrimary),
            maxLines = 2,
        )
    }
}

/** 배경 격자 + 상단 광원. */
@Composable
private fun HudGrid() {
    Canvas(Modifier.fillMaxSize()) {
        val spacing = 28.dp.toPx()
        val line = JarvisColors.Cyan.copy(alpha = 0.045f)
        var x = 0f
        while (x <= size.width) {
            drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += spacing
        }
        var y = 0f
        while (y <= size.height) {
            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += spacing
        }
        drawCircle(
            Brush.radialGradient(
                listOf(JarvisColors.Cyan.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(size.width / 2f, size.height * 0.3f),
                radius = size.width,
            ),
            radius = size.width,
            center = Offset(size.width / 2f, size.height * 0.3f),
        )
    }
}
