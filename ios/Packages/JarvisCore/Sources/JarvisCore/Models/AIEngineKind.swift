import Foundation

/// JARVIS가 요청을 분배하는 실행 엔진.
///
/// - `nvidia`: 일반 대화 엔진 (NVIDIA API Catalog, OpenAI 호환 Chat Completions)
/// - `google`: 검색 엔진 (Google 공식 검색 제품 — PHASE 2에서 제품 확정)
/// - `claude`: AI 두뇌 · 에이전트 (Claude API Tool Use)
/// - `local`: 기기 내 명령 엔진 (분류기, 캘린더, 미리 알림, 파일 등)
public enum AIEngineKind: String, CaseIterable, Codable, Sendable, Identifiable {
    case nvidia
    case google
    case claude
    case local

    public var id: String { rawValue }

    /// HUD에 표시하는 짧은 이름.
    public var displayName: String {
        switch self {
        case .nvidia: "NVIDIA"
        case .google: "GOOGLE"
        case .claude: "CLAUDE"
        case .local: "LOCAL"
        }
    }

    /// 엔진의 역할 (한국어).
    public var roleDescription: String {
        switch self {
        case .nvidia: "대화 엔진"
        case .google: "검색 엔진"
        case .claude: "AI 두뇌 · 에이전트"
        case .local: "로컬 명령 엔진"
        }
    }

    /// 백엔드를 거쳐야 하는 원격 엔진인지 여부. API Key는 백엔드에만 존재한다.
    public var isRemote: Bool {
        self != .local
    }

    /// 원격 엔진 목록 (백엔드 상태 조회 대상).
    public static var remoteEngines: [AIEngineKind] {
        allCases.filter(\.isRemote)
    }
}
