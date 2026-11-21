import XCTest
import JarvisCore
@testable import JARVIS
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// 고정 응답을 돌려주는 전송 계층.
private struct StubTransport: HTTPTransport {
    let routes: [String: (Int, String)]

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let path = request.url?.path ?? ""
        let (status, body) = routes[path] ?? (404, #"{"detail":"not found"}"#)
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
        return (Data(body.utf8), response)
    }
}

@MainActor
final class MainViewModelTests: XCTestCase {
    /// 테스트마다 격리된 UserDefaults.
    private func freshDefaults() -> UserDefaults {
        UserDefaults(suiteName: "jarvis.tests.\(UUID().uuidString)")!
    }

    private func makeModel(
        defaults: UserDefaults? = nil,
        token: String? = "t",
        routes: [String: (Int, String)] = [:]
    ) -> MainViewModel {
        MainViewModel(
            defaults: defaults ?? freshDefaults(),
            tokenProvider: StaticTokenProvider(token),
            transport: StubTransport(routes: routes)
        )
    }

    func testSubmitAppendsUserMessageAndHonestNotice() async {
        let model = makeModel()
        model.inputText = "  안녕 자비스  "
        model.submitInput()

        XCTAssertEqual(model.inputText, "")
        XCTAssertEqual(model.log.messages.first?.role, .user)
        XCTAssertEqual(model.log.messages.first?.text, "안녕 자비스")
        // PHASE 1에서는 AI 응답을 흉내 내지 않고 시스템 안내만 표시해야 한다.
        XCTAssertFalse(model.log.messages.contains { $0.role == .assistant })
        XCTAssertEqual(model.log.last?.role, .system)
        XCTAssertEqual(model.state, .completed)
    }

    func testEmptyInputIsIgnored() async {
        let model = makeModel()
        model.inputText = "   "
        model.submitInput()
        XCTAssertTrue(model.log.isEmpty)
        XCTAssertEqual(model.state, .idle)
    }

    func testMissingBackendURLShowsNotConfigured() async {
        let model = makeModel()
        await model.refreshEngineStatus()
        XCTAssertEqual(model.backendStatus, .notConfigured)
    }

    func testRefreshMapsEngineStatuses() async {
        let defaults = freshDefaults()
        defaults.jarvisBackendURL = "https://jarvis.example.com"
        let model = makeModel(defaults: defaults, routes: [
            "/health": (200, #"{"status":"ok","service":"jarvis-backend","version":"0.1.0"}"#),
            "/v1/engines": (200, #"{"engines":[{"id":"nvidia","configured":true,"provider":"NVIDIA","model":"m","note":null},{"id":"claude","configured":false,"provider":"Anthropic","model":"claude-opus-5-5","note":null}]}"#),
        ])
        await model.refreshEngineStatus()
        XCTAssertEqual(model.backendStatus, .connected)
        XCTAssertEqual(model.engineStatuses[.nvidia], .configured)
        XCTAssertEqual(model.engineStatuses[.claude], .notConfigured)
        XCTAssertEqual(model.engineStatuses[.google], .notConfigured)
        XCTAssertEqual(model.taskBoard.finishedTasks.last?.status, .succeeded)
    }

    func testInsecureURLIsRejected() async {
        let defaults = freshDefaults()
        defaults.jarvisBackendURL = "http://jarvis.example.com"
        let model = makeModel(defaults: defaults)
        await model.refreshEngineStatus()
        XCTAssertEqual(model.backendStatus.level, .failure)
    }

    func testFeaturesNotImplementedProduceNotice() async {
        let model = makeModel()
        model.selectFeature(.search)
        XCTAssertEqual(model.log.last?.role, .system)
        XCTAssertTrue(model.log.last?.text.contains("PHASE 2") == true)
    }
}
