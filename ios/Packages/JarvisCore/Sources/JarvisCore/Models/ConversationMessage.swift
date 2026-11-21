import Foundation

/// 대화 기록의 한 항목.
public struct ConversationMessage: Identifiable, Equatable, Codable, Sendable {
    public enum Role: String, Codable, Sendable {
        /// 사용자 발화/입력
        case user
        /// AI 엔진의 응답
        case assistant
        /// JARVIS 시스템 안내 (엔진 미연결, 오류, 권한 안내 등)
        case system
    }

    public let id: UUID
    public let role: Role
    public var text: String
    /// 응답을 생성한 엔진. 사용자/시스템 메시지는 nil.
    public let engine: AIEngineKind?
    public let timestamp: Date

    public init(
        id: UUID = UUID(),
        role: Role,
        text: String,
        engine: AIEngineKind? = nil,
        timestamp: Date = Date()
    ) {
        self.id = id
        self.role = role
        self.text = text
        self.engine = engine
        self.timestamp = timestamp
    }
}

/// 화면에 표시되는 대화 기록. 메모리 사용을 제한하기 위해 최대 개수를 유지한다.
///
/// 오래된 메시지 요약(토큰 절약)은 PHASE 7에서 백엔드 요약 기능과 연결한다.
public struct ConversationLog: Equatable, Sendable {
    public private(set) var messages: [ConversationMessage] = []
    public let maxMessages: Int

    public init(maxMessages: Int = 200, messages: [ConversationMessage] = []) {
        precondition(maxMessages > 0, "maxMessages must be positive")
        self.maxMessages = maxMessages
        self.messages = Array(messages.suffix(maxMessages))
    }

    public var isEmpty: Bool { messages.isEmpty }
    public var last: ConversationMessage? { messages.last }

    @discardableResult
    public mutating func append(_ message: ConversationMessage) -> ConversationMessage {
        messages.append(message)
        if messages.count > maxMessages {
            messages.removeFirst(messages.count - maxMessages)
        }
        return message
    }

    /// 스트리밍 응답 등으로 기존 메시지 본문을 갱신한다. 존재하지 않으면 false.
    @discardableResult
    public mutating func updateText(id: UUID, text: String) -> Bool {
        guard let index = messages.firstIndex(where: { $0.id == id }) else { return false }
        messages[index].text = text
        return true
    }

    public mutating func removeAll() {
        messages.removeAll()
    }
}
