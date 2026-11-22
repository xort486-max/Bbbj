package com.jarvis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.app.assistant.MainUiState
import com.jarvis.app.voice.HotwordModelManager
import com.jarvis.app.voice.HotwordModelStatus
import com.jarvis.core.voice.VoiceSettings
import androidx.compose.ui.graphics.Color
import com.jarvis.app.ui.main.components.HudSectionTitle
import com.jarvis.app.ui.theme.JarvisColors
import com.jarvis.app.ui.theme.JarvisType
import com.jarvis.app.ui.theme.hudPanel
import com.jarvis.core.model.AiEngine
import com.jarvis.core.model.ConnectionStatus
import com.jarvis.core.network.BackendConfiguration
import com.jarvis.core.state.JarvisState

data class SettingsActions(
    val onBack: () -> Unit = {},
    val onSaveUrl: (String) -> Unit = {},
    val onSaveToken: (String) -> Result<Unit> = { Result.success(Unit) },
    val onDeleteToken: () -> Result<Unit> = { Result.success(Unit) },
    val onTestConnection: () -> Unit = {},
    /** 디버그 빌드에서만 non-null — 디자인 점검 도구. */
    val onDebugState: ((JarvisState) -> Unit)? = null,
    val onDebugSeed: (() -> Unit)? = null,
    val onClearConversation: () -> Unit = {},
    // --- 음성 ---
    val onVoiceSettings: ((VoiceSettings) -> VoiceSettings) -> Unit = {},
    val onWakeWordToggle: (Boolean) -> Unit = {},
    val onDownloadHotwordModel: () -> Unit = {},
    val onDeleteHotwordModel: () -> Unit = {},
    val onOpenBatterySettings: () -> Unit = {},
)

