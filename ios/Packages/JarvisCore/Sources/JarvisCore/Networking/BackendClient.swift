import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum BackendError: Error, Equatable, LocalizedError {
    case missingToken
    case unauthorized
    case serverNotConfigured(String?)
    case http(status: Int, message: String?)
    case network(String)
    case decoding(String)

    public var errorDescription: String? {
        switch self {
        case .missingToken:
            "앱 인증 토큰이 설정되지 않았습니다. 설정에서 토큰을 입력하세요."
        case .unauthorized:
            "앱 인증 토큰이 올바르지 않습니다."
        case let .serverNotConfigured(message):
            "백엔드 서버 설정이 완료되지 않았습니다." + (message.map { " (\($0))" } ?? "")
        case let .http(status, message):
            "백엔드 오류 \(status)" + (message.map { ": \($0)" } ?? "")
        case let .network(message):
            "백엔드에 연결할 수 없습니다: \(message)"
        case let .decoding(message):
            "백엔드 응답을 해석할 수 없습니다: \(message)"
        }
    }
}

/// JARVIS 백엔드 HTTP 클라이언트.
///
/// PHASE 1: 상태 확인(`/health`, `/v1/engines`)만 제공한다.
/// PHASE 2에서 대화·검색·에이전트 엔드포인트를 추가한다.
public struct BackendClient: Sendable {
    public let configuration: BackendConfiguration
    private let transport: HTTPTransport
    private let tokenProvider: AuthTokenProviding

    public init(
        configuration: BackendConfiguration,
        transport: HTTPTransport = URLSessionTransport(),
        tokenProvider: AuthTokenProviding
    ) {
        self.configuration = configuration
        self.transport = transport
        self.tokenProvider = tokenProvider
    }

    /// 백엔드 생존 확인 (인증 불필요).
    public func health() async throws -> HealthResponse {
        try await get("/health", authenticated: false)
    }

    /// 백엔드에 설정된 엔진 목록 (인증 필요).
    public func engines() async throws -> EnginesResponse {
        try await get("/v1/engines", authenticated: true)
    }

    // MARK: - 내부

    func makeRequest(path: String, authenticated: Bool) throws -> URLRequest {
        var request = URLRequest(url: configuration.url(path: path))
        request.httpMethod = "GET"
        request.timeoutInterval = configuration.timeout
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if authenticated {
            guard let token = tokenProvider.currentToken(), !token.isEmpty else {
                throw BackendError.missingToken
            }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return request
    }

    private func get<T: Decodable>(_ path: String, authenticated: Bool) async throws -> T {
        let request = try makeRequest(path: path, authenticated: authenticated)

        let data: Data
        let response: HTTPURLResponse
        do {
            (data, response) = try await transport.send(request)
        } catch let error as BackendError {
            throw error
        } catch {
            throw BackendError.network(error.localizedDescription)
        }

        switch response.statusCode {
        case 200..<300:
            do {
                return try JSONDecoder().decode(T.self, from: data)
            } catch {
                throw BackendError.decoding(String(describing: error))
            }
        case 401, 403:
            throw BackendError.unauthorized
        case 503:
            throw BackendError.serverNotConfigured(Self.detail(from: data))
        default:
            throw BackendError.http(status: response.statusCode, message: Self.detail(from: data))
        }
    }

    private static func detail(from data: Data) -> String? {
        (try? JSONDecoder().decode(ErrorBody.self, from: data))?.detail
    }
}
