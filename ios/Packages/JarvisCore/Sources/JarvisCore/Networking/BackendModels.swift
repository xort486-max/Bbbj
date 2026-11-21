import Foundation

/// `GET /health` 응답.
public struct HealthResponse: Decodable, Equatable, Sendable {
    public let status: String
    public let service: String
    public let version: String

    public var isOK: Bool { status == "ok" }
}

/// `GET /v1/engines` 응답 — 백엔드에 설정된 AI 엔진 정보 (API Key 값은 절대 포함하지 않음).
public struct EnginesResponse: Decodable, Equatable, Sendable {
    public let engines: [EngineInfo]
}

public struct EngineInfo: Decodable, Equatable, Sendable {
    /// "nvidia" | "google" | "claude"
    public let id: String
    /// API Key 및 필수 설정값이 백엔드에 존재하는지 (실제 호출 성공 여부와는 별개)
    public let configured: Bool
    public let provider: String
    public let model: String?
    /// 설정 누락 항목 등 안내 문구
    public let note: String?

    public var kind: AIEngineKind? { AIEngineKind(rawValue: id) }
}

/// 백엔드 오류 응답 본문 (FastAPI `{"detail": ...}` 형식).
struct ErrorBody: Decodable {
    let detail: String?
}

public enum EngineStatusMapper {
    /// 백엔드 응답을 엔진별 상태로 변환한다. 응답에 없는 원격 엔진은 `.notConfigured`.
    public static func statuses(from response: EnginesResponse) -> [AIEngineKind: EngineConnectionStatus] {
        var result: [AIEngineKind: EngineConnectionStatus] = [:]
        for engine in AIEngineKind.remoteEngines {
            result[engine] = .notConfigured
        }
        for info in response.engines {
            guard let kind = info.kind, kind.isRemote else { continue }
            result[kind] = info.configured ? .configured : .notConfigured
        }
        return result
    }
}
