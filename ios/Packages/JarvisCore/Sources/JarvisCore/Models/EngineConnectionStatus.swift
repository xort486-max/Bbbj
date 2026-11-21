import Foundation

/// 엔진/백엔드 연결 상태.
///
/// `configured`와 `connected`를 구분한다:
/// - `configured`: 백엔드에 API Key와 모델 설정이 존재함 (실제 호출 성공 여부는 아직 모름)
/// - `connected`: 실제 요청이 성공함 (예: 백엔드 `/health` 응답 성공)
/// 확인하지 않은 상태를 "연결됨"으로 표시하지 않기 위함이다.
public enum EngineConnectionStatus: Equatable, Sendable {
    case unknown
    case checking
    case notConfigured
    case configured
    case connected
    case unreachable(String)

    public var displayName: String {
        switch self {
        case .unknown: "확인 전"
        case .checking: "확인 중"
        case .notConfigured: "미설정"
        case .configured: "설정됨"
        case .connected: "연결됨"
        case .unreachable: "연결 실패"
        }
    }

    /// 상태 표시등에 사용하는 단계.
    public var level: Level {
        switch self {
        case .unknown, .checking: .neutral
        case .notConfigured: .warning
        case .configured, .connected: .ok
        case .unreachable: .failure
        }
    }

    public var detail: String? {
        if case let .unreachable(message) = self { return message }
        return nil
    }

    public enum Level: Sendable {
        case neutral, ok, warning, failure
    }
}
