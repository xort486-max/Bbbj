import Foundation

/// JARVIS 백엔드 접속 정보.
///
/// iOS 앱은 AI 제공자 API Key를 갖지 않는다. 앱이 아는 것은 백엔드 주소와
/// 사용자 인증 토큰(Keychain 보관)뿐이다.
public struct BackendConfiguration: Equatable, Sendable {
    public let baseURL: URL
    public let timeout: TimeInterval

    public enum ValidationError: Error, Equatable, LocalizedError {
        case empty
        case invalidURL
        case insecureScheme

        public var errorDescription: String? {
            switch self {
            case .empty: "백엔드 주소가 설정되지 않았습니다."
            case .invalidURL: "백엔드 주소 형식이 올바르지 않습니다."
            case .insecureScheme: "백엔드 주소는 HTTPS여야 합니다. (개발용 localhost/사설망 주소만 HTTP 허용)"
            }
        }
    }

    public init(baseURL: URL, timeout: TimeInterval = 30) {
        self.baseURL = baseURL
        self.timeout = timeout
    }

    /// 문자열 주소를 검증해 설정을 만든다.
    ///
    /// HTTPS만 허용한다. 단, 개발 중 Mac에서 실행하는 로컬 백엔드를 위해
    /// `localhost`, `127.0.0.1`, `*.local`, 사설 IPv4 대역은 HTTP를 허용한다.
    public static func validated(_ raw: String, timeout: TimeInterval = 30) throws -> BackendConfiguration {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw ValidationError.empty }
        guard let components = URLComponents(string: trimmed),
              let scheme = components.scheme?.lowercased(),
              let host = components.host, !host.isEmpty,
              var url = components.url
        else { throw ValidationError.invalidURL }

        switch scheme {
        case "https":
            break
        case "http":
            guard isLocalDevelopmentHost(host) else { throw ValidationError.insecureScheme }
        default:
            throw ValidationError.invalidURL
        }

        // 끝의 "/" 제거 → 경로 결합 시 이중 슬래시 방지
        if url.absoluteString.hasSuffix("/") {
            url = URL(string: String(url.absoluteString.dropLast())) ?? url
        }
        return BackendConfiguration(baseURL: url, timeout: timeout)
    }

    static func isLocalDevelopmentHost(_ host: String) -> Bool {
        let host = host.lowercased()
        if host == "localhost" || host == "127.0.0.1" || host == "::1" || host.hasSuffix(".local") {
            return true
        }
        let octets = host.split(separator: ".").compactMap { Int($0) }
        guard octets.count == 4, octets.allSatisfy({ (0...255).contains($0) }) else { return false }
        switch (octets[0], octets[1]) {
        case (10, _): return true
        case (192, 168): return true
        case (172, 16...31): return true
        default: return false
        }
    }

    public func url(path: String) -> URL {
        let cleaned = path.hasPrefix("/") ? String(path.dropFirst()) : path
        return baseURL.appendingPathComponent(cleaned)
    }
}
