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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.jarvis.app.ui.main.MainUiState
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
)

/** 앱 설정: 백엔드 주소, 앱 인증 토큰(Keystore 암호화), 디자인 점검(디버그 빌드). */
@Composable
fun SettingsScreen(ui: MainUiState, savedUrl: String, versionName: String, actions: SettingsActions) {
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
            StatusText("개발 단계", "PHASE 2 — AI 엔진 연결")
        }
        Spacer(Modifier.padding(8.dp))
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
