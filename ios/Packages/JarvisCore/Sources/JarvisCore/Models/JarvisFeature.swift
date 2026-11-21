import Foundation

/// 빠른 기능 실행 메뉴에 노출되는 기능과 구현 상태.
///
/// 구현되지 않은 기능을 동작하는 것처럼 보이지 않도록, 각 기능은 구현 여부와
/// 예정 단계를 함께 가진다. 기능을 실제로 구현한 단계에서 `isImplemented`를 갱신한다.
public enum JarvisFeature: String, CaseIterable, Identifiable, Sendable {
    case voice
    case search
    case calendar
    case reminder
    case note
    case project
    case video

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .voice: "음성"
        case .search: "검색"
        case .calendar: "일정"
        case .reminder: "미리 알림"
        case .note: "메모"
        case .project: "프로젝트"
        case .video: "영상"
        }
    }

    /// SF Symbols 이름.
    public var symbolName: String {
        switch self {
        case .voice: "waveform"
        case .search: "magnifyingglass"
        case .calendar: "calendar"
        case .reminder: "checklist"
        case .note: "note.text"
        case .project: "folder.badge.gearshape"
        case .video: "film.stack"
        }
    }

    /// 구현 예정 단계 (PROJECT_STATUS.md의 PHASE 번호).
    public var plannedPhase: Int {
        switch self {
        case .search: 2
        case .voice: 3
        case .calendar, .reminder, .note: 4
        case .project: 5
        case .video: 6
        }
    }

    /// 실제로 동작하는 구현이 앱에 들어있는지. PHASE 1에서는 모두 false.
    public var isImplemented: Bool {
        switch self {
        case .voice, .search, .calendar, .reminder, .note, .project, .video:
            false
        }
    }

    public var availabilityLabel: String {
        isImplemented ? "사용 가능" : "PHASE \(plannedPhase) 예정"
    }
}
