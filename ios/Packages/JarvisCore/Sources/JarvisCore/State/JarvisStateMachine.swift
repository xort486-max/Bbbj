import Foundation

/// JARVIS 상태 전이를 검증하는 상태 머신.
///
/// 허용되지 않은 전이(예: 대기 중 → Claude 추론 중)를 막아 UI 표시와 실제 처리 흐름이
/// 어긋나지 않도록 한다. 모든 상태에서 `.error`와 `.idle`(중단)로는 항상 이동할 수 있다.
public struct JarvisStateMachine: Equatable, Sendable {
    public struct Transition: Equatable, Sendable {
        public let from: JarvisState
        public let to: JarvisState
        public let at: Date
    }

    public enum TransitionError: Error, Equatable, LocalizedError {
        case notAllowed(from: JarvisState.Kind, to: JarvisState.Kind)

        public var errorDescription: String? {
            switch self {
            case let .notAllowed(from, to):
                "허용되지 않은 상태 전이: \(from.rawValue) → \(to.rawValue)"
            }
        }
    }

    public private(set) var state: JarvisState
    /// 최근 전이 기록 (디버깅·실행 기록용).
    public private(set) var history: [Transition] = []
    public let maxHistory: Int

    public init(initial: JarvisState = .idle, maxHistory: Int = 50) {
        self.state = initial
        self.maxHistory = maxHistory
    }

    /// 상태별 허용 전이표. `.error`와 `.idle`은 `canTransition`에서 공통으로 허용한다.
    private static let allowed: [JarvisState.Kind: Set<JarvisState.Kind>] = [
        .idle: [.listening, .analyzing],
        .listening: [.analyzing],
        .analyzing: [.nvidiaResponding, .googleSearching, .claudeReasoning, .executingTask, .completed],
        .nvidiaResponding: [.completed],
        // 검색 결과 분석은 Claude, 간단 요약은 NVIDIA가 담당할 수 있다.
        .googleSearching: [.claudeReasoning, .nvidiaResponding, .completed],
        // Claude Tool Use: 추론 → 검색/작업 실행 → 다시 추론 반복.
        .claudeReasoning: [.googleSearching, .executingTask, .completed],
        .executingTask: [.claudeReasoning, .completed],
        .completed: [.listening, .analyzing],
        .error: [.listening, .analyzing],
    ]

    public static func canTransition(from: JarvisState, to: JarvisState) -> Bool {
        let target = to.kind
        if target == .error || target == .idle { return true }
        return allowed[from.kind, default: []].contains(target)
    }

    /// 상태를 전이한다. 같은 상태로의 전이는 무시한다(오류 메시지 변경은 반영).
    public mutating func transition(to next: JarvisState, at date: Date = Date()) throws {
        if next == state { return }
        if next.kind == state.kind, next.kind == .error {
            record(next, at: date)
            return
        }
        guard Self.canTransition(from: state, to: next) else {
            throw TransitionError.notAllowed(from: state.kind, to: next.kind)
        }
        record(next, at: date)
    }

    /// 진행 중인 처리를 중단하고 대기 상태로 돌아간다.
    public mutating func reset(at date: Date = Date()) {
        guard state != .idle else { return }
        record(.idle, at: date)
    }

    /// 검증 없이 상태를 지정한다. SwiftUI 미리보기와 디자인 점검(DEBUG)에서만 사용한다.
    public mutating func forceState(_ next: JarvisState, at date: Date = Date()) {
        record(next, at: date)
    }

    private mutating func record(_ next: JarvisState, at date: Date) {
        history.append(Transition(from: state, to: next, at: date))
        if history.count > maxHistory {
            history.removeFirst(history.count - maxHistory)
        }
        state = next
    }
}
