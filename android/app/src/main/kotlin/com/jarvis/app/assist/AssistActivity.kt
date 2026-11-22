package com.jarvis.app.assist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.app.JarvisApplication
import com.jarvis.app.MainActivity
import com.jarvis.app.assistant.MainUiState
import com.jarvis.app.ui.main.components.ArcReactorCore
import com.jarvis.app.ui.main.components.SourceLinks
import com.jarvis.app.ui.main.components.Waveform
import com.jarvis.app.ui.theme.JarvisColors
import com.jarvis.app.ui.theme.JarvisTheme
import com.jarvis.app.ui.theme.JarvisType
import com.jarvis.core.model.ConversationMessage
import kotlinx.coroutines.delay

/**
 * 시리·빅스비처럼 다른 앱 위에 뜨는 JARVIS 어시스턴트 화면.
 *
 * 열리는 경로: 기본 어시스턴트 호출(측면 버튼·홈 길게 누르기), "자비스" 호출어, 빠른 설정 타일, 앱 아이콘 바로가기.
 * 열리면 바로 듣기 시작하고, 답을 읽은 뒤 잠시 후 저절로 닫힌다.
 */
class AssistActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = (application as JarvisApplication).container.controller
        val greet = intent.getBooleanExtra(EXTRA_GREET, false)
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        controller.setMicPermission(hasMic)
        val firstMessageIndex = controller.ui.value.log.messages.size
        val started = savedInstanceState == null && hasMic && controller.startAssistListening(greet)

        setContent {
            JarvisTheme {
                val ui by controller.ui.collectAsStateWithLifecycle()
                var interactions by remember { mutableIntStateOf(0) }
                var sessionSeen by remember { mutableStateOf(started) }
                LaunchedEffect(ui.voiceSessionActive) { if (ui.voiceSessionActive) sessionSeen = true }

                // 음성 세션이 끝나고 처리 중도 아니면 잠시 뒤 자동으로 닫는다 (사용자가 만지면 시간 연장)
                LaunchedEffect(ui.voiceSessionActive, ui.state.isBusy, interactions, sessionSeen) {
                    if (sessionSeen && !ui.voiceSessionActive && !ui.state.isBusy) {
                        delay(AUTO_CLOSE_MS)
                        finish()
                    }
                }

                AssistOverlay(
                    ui = ui,
                    messages = ui.log.messages.drop(firstMessageIndex),
                    micPermission = hasMic,
                    onDismiss = {
                        controller.stopVoice()
                        finish()
                    },
                    onMic = {
                        interactions++
                        if (ui.voiceSessionActive || ui.isListening || ui.isSpeaking) {
                            controller.stopVoice()
                        } else if (controller.startAssistListening(greet = false)) {
                            sessionSeen = true
                        }
                    },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_GREET = "com.jarvis.app.extra.GREET"
        private const val AUTO_CLOSE_MS = 6_000L
    }
}

@Composable
fun AssistOverlay(
    ui: MainUiState,
    messages: List<ConversationMessage>,
    micPermission: Boolean,
    onDismiss: () -> Unit,
    onMic: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val stateColor = JarvisColors.forState(ui.state)
    val noRipple = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = noRipple, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(interactionSource = noRipple, indication = null) {} // 카드 안 터치는 닫지 않음
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(listOf(JarvisColors.BackgroundBottom, JarvisColors.BackgroundTop)),
                    RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                )
                .border(1.dp, JarvisColors.PanelBorder, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("J.A.R.V.I.S.", style = JarvisType.hud(14.sp, FontWeight.Bold, JarvisColors.TextPrimary), modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenApp) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, "앱 열기", tint = JarvisColors.TextSecondary) }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "닫기", tint = JarvisColors.TextSecondary) }
            }

            ArcReactorCore(ui.state, ui.audioLevel, Modifier.size(120.dp))
            Text(ui.statusLabel, style = JarvisType.body(15.sp, FontWeight.SemiBold, stateColor))
            Waveform(ui.state, ui.audioLevel, Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 24.dp))

            val lastUser = messages.lastOrNull { it.role == ConversationMessage.Role.USER }
            val lastReply = messages.lastOrNull { it.role != ConversationMessage.Role.USER }
            Column(
                Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    !micPermission -> Text(
                        "마이크 권한이 필요합니다. 앱을 열어 마이크 버튼을 눌러 권한을 허용해 주세요.",
                        style = JarvisType.body(14.sp, color = JarvisColors.Warning),
                    )
                    ui.isListening -> Text(
                        ui.partialTranscript?.takeIf { it.isNotBlank() } ?: "말씀하세요…",
                        style = JarvisType.body(20.sp, FontWeight.Medium,
                            if (ui.partialTranscript.isNullOrBlank()) JarvisColors.TextSecondary else JarvisColors.TextPrimary),
                    )
                    else -> {
                        lastUser?.let { Text("“${it.text}”", style = JarvisType.body(15.sp, color = JarvisColors.TextSecondary)) }
                        lastReply?.let { reply ->
                            Text(
                                reply.text,
                                style = JarvisType.body(16.sp, color = if (reply.role == ConversationMessage.Role.SYSTEM) JarvisColors.Warning else JarvisColors.TextPrimary),
                            )
                            reply.searchSuggestionsHtml?.let { html -> com.jarvis.app.ui.main.components.SearchSuggestions(html) }
                            SourceLinks(reply.sources, reply.sourceProvider)
                        }
                    }
                }
            }

            val active = ui.isListening || ui.isSpeaking || ui.voiceSessionActive
            IconButton(
                onClick = onMic,
                enabled = micPermission,
                modifier = Modifier
                    .size(64.dp)
                    .background(if (active) JarvisColors.Failure else JarvisColors.Cyan, CircleShape),
            ) {
                Icon(if (active) Icons.Outlined.Stop else Icons.Outlined.Mic, if (active) "중지" else "다시 말하기", tint = Color.Black)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