/** 앱 설정: 백엔드 주소, 앱 인증 토큰(Keystore 암호화), 디자인 점검(디버그 빌드). */
@Composable
fun SettingsScreen(
    ui: MainUiState,
    savedUrl: String,
    versionName: String,
    actions: SettingsActions,
    hotwordStatus: HotwordModelStatus = HotwordModelStatus.NotDownloaded,
) {
    var url by rememberSaveable(savedUrl) { mutableStateOf(savedUrl) }
    val token = rememberTextFieldState()
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // (문구, 오류 여부)

    val urlError = remember(url) {
        if (url.isBlank()) null else runCatching { BackendConfiguration.validated(url) }.exceptionOrNull()?.message
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = JarvisColors.Cyan,
        unfocusedBorderColor = JarvisColors.PanelBorder,
        focusedTextColor = JarvisColors.TextPrimary,
        unfocusedTextColor = JarvisColors.TextPrimary,
        cursorColor = JarvisColors.Cyan,
        focusedLabelColor = JarvisColors.Cyan,
        unfocusedLabelColor = JarvisColors.TextSecondary,
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(JarvisColors.backgroundBrush)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "뒤로", tint = JarvisColors.TextPrimary)
            }
            Text("설정", style = JarvisType.body(18.sp, FontWeight.SemiBold))
        }

        Section("// BACKEND SERVER") {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("백엔드 주소") },
                placeholder = { Text("https://jarvis.example.com") },
                singleLine = true,
                isError = urlError != null,
                supportingText = urlError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { actions.onSaveUrl(url); message = "주소를 저장했습니다." to false }, enabled = urlError == null) {
                Text("주소 저장")
            }
            Hint(
                "AI API Key는 앱에 저장하지 않습니다. 앱은 이 서버를 통해서만 NVIDIA · Google에 접근합니다.\n" +
                    "에뮬레이터 → PC: http://10.0.2.2:8000 / 실기기 → 같은 Wi-Fi의 PC: http://<PC IP>:8000 (디버그 빌드만 HTTP 허용)",
            )
        }

        Section("// APP TOKEN") {
            OutlinedSecureTextField(
                state = token,
                label = { Text(if (ui.hasToken) "저장됨 — 변경하려면 새 토큰 입력" else "앱 인증 토큰") },
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    message = actions.onSaveToken(token.text.toString()).fold(
                        onSuccess = { token.clearText(); "토큰을 암호화해 저장했습니다." to false },
                        onFailure = { (it.message ?: "저장 실패") to true },
                    )
                }, enabled = token.text.isNotBlank()) { Text("토큰 저장") }
                OutlinedButton(onClick = {
                    message = actions.onDeleteToken().fold(
                        onSuccess = { "토큰을 삭제했습니다." to false },
                        onFailure = { (it.message ?: "삭제 실패") to true },
                    )
                }, enabled = ui.hasToken) { Text("토큰 삭제", color = JarvisColors.Failure) }
            }
            Hint("백엔드의 JARVIS_APP_TOKEN 값과 같아야 합니다. Android Keystore 키로 암호화해 이 기기에만 저장합니다.")
        }

        VoiceSection(ui, hotwordStatus, actions)

        Section("// CONNECTION") {
            Button(onClick = actions.onTestConnection) { Text("연결 테스트") }
            StatusLine("백엔드", ui.backend)
            AiEngine.remoteEngines.forEach { engine ->
                StatusLine("${engine.displayName} · ${engine.roleDescription}", ui.engines[engine] ?: ConnectionStatus.Unknown)
            }
            (ui.backend as? ConnectionStatus.Unreachable)?.let {
                Text(it.reason, style = JarvisType.body(12.sp, color = JarvisColors.Failure))
            }
        }

        message?.let { (text, isError) ->
            Text(text, style = JarvisType.body(13.sp, color = if (isError) JarvisColors.Failure else JarvisColors.Ok))
        }

        if (actions.onDebugState != null) {
            Section("// DESIGN CHECK (DEBUG 빌드 전용)") {
                Hint("화면 상태 표시를 확인하기 위한 도구입니다. 실제 AI 처리가 아닙니다.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    JarvisState.all.forEach { state ->
                        TextButton(onClick = { actions.onDebugState.invoke(state) }) {
                            Text(state.displayName, color = JarvisColors.forState(state), fontSize = 12.sp)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.onDebugSeed?.let { seed -> OutlinedButton(onClick = seed) { Text("예시 대화 추가") } }
                    OutlinedButton(onClick = actions.onClearConversation) { Text("대화 지우기") }
                }
            }
        }

        Section("// ABOUT") {
            StatusText("버전", versionName)
            StatusText("개발 단계", "PHASE 3 — 음성")
        }
        Spacer(Modifier.padding(8.dp))
    }
}

