package com.jarvis.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import com.github.takahirom.roborazzi.captureRoboImage
import com.jarvis.app.ui.main.MainActions
import com.jarvis.app.ui.main.MainScreen
import com.jarvis.app.ui.main.MainUiState
import com.jarvis.app.ui.settings.SettingsActions
import com.jarvis.app.ui.settings.SettingsScreen
import com.jarvis.app.ui.theme.JarvisTheme
import com.jarvis.core.model.AiEngine
import com.jarvis.core.model.ConnectionStatus
import com.jarvis.core.model.ConversationLog
import com.jarvis.core.model.ConversationMessage
import com.jarvis.core.model.TaskBoard
import com.jarvis.core.state.JarvisState
import com.jarvis.core.state.JarvisStateMachine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

/**
 * 화면 스냅샷 (Robolectric + Roborazzi). 기기 없이 실제 Compose 렌더링 결과를 PNG로 저장한다.
 * 기록: ./gradlew :app:recordRoborazziDebug  → app/screenshots/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val conversation = ConversationLog()
        .append(ConversationMessage(ConversationMessage.Role.USER, "자비스, 오늘 일정 알려줘."))
        .append(
            ConversationMessage(
                ConversationMessage.Role.ASSISTANT,
                "[디자인 미리보기] 실제 응답이 아닌 화면 점검용 예시 메시지입니다.",
                AiEngine.NVIDIA,
            ),
        )
        .append(ConversationMessage(ConversationMessage.Role.SYSTEM, "캘린더 접근은 PHASE 4에서 구현됩니다."))

    private fun render(ui: MainUiState, name: String) {
        compose.setContent { JarvisTheme { MainScreen(ui, MainActions()) } }
        compose.onNodeWithContentDescription("JARVIS 코어").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Test
    fun mainIdleNotConfigured() = render(
        MainUiState(
            backend = ConnectionStatus.NotConfigured,
            engines = AiEngine.remoteEngines.associateWith { ConnectionStatus.Unknown },
        ),
        "main_idle",
    )

    @Test
    fun mainClaudeReasoning() {
        val (tasks, _) = TaskBoard().start("검색 결과 분석", AiEngine.CLAUDE)
        render(
            MainUiState(
                machine = JarvisStateMachine().forced(JarvisState.ClaudeReasoning),
                log = conversation,
                tasks = tasks,
                backend = ConnectionStatus.Connected,
                engines = mapOf(
                    AiEngine.NVIDIA to ConnectionStatus.Configured,
                    AiEngine.GOOGLE to ConnectionStatus.NotConfigured,
                    AiEngine.CLAUDE to ConnectionStatus.Configured,
                ),
            ),
            "main_claude_reasoning",
        )
    }

    @Test
    fun mainError() = render(
        MainUiState(
            machine = JarvisStateMachine().forced(JarvisState.Error("백엔드에 연결할 수 없습니다")),
            log = conversation,
            backend = ConnectionStatus.Unreachable("백엔드에 연결할 수 없습니다: Failed to connect to /10.0.2.2:8000"),
        ),
        "main_error",
    )

    @Test
    fun settings() {
        compose.setContent {
            JarvisTheme {
                SettingsScreen(
                    ui = MainUiState(backend = ConnectionStatus.Connected, hasToken = true),
                    savedUrl = "http://10.0.2.2:8000",
                    versionName = "0.1.0",
                    actions = SettingsActions(onDebugState = {}, onDebugSeed = {}),
                )
            }
        }
        compose.onRoot().captureRoboImage("screenshots/settings.png")
    }
}
