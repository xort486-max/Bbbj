import Foundation
import Observation
import JarvisCore

/// 메인 화면 상태와 사용자 동작 처리.
///
/// PHASE 1 범위: 상태 머신, 대화 기록, 작업 표시, 백엔드 연결 상태 확인.
/// AI 응답(NVIDIA/Google/Claude)은 PHASE 2에서 `CommandRouter`를 통해 연결한다.
@MainActor
@Observable
final class MainViewModel {
    // MARK: 화면 상태
    private(set) var stateMachine = JarvisStateMachine()
    private(set) var log = ConversationLog()
    private(set) var taskBoard = TaskBoard()
    private(set) var backendStatus: EngineConnectionStatus = .unknown
    private(set) var engineStatuses: [AIEngineKind: EngineConnectionStatus] =
        Dictionary(uniqueKeysWithValues: AIEngineKind.remoteEngines.map { ($0, .unknown) })
    /// PHASE 3에서 음성 엔진과 연결된다.
    private(set) var microphone: MicrophoneStatus = .unavailable
    /// 0...1 — PHASE 3에서 마이크/TTS 레벨과 연결된다.
    private(set) var audioLevel: Double = 0
    var inputText = ""

    var state: JarvisState { stateMachine.state }

    /// 화면에 표시할 작업: 진행 중 작업 + 최근 종료 작업 1개.
    var visibleTasks: [JarvisTask] {
        let active = taskBoard.activeTasks
        if !active.isEmpty { return active }
        return taskBoard.finishedTasks.suffix(1)
    }

    // MARK: 의존성
    private let defaults: UserDefaults
    private let tokenProvider: AuthTokenProviding
    private let transport: HTTPTransport
    private var completionResetTask: Task<Void, Never>?

    init(
        defaults: UserDefaults = .standard,
        tokenProvider: AuthTokenProviding = KeychainTokenStore(),
        transport: HTTPTransport = URLSessionTransport()
    ) {
        self.defaults = defaults
        self.tokenProvider = tokenProvider
        self.transport = transport
    }

    // MARK: 백엔드 상태

    /// 백엔드 `/health`와 `/v1/engines`를 호출해 연결 상태를 갱신한다.
    func refreshEngineStatus() async {
        let configuration: BackendConfiguration
        do {
            configuration = try BackendConfiguration.validated(defaults.jarvisBackendURL)
        } catch BackendConfiguration.ValidationError.empty {
            backendStatus = .notConfigured
            setAllEngines(.unknown)
            return
        } catch {
            backendStatus = .unreachable(error.localizedDescription)
            setAllEngines(.unknown)
            return
        }

        let client = BackendClient(configuration: configuration, transport: transport, tokenProvider: tokenProvider)
        backendStatus = .checking
        setAllEngines(.checking)
        let taskID = taskBoard.start("백엔드 연결 확인", engine: nil)

        do {
            let health = try await client.health()
            guard health.isOK else {
                throw BackendError.http(status: 200, message: "status=\(health.status)")
            }
            backendStatus = .connected
        } catch {
            backendStatus = .unreachable(error.localizedDescription)
            setAllEngines(.unknown)
            taskBoard.finish(taskID, status: .failed, detail: error.localizedDescription)
            return
        }

        do {
            let engines = try await client.engines()
            engineStatuses = EngineStatusMapper.statuses(from: engines)
            taskBoard.finish(taskID, status: .succeeded, detail: "백엔드 연결됨 · 엔진 설정 확인 완료")
        } catch {
            setAllEngines(.unknown)
            taskBoard.finish(taskID, status: .failed, detail: error.localizedDescription)
            log.append(ConversationMessage(role: .system, text: error.localizedDescription))
        }
    }

    // MARK: 사용자 명령

    func submitInput() {
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !state.isBusy else { return }
        inputText = ""
        completionResetTask?.cancel()

        log.append(ConversationMessage(role: .user, text: text))
        move(to: .analyzing)

        // PHASE 2에서 로컬 분류기 → NVIDIA / Google / Claude / 로컬 엔진 라우팅으로 대체된다.
        // 연결되지 않은 엔진의 응답을 흉내 내지 않고, 현재 상태를 그대로 안내한다.
        log.append(ConversationMessage(
            role: .system,
            text: "명령을 수신했습니다. AI 엔진 라우팅(NVIDIA 대화 · Google 검색 · Claude 에이전트)은 PHASE 2에서 연결됩니다. 현재 버전은 인터페이스와 백엔드 상태 확인만 동작합니다."
        ))
        move(to: .completed)
        scheduleReturnToIdle()
    }

    func selectFeature(_ feature: JarvisFeature) {
        guard !feature.isImplemented else { return }
        log.append(ConversationMessage(
            role: .system,
            text: "\(feature.title) 기능은 \(feature.availabilityLabel)입니다."
        ))
    }

    func microphoneTapped() {
        // PHASE 3: Speech 프레임워크 권한 요청 및 음성 인식 시작.
        log.append(ConversationMessage(role: .system, text: "음성 인식은 PHASE 3에서 구현됩니다."))
    }

    /// 진행 중인 처리를 중단한다.
    func cancelCurrent() {
        completionResetTask?.cancel()
        taskBoard.cancelAll()
        stateMachine.reset()
    }

    func clearConversation() {
        log.removeAll()
    }

    // MARK: 내부

    private func move(to next: JarvisState) {
        do {
            try stateMachine.transition(to: next)
        } catch {
            // 잘못된 전이는 개발 중 버그이므로 기록하고 오류 상태로 표시한다.
            assertionFailure(error.localizedDescription)
            stateMachine.forceState(.error(message: error.localizedDescription))
        }
    }

    private func scheduleReturnToIdle() {
        completionResetTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard !Task.isCancelled, let self, self.state == .completed else { return }
            self.stateMachine.reset()
        }
    }

    private func setAllEngines(_ status: EngineConnectionStatus) {
        for engine in AIEngineKind.remoteEngines {
            engineStatuses[engine] = status
        }
    }

    #if DEBUG
    /// 디자인 점검용: 상태를 강제로 지정한다 (설정 > 디자인 점검, SwiftUI 미리보기).
    func debugForceState(_ next: JarvisState) {
        completionResetTask?.cancel()
        stateMachine.forceState(next)
        audioLevel = next == .listening ? 0.6 : 0
    }

    func debugSeedConversation() {
        log.append(ConversationMessage(role: .user, text: "자비스, 오늘 일정 알려줘."))
        log.append(ConversationMessage(role: .assistant, text: "[디자인 미리보기] 실제 응답이 아닌 화면 점검용 예시 메시지입니다.", engine: .nvidia))
        log.append(ConversationMessage(role: .system, text: "캘린더 접근은 PHASE 4에서 구현됩니다."))
    }
    #endif
}
