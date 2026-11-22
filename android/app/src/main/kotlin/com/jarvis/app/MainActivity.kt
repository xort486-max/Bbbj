package com.jarvis.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.app.ui.main.MainActions
import com.jarvis.app.ui.main.MainScreen
import com.jarvis.app.ui.main.MainViewModel
import com.jarvis.app.ui.settings.SettingsActions
import com.jarvis.app.ui.settings.SettingsScreen
import com.jarvis.app.ui.theme.JarvisTheme
import com.jarvis.app.voice.JarvisVoiceService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val container = (application as JarvisApplication).container

        setContent {
            JarvisTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val vm: MainViewModel = viewModel(factory = MainViewModel.factory(container))
                val controller = vm.controller
                val ui by vm.ui.collectAsStateWithLifecycle()
                val savedUrl by vm.backendUrl.collectAsStateWithLifecycle()
                val hotwordStatus by container.hotwordModels.status.collectAsStateWithLifecycle()
                var showSettings by rememberSaveable { mutableStateOf(false) }

                // --- 권한 ---
                var afterMicGranted by remember { mutableStateOf<(() -> Unit)?>(null) }
                val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    controller.setMicPermission(granted)
                    if (granted) afterMicGranted?.invoke()
                    afterMicGranted = null
                }
                val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    // 거부해도 서비스는 동작한다 (알림이 알림창에 보이지 않을 뿐). 마이크 표시등은 항상 켜진다.
                    JarvisVoiceService.start(context)
                }

                fun withMic(action: () -> Unit) {
                    if (context.hasPermission(Manifest.permission.RECORD_AUDIO)) {
                        action()
                    } else {
                        afterMicGranted = action
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }

                fun startWakeService() {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        JarvisVoiceService.start(context)
                    }
                }

                fun setWakeWord(enabled: Boolean) {
                    if (enabled) {
                        withMic {
                            controller.updateVoiceSettings { it.copy(wakeWordEnabled = true) }
                            startWakeService()
                        }
                    } else {
                        controller.updateVoiceSettings { it.copy(wakeWordEnabled = false) }
                        JarvisVoiceService.stop(context)
                    }
                }

                // 앱이 화면에 보일 때: 권한 상태 갱신 + (켜 둔 경우) 호출어 대기 서비스 다시 시작.
                // Android 14+는 화면이 보일 때만 마이크 서비스를 시작할 수 있다.
                LifecycleEventEffect(Lifecycle.Event.ON_START) {
                    val granted = context.hasPermission(Manifest.permission.RECORD_AUDIO)
                    controller.setMicPermission(granted)
                    if (granted && ui.voiceSettings.wakeWordEnabled && container.hotwordModels.isReady && !ui.wakeServiceRunning) {
                        JarvisVoiceService.start(context)
                    }
                }
                LaunchedEffect(ui.voiceSettings.wakeWordEnabled) {
                    val granted = context.hasPermission(Manifest.permission.RECORD_AUDIO)
                    if (granted && ui.voiceSettings.wakeWordEnabled && container.hotwordModels.isReady && !ui.wakeServiceRunning) {
                        JarvisVoiceService.start(context)
                    }
                }
                LaunchedEffect(Unit) { controller.refreshEngineStatus() }

                if (showSettings) {
                    BackHandler {
                        showSettings = false
                        controller.refreshEngineStatus()
                    }
                    SettingsScreen(
                        ui = ui,
                        savedUrl = savedUrl,
                        versionName = BuildConfig.VERSION_NAME,
                        hotwordStatus = hotwordStatus,
                        actions = SettingsActions(
                            onBack = {
                                showSettings = false
                                controller.refreshEngineStatus()
                            },
                            onSaveUrl = controller::setBackendUrl,
                            onSaveToken = controller::saveToken,
                            onDeleteToken = controller::deleteToken,
                            onTestConnection = { controller.refreshEngineStatus() },
                            onDebugState = if (BuildConfig.DEBUG) controller::debugForceState else null,
                            onDebugSeed = if (BuildConfig.DEBUG) controller::debugSeedConversation else null,
                            onClearConversation = controller::clearConversation,
                            onVoiceSettings = controller::updateVoiceSettings,
                            onWakeWordToggle = ::setWakeWord,
                            onDownloadHotwordModel = { scope.launch { container.hotwordModels.download() } },
                            onDeleteHotwordModel = {
                                setWakeWord(false)
                                container.hotwordModels.delete()
                            },
                            onOpenBatterySettings = {
                                runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                            },
                        ),
                    )
                } else {
                    MainScreen(
                        ui = ui,
                        actions = MainActions(
                            onSettings = { showSettings = true },
                            onRefresh = { controller.refreshEngineStatus() },
                            onCancel = controller::cancelCurrent,
                            onSubmit = controller::submit,
                            onFeature = { feature ->
                                if (feature == com.jarvis.core.model.JarvisFeature.VOICE) {
                                    withMic { controller.microphoneTapped() }
                                } else {
                                    controller.selectFeature(feature)
                                }
                            },
                            onMicrophone = {
                                if (ui.isListening || ui.isSpeaking || ui.voiceSessionActive) {
                                    controller.microphoneTapped()
                                } else {
                                    withMic { controller.microphoneTapped() }
                                }
                            },
                        ),
                    )
                }
            }
        }
    }
}

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
