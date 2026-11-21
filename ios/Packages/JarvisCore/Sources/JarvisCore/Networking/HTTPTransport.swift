import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// HTTP 전송 추상화. 테스트에서는 가짜 전송 계층을 주입한다.
public protocol HTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse)
}

public enum TransportError: Error, Equatable, LocalizedError {
    case nonHTTPResponse

    public var errorDescription: String? {
        "HTTP 응답이 아닙니다."
    }
}

/// URLSession 기반 기본 전송 계층.
public struct URLSessionTransport: HTTPTransport {
    private let session: URLSession

    public init(session: URLSession = .shared) {
        self.session = session
    }

    public func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw TransportError.nonHTTPResponse
        }
        return (data, http)
    }
}

/// 사용자 인증 토큰 제공자. iOS 앱에서는 Keychain 저장소가 구현한다.
public protocol AuthTokenProviding: Sendable {
    func currentToken() -> String?
}

/// 고정 토큰 (테스트용).
public struct StaticTokenProvider: AuthTokenProviding {
    private let token: String?
    public init(_ token: String?) { self.token = token }
    public func currentToken() -> String? { token }
}
