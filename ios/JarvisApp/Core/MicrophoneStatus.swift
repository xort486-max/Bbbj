import Foundation

/// 마이크 상태. 마이크가 켜져 있으면 화면에 반드시 눈에 띄게 표시한다.
enum MicrophoneStatus: Equatable {
    /// PHASE 3 이전: 음성 기능 미구현
    case unavailable
    /// 권한 미요청/거부 등으로 꺼져 있음
    case off
    /// 마이크 사용 중 (음성 인식 중)
    case live

    var label: String {
        switch self {
        case .unavailable: "MIC 미구현"
        case .off: "MIC OFF"
        case .live: "MIC LIVE"
        }
    }

    var accessibilityText: String {
        switch self {
        case .unavailable: "마이크 기능은 아직 구현되지 않았습니다"
        case .off: "마이크 꺼짐"
        case .live: "마이크 사용 중"
        }
    }
}
