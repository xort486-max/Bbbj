package com.jarvis.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.app.ui.main.MainActions
import com.jarvis.app.ui.main.MainScreen
import com.jarvis.app.ui.main.MainViewModel
import com.jarvis.app.ui.settings.SettingsActions
import com.jarvis.app.ui.settings.SettingsScreen
import com.jarvis.app.ui.theme.JarvisTheme

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
                val vm: MainViewModel = viewModel(factory = MainViewModel.factory(container))
                val ui by vm.ui.collectAsStateWithLifecycle()
                val savedUrl by vm.backendUrl.collectAsStateWithLifecycle()
                var showSettings by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(Unit) { vm.refreshEngineStatus() }

                if (showSettings) {
                    BackHandler {
                        showSettings = false
                        vm.refreshEngineStatus()
                    }
                    SettingsScreen(
                        ui = ui,
                        savedUrl = savedUrl,
                        versionName = BuildConfig.VERSION_NAME,
                        actions = SettingsActions(
                            onBack = {
                                showSettings = false
                                vm.refreshEngineStatus()
                            },
                            onSaveUrl = vm::setBackendUrl,
                            onSaveToken = vm::saveToken,
                            onDeleteToken = vm::deleteToken,
                            onTestConnection = { vm.refreshEngineStatus() },
                            onDebugState = if (BuildConfig.DEBUG) vm::debugForceState else null,
                            onDebugSeed = if (BuildConfig.DEBUG) vm::debugSeedConversation else null,
                            onClearConversation = vm::clearConversation,
                        ),
                    )
                } else {
                    MainScreen(
                        ui = ui,
                        actions = MainActions(
                            onSettings = { showSettings = true },
                            onRefresh = { vm.refreshEngineStatus() },
                            onCancel = vm::cancelCurrent,
                            onSubmit = vm::submit,
                            onFeature = vm::selectFeature,
                            onMicrophone = vm::microphoneTapped,
                        ),
                    )
                }
            }
        }
    }
}
