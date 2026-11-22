package com.jarvis.app.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jarvis.app.AppContainer
import com.jarvis.app.assistant.JarvisController

/**
 * 화면용 ViewModel. 실제 상태와 로직은 앱 전역 [JarvisController]에 있다
 * (백그라운드 호출어 서비스와 같은 대화를 공유하기 위해).
 */
class MainViewModel(val controller: JarvisController) : ViewModel() {
    val ui = controller.ui
    val backendUrl = controller.backendUrl

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(container.controller) }
        }
    }
}
