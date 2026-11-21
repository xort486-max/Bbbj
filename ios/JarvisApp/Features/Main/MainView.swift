import SwiftUI
import JarvisCore

/// JARVIS 메인 인터페이스.
///
/// 구성: 헤더(상태·마이크) / 엔진 상태·사용량 / 중앙 AI 코어 + 음성 파형 /
/// 현재 작업 / 대화 기록 / 빠른 기능 / 명령 입력.
struct MainView: View {
    @Bindable var model: MainViewModel
    @State private var isSettingsPresented = false

    var body: some View {
        ZStack {
            HUDBackgroundView()

            VStack(spacing: 10) {
                StatusHeaderView(state: model.state, microphone: model.microphone) {
                    isSettingsPresented = true
                }

                EngineStatusStrip(
                    backend: model.backendStatus,
                    engines: model.engineStatuses,
                    activeEngine: model.state.activeEngine
                ) {
                    Task { await model.refreshEngineStatus() }
                }

                coreSection

                TaskStatusPanel(tasks: model.visibleTasks, state: model.state) {
                    model.cancelCurrent()
                }

                ConversationLogView(messages: model.log.messages)
                    .frame(minHeight: 120, maxHeight: .infinity)

                QuickActionsBar { feature in
                    model.selectFeature(feature)
                }

                CommandInputBar(
                    text: $model.inputText,
                    isMicrophoneAvailable: JarvisFeature.voice.isImplemented,
                    isBusy: model.state.isBusy,
                    onSubmit: { model.submitInput() },
                    onMicrophone: { model.microphoneTapped() }
                )
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 8)
        }
        .preferredColorScheme(.dark)
        .task {
            await model.refreshEngineStatus()
        }
        .sheet(isPresented: $isSettingsPresented, onDismiss: {
            Task { await model.refreshEngineStatus() }
        }) {
            SettingsView(model: model)
        }
    }

    private var coreSection: some View {
        VStack(spacing: 6) {
            ArcReactorCoreView(state: model.state, audioLevel: model.audioLevel)
                .frame(maxWidth: 220, maxHeight: 220)
                .frame(maxWidth: .infinity)

            Text(model.state.displayName)
                .font(JarvisTheme.body(15, weight: .semibold))
                .foregroundStyle(JarvisTheme.color(for: model.state))
                .contentTransition(.opacity)
                .animation(.easeInOut(duration: 0.2), value: model.state)

            if let error = model.state.errorMessage {
                Text(error)
                    .font(JarvisTheme.body(12))
                    .foregroundStyle(JarvisTheme.failure)
                    .multilineTextAlignment(.center)
            }

            WaveformView(state: model.state, level: model.audioLevel)
                .frame(height: 36)
                .padding(.horizontal, 24)
        }
        .layoutPriority(1)
    }
}

#if DEBUG
#Preview("Main · 대기") {
    MainView(model: PreviewData.model(state: .idle))
}

#Preview("Main · Claude 추론") {
    MainView(model: PreviewData.model(state: .claudeReasoning, seed: true))
}

#Preview("Main · 오류") {
    MainView(model: PreviewData.model(state: .error(message: "백엔드에 연결할 수 없습니다"), seed: true))
}
#endif
