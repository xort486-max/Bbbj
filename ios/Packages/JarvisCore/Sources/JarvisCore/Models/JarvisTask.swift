import Foundation

/// 현재 실행 중이거나 완료된 작업 (화면의 "현재 작업 상태" 패널).
public struct JarvisTask: Identifiable, Equatable, Sendable {
    public enum Status: String, Sendable {
        case pending
        case running
        case succeeded
        case failed
        case cancelled

        public var displayName: String {
            switch self {
            case .pending: "대기"
            case .running: "진행 중"
            case .succeeded: "완료"
            case .failed: "실패"
            case .cancelled: "취소됨"
            }
        }

        public var isFinished: Bool {
            switch self {
            case .succeeded, .failed, .cancelled: true
            case .pending, .running: false
            }
        }
    }

    public let id: UUID
    public var title: String
    public var status: Status
    /// 0.0 ~ 1.0. 진행률을 알 수 없으면 nil (인디케이터만 표시).
    public var progress: Double?
    public var engine: AIEngineKind?
    public var detail: String?
    public let createdAt: Date
    public var finishedAt: Date?

    public init(
        id: UUID = UUID(),
        title: String,
        status: Status = .pending,
        progress: Double? = nil,
        engine: AIEngineKind? = nil,
        detail: String? = nil,
        createdAt: Date = Date()
    ) {
        self.id = id
        self.title = title
        self.status = status
        self.progress = progress.map { min(max($0, 0), 1) }
        self.engine = engine
        self.detail = detail
        self.createdAt = createdAt
    }
}

/// 작업 목록 관리. 완료된 작업은 최근 N개만 유지한다.
public struct TaskBoard: Equatable, Sendable {
    public private(set) var tasks: [JarvisTask] = []
    public let maxFinishedTasks: Int

    public init(maxFinishedTasks: Int = 20) {
        self.maxFinishedTasks = maxFinishedTasks
    }

    public var activeTasks: [JarvisTask] { tasks.filter { !$0.status.isFinished } }
    public var finishedTasks: [JarvisTask] { tasks.filter(\.status.isFinished) }

    @discardableResult
    public mutating func start(_ title: String, engine: AIEngineKind? = nil, now: Date = Date()) -> UUID {
        let task = JarvisTask(title: title, status: .running, engine: engine, createdAt: now)
        tasks.append(task)
        return task.id
    }

    public mutating func updateProgress(_ id: UUID, progress: Double?, detail: String? = nil) {
        guard let index = tasks.firstIndex(where: { $0.id == id }) else { return }
        tasks[index].progress = progress.map { min(max($0, 0), 1) }
        if let detail { tasks[index].detail = detail }
    }

    /// 작업을 종료 상태로 전환한다. 이미 종료된 작업은 변경하지 않는다.
    public mutating func finish(_ id: UUID, status: JarvisTask.Status, detail: String? = nil, now: Date = Date()) {
        precondition(status.isFinished, "finish(_:status:) requires a finished status")
        guard let index = tasks.firstIndex(where: { $0.id == id }),
              !tasks[index].status.isFinished else { return }
        tasks[index].status = status
        tasks[index].finishedAt = now
        if status == .succeeded { tasks[index].progress = 1 }
        if let detail { tasks[index].detail = detail }
        trimFinished()
    }

    /// 진행 중인 모든 작업을 취소 처리한다 (음성 대화 중단 등).
    public mutating func cancelAll(now: Date = Date()) {
        for task in activeTasks {
            finish(task.id, status: .cancelled, now: now)
        }
    }

    private mutating func trimFinished() {
        let finished = finishedTasks
        guard finished.count > maxFinishedTasks else { return }
        let removeIDs = Set(
            finished
                .sorted { ($0.finishedAt ?? $0.createdAt) < ($1.finishedAt ?? $1.createdAt) }
                .prefix(finished.count - maxFinishedTasks)
                .map(\.id)
        )
        tasks.removeAll { removeIDs.contains($0.id) }
    }
}