@Composable
private fun VoiceSection(ui: MainUiState, hotwordStatus: HotwordModelStatus, actions: SettingsActions) {
    val voice = ui.voiceSettings
    Section("// VOICE") {
        if (!ui.voiceAvailable) {
            Text("이 기기에서 음성 인식을 사용할 수 없습니다. Google 앱이 설치·활성화되어 있는지 확인하세요.",
                style = JarvisType.body(12.sp, color = JarvisColors.Warning))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("인식 언어", style = JarvisType.body(13.sp), modifier = Modifier.weight(1f))
            listOf("ko-KR" to "한국어", "en-US" to "English").forEach { (tag, label) ->
                TextButton(onClick = { actions.onVoiceSettings { it.copy(language = tag) } }) {
                    Text(label, color = if (voice.language == tag) JarvisColors.Cyan else JarvisColors.TextDim,
                        fontWeight = if (voice.language == tag) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        ToggleRow("답변 소리 내어 읽기", "음성으로 물으면 답을 읽어 줍니다", voice.speakReplies) { on ->
            actions.onVoiceSettings { it.copy(speakReplies = on) }
        }
        ToggleRow("음성 대화 모드", "답변을 읽은 뒤 자동으로 다시 듣습니다", voice.continuousConversation) { on ->
            actions.onVoiceSettings { it.copy(continuousConversation = on) }
        }
        SliderRow("말하기 속도", voice.speechRate, 0.5f..2.0f, { "%.1f배".format(it) }) { v ->
            actions.onVoiceSettings { it.copy(speechRate = v) }
        }
        SliderRow("음량", voice.volume, 0f..1f, { "${(it * 100).toInt()}%" }) { v ->
            actions.onVoiceSettings { it.copy(volume = v) }
        }
    }

    Section("// \"자비스\" 호출 (앱이 꺼져 있어도)") {
        Hint(
            "\"자비스\"라고 부르면 앱이 꺼져 있어도 응답합니다. 호출어는 인터넷 없이 기기 안에서만 인식하며 녹음을 저장하거나 보내지 않습니다. " +
                "대기 중에는 Android 규칙에 따라 상단에 알림이 계속 표시되고 마이크 표시등이 켜지며, 배터리를 더 사용합니다.",
        )
        when (hotwordStatus) {
            HotwordModelStatus.NotDownloaded, is HotwordModelStatus.Failed -> {
                (hotwordStatus as? HotwordModelStatus.Failed)?.let {
                    Text("다운로드 실패: ${it.message}", style = JarvisType.body(12.sp, color = JarvisColors.Failure))
                }
                Hint("먼저 오프라인 호출어 모델을 받아야 합니다 (${HotwordModelManager.SIZE_LABEL}, Wi-Fi 권장).")
                Button(onClick = actions.onDownloadHotwordModel) { Text("호출어 모델 다운로드") }
            }
            is HotwordModelStatus.Downloading -> {
                Text("모델 다운로드 중… ${(hotwordStatus.progress * 100).toInt()}%", style = JarvisType.body(13.sp))
                LinearProgressIndicator(progress = { hotwordStatus.progress }, modifier = Modifier.fillMaxWidth(), color = JarvisColors.Cyan)
            }
            HotwordModelStatus.Ready -> {
                ToggleRow(
                    "\"자비스\" 상시 대기",
                    if (ui.wakeServiceRunning) "대기 중 — 알림에서도 끌 수 있습니다" else "꺼짐",
                    ui.voiceSettings.wakeWordEnabled,
                ) { on -> actions.onWakeWordToggle(on) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = actions.onOpenBatterySettings) { Text("배터리 최적화 설정") }
                    TextButton(onClick = actions.onDeleteHotwordModel) { Text("모델 삭제", color = JarvisColors.TextDim) }
                }
                Hint("삼성 등 일부 기기는 배터리 절약을 위해 백그라운드 앱을 멈춥니다. 대기가 자주 꺼지면 JARVIS 배터리 사용을 \"제한 없음\"으로 바꾸세요. 휴대폰을 다시 켠 뒤에는 앱을 한 번 열어야 대기가 다시 시작됩니다.")
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = JarvisType.body(14.sp))
            Text(subtitle, style = JarvisType.body(11.sp, color = JarvisColors.TextDim))
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = JarvisColors.Cyan),
        )
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    val state = remember(range) { SliderState(value, 0, range) }
    LaunchedEffect(value) { state.value = value }
    Column {
        Row {
            Text(title, style = JarvisType.body(13.sp), modifier = Modifier.weight(1f))
            Text(label(state.value), style = JarvisType.hud(12.sp, color = JarvisColors.Cyan))
        }
        Slider(
            state = state,
            onValueChange = { state.value = it },
            onValueChangeFinished = { onChange(state.value) },
            colors = SliderDefaults.colors(thumbColor = JarvisColors.Cyan, activeTrackColor = JarvisColors.Cyan),
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().hudPanel(padding = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HudSectionTitle(title)
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = JarvisType.body(12.sp, color = JarvisColors.TextDim))
}

@Composable
private fun StatusLine(label: String, status: ConnectionStatus) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = JarvisType.body(13.sp), modifier = Modifier.weight(1f))
        Text(status.displayName, style = JarvisType.hud(12.sp, FontWeight.SemiBold, JarvisColors.forLevel(status.level)))
    }
}

@Composable
private fun StatusText(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = JarvisType.body(13.sp), modifier = Modifier.weight(1f))
        Text(value, style = JarvisType.body(13.sp, color = JarvisColors.TextSecondary))
    }
}
