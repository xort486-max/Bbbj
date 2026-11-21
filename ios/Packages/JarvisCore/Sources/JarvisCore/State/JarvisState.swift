import Foundation

/// JARVIS 작동 상태. 중앙 AI 코어와 상태 표시줄이 이 값을 표시한다.
public enum JarvisState: Equatable, Sendable {
    /// 대기 중
    case idle
    /// 음성 인식 중
    case listening
    /// 사용자 명령 분석 중 (로컬 분류기)
    case analyzing
    /// NVIDIA 응답 중
    case nvidiaResponding
    /// Google 검색 중
    case googleSearching
    /// Claude 추론 중
    case claudeReasoning
    /// 작업 실행 중 (로컬 명령, 파일, 일정 등)
    case executingTask
    /// 작업 완료
    case completed
    /// 오류 발생
    case error(message: String)

    /// 전이 규칙 비교용 판별자 (오류 메시지와 무관).
    public enum Kind: String, CaseIterable, Sendable {
        case idle, listening, analyzing, nvidiaResponding, googleSearching
        case claudeReasoning, executingTask, completed, error
    }

    public var kind: Kind {
        switch self {
        case .idle: .idle
        case .listening: .listening
        case .analyzing: .analyzing
        case .nvidiaResponding: .nvidiaResponding
        case .googleSearching: .googleSearching
        case .claudeReasoning: .claudeReasoning
        case .executingTask: .executingTask
        case .completed: .completed
        case .error: .error
        }
    }

    /// 한국어 상태명.
    public var displayName: String {
        switch self {
        case .idle: "대기 중"
        case .listening: "음성 인식 중"
        case .analyzing: "사용자 명령 분석 중"
        case .nvidiaResponding: "NVIDIA 응답 중"
        case .googleSearching: "Google 검색 중"
        case .claudeReasoning: "Claude 추론 중"
        case .executingTask: "작업 실행 중"
        case .completed: "작업 완료"
        case .error: "오류 발생"
        }
    }

    /// HUD용 영문 코드.
    public var hudCode: String {
        switch self {
        case .idle: "STANDBY"
        case .listening: "LISTENING"
        case .analyzing: "ANALYZING"
        case .nvidiaResponding: "NVIDIA · RESPONDING"
        case .googleSearching: "GOOGLE · SEARCHING"
        case .claudeReasoning: "CLAUDE · REASONING"
        case .executingTask: "EXECUTING"
        case .completed: "COMPLETE"
        case .error: "ERROR"
        }
    }

    /// 현재 상태에서 작동 중인 엔진.
    public var activeEngine: AIEngineKind? {
        switch self {
        case .nvidiaResponding: .nvidia
        case .googleSearching: .google
        case .claudeReasoning: .claude
        case .analyzing, .executingTask: .local
        case .idle, .listening, .completed, .error: nil
        }
    }

    /// 사용자가 중단할 수 있는 처리 중 상태인지.
    public var isBusy: Bool {
        switch self {
        case .listening, .analyzing, .nvidiaResponding, .googleSearching, .claudeReasoning, .executingTask:
            true
        case .idle, .completed, .error:
            false
        }
    }

    public var errorMessage: String? {
        if case let .error(message) = self { return message }
        return nil
    }
}
