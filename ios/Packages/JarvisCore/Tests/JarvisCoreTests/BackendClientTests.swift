import XCTest
@testable import JarvisCore
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// 요청을 기록하고 미리 정한 응답을 돌려주는 가짜 전송 계층.
private final class MockTransport: HTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var _requests: [URLRequest] = []
    private let handler: @Sendable (URLRequest) throws -> (Int, String)

    init(handler: @escaping @Sendable (URLRequest) throws -> (Int, String)) {
        self.handler = handler
    }

    var requests: [URLRequest] {
        lock.withLock { _requests }
    }

    private func record(_ request: URLRequest) {
        lock.withLock { _requests.append(request) }
    }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        record(request)
        let (status, body) = try handler(request)
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: nil)!
        return (Data(body.utf8), response)
    }
}

private struct OfflineError: Error, LocalizedError {
    var errorDescription: String? { "오프라인" }
}

final class BackendConfigurationTests: XCTestCase {
    func testAcceptsHTTPSAndTrimsTrailingSlash() throws {
        let config = try BackendConfiguration.validated("  https://jarvis.example.com/  ")
        XCTAssertEqual(config.baseURL.absoluteString, "https://jarvis.example.com")
        XCTAssertEqual(config.url(path: "/health").absoluteString, "https://jarvis.example.com/health")
        XCTAssertEqual(config.url(path: "v1/engines").absoluteString, "https://jarvis.example.com/v1/engines")
    }

    func testRejectsPlainHTTPOnPublicHost() {
        XCTAssertThrowsError(try BackendConfiguration.validated("http://jarvis.example.com")) {
            XCTAssertEqual($0 as? BackendConfiguration.ValidationError, .insecureScheme)
        }
        XCTAssertThrowsError(try BackendConfiguration.validated("http://8.8.8.8:8000")) {
            XCTAssertEqual($0 as? BackendConfiguration.ValidationError, .insecureScheme)
        }
    }

    func testAllowsHTTPForLocalDevelopment() throws {
        for raw in ["http://localhost:8000", "http://127.0.0.1:8000", "http://192.168.0.12:8000",
                    "http://10.0.0.5", "http://172.20.1.1", "http://my-mac.local:8000"] {
            XCTAssertNoThrow(try BackendConfiguration.validated(raw), raw)
        }
        XCTAssertFalse(BackendConfiguration.isLocalDevelopmentHost("172.32.0.1"))
        XCTAssertFalse(BackendConfiguration.isLocalDevelopmentHost("192.169.0.1"))
    }

    func testRejectsEmptyAndMalformed() {
        XCTAssertThrowsError(try BackendConfiguration.validated("   ")) {
            XCTAssertEqual($0 as? BackendConfiguration.ValidationError, .empty)
        }
        XCTAssertThrowsError(try BackendConfiguration.validated("jarvis.example.com")) {
            XCTAssertEqual($0 as? BackendConfiguration.ValidationError, .invalidURL)
        }
        XCTAssertThrowsError(try BackendConfiguration.validated("ftp://jarvis.example.com")) {
            XCTAssertEqual($0 as? BackendConfiguration.ValidationError, .invalidURL)
        }
    }
}

final class BackendClientTests: XCTestCase {
    private let config = BackendConfiguration(baseURL: URL(string: "https://jarvis.example.com")!)

    func testHealthDoesNotSendToken() async throws {
        let transport = MockTransport { _ in (200, #"{"status":"ok","service":"jarvis-backend","version":"0.1.0"}"#) }
        let client = BackendClient(configuration: config, transport: transport, tokenProvider: StaticTokenProvider("secret"))

        let health = try await client.health()
        XCTAssertTrue(health.isOK)
        XCTAssertEqual(health.version, "0.1.0")
        XCTAssertEqual(transport.requests.first?.url?.path, "/health")
        XCTAssertNil(transport.requests.first?.value(forHTTPHeaderField: "Authorization"))
    }

    func testEnginesSendsBearerTokenAndMapsStatuses() async throws {
        let body = """
        {"engines":[
          {"id":"nvidia","configured":true,"provider":"NVIDIA API Catalog","model":"some/model","note":null},
          {"id":"google","configured":false,"provider":"undecided","model":null,"note":"검색 제품 선택 필요"},
          {"id":"unknown-engine","configured":true,"provider":"x","model":null,"note":null}
        ]}
        """
        let transport = MockTransport { _ in (200, body) }
        let client = BackendClient(configuration: config, transport: transport, tokenProvider: StaticTokenProvider("secret"))

        let response = try await client.engines()
        XCTAssertEqual(transport.requests.first?.value(forHTTPHeaderField: "Authorization"), "Bearer secret")
        XCTAssertEqual(response.engines.count, 3)

        let statuses = EngineStatusMapper.statuses(from: response)
        XCTAssertEqual(statuses[.nvidia], .configured)
        XCTAssertEqual(statuses[.google], .notConfigured)
        XCTAssertEqual(statuses[.claude], .notConfigured, "응답에 없는 엔진은 미설정으로 표시")
        XCTAssertNil(statuses[.local])
    }

    func testEnginesWithoutTokenFailsBeforeNetwork() async {
        let transport = MockTransport { _ in (200, "{}") }
        let client = BackendClient(configuration: config, transport: transport, tokenProvider: StaticTokenProvider(nil))
        await assertThrows(BackendError.missingToken) { _ = try await client.engines() }
        XCTAssertTrue(transport.requests.isEmpty)
    }

    func testStatusCodeMapping() async {
        let cases: [(Int, String, BackendError)] = [
            (401, #"{"detail":"invalid token"}"#, .unauthorized),
            (503, #"{"detail":"JARVIS_APP_TOKEN not set"}"#, .serverNotConfigured("JARVIS_APP_TOKEN not set")),
            (500, #"{"detail":"boom"}"#, .http(status: 500, message: "boom")),
            (502, "not json", .http(status: 502, message: nil)),
        ]
        for (status, body, expected) in cases {
            let transport = MockTransport { _ in (status, body) }
            let client = BackendClient(configuration: config, transport: transport, tokenProvider: StaticTokenProvider("t"))
            await assertThrows(expected) { _ = try await client.engines() }
        }
    }

    func testNetworkAndDecodingErrors() async {
        let offline = MockTransport { _ in throw OfflineError() }
        let client1 = BackendClient(configuration: config, transport: offline, tokenProvider: StaticTokenProvider("t"))
        await assertThrows(BackendError.network("오프라인")) { _ = try await client1.health() }

        let garbage = MockTransport { _ in (200, #"{"unexpected":true}"#) }
        let client2 = BackendClient(configuration: config, transport: garbage, tokenProvider: StaticTokenProvider("t"))
        do {
            _ = try await client2.health()
            XCTFail("expected decoding error")
        } catch let BackendError.decoding(message) {
            XCTAssertFalse(message.isEmpty)
        } catch {
            XCTFail("unexpected error \(error)")
        }
    }

    private func assertThrows(
        _ expected: BackendError,
        file: StaticString = #filePath, line: UInt = #line,
        _ body: () async throws -> Void
    ) async {
        do {
            try await body()
            XCTFail("expected \(expected)", file: file, line: line)
        } catch {
            XCTAssertEqual(error as? BackendError, expected, file: file, line: line)
        }
    }
}
