#if DEBUG
import Foundation
import JarvisCore

/// SwiftUI 미리보기 및 디자인 점검용 데이터 (DEBUG 빌드 전용).
enum PreviewData {
    static let allStates: [JarvisState] = [
        .idle, .listening, .analyzing, .nvidiaResponding, .googleSearching,
        .claudeReasoning, .executingTask, .completed, .error(message: "예시 오류"),
    ]

    @MainActor
    static func model(state: JarvisState, seed: Bool = false) -> MainViewModel {
        let model = MainViewModel(
            defaults: UserDefaults(suiteName: "jarvis.preview") ?? .standard,
            tokenProvider: StaticTokenProvider(nil)
        )
        model.debugForceState(state)
        if seed { model.debugSeedConversation() }
        return model
    }
}
#endif
