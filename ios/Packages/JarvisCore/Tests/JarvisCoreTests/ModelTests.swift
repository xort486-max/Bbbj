import XCTest
@testable import JarvisCore

final class ConversationLogTests: XCTestCase {
    func testAppendAndTrim() {
        var log = ConversationLog(maxMessages: 3)
        for i in 1...5 {
            log.append(ConversationMessage(role: .user, text: "m\(i)"))
        }
        XCTAssertEqual(log.messages.map(\.text), ["m3", "m4", "m5"])
        XCTAssertEqual(log.last?.text, "m5")
    }

    func testUpdateTextForStreaming() {
        var log = ConversationLog()
        let message = log.append(ConversationMessage(role: .assistant, text: "", engine: .nvidia))
        XCTAssertTrue(log.updateText(id: message.id, text: "안녕하세요"))
        XCTAssertEqual(log.last?.text, "안녕하세요")
        XCTAssertFalse(log.updateText(id: UUID(), text: "없음"))
    }

    func testMessageCodableRoundTrip() throws {
        let message = ConversationMessage(role: .assistant, text: "응답", engine: .claude)
        let data = try JSONEncoder().encode(message)
        let decoded = try JSONDecoder().decode(ConversationMessage.self, from: data)
        XCTAssertEqual(decoded, message)
    }
}

final class TaskBoardTests: XCTestCase {
    func testStartProgressFinish() {
        var board = TaskBoard()
        let id = board.start("백엔드 연결 확인", engine: .local)
        XCTAssertEqual(board.activeTasks.count, 1)

        board.updateProgress(id, progress: 1.7, detail: "응답 대기")
        XCTAssertEqual(board.tasks.first?.progress, 1.0, "진행률은 0...1로 제한")
        XCTAssertEqual(board.tasks.first?.detail, "응답 대기")

        board.finish(id, status: .succeeded)
        XCTAssertTrue(board.activeTasks.isEmpty)
        XCTAssertEqual(board.finishedTasks.first?.status, .succeeded)
        XCTAssertNotNil(board.finishedTasks.first?.finishedAt)

        board.finish(id, status: .failed)
        XCTAssertEqual(board.finishedTasks.first?.status, .succeeded, "종료된 작업은 다시 바뀌지 않는다")
    }

    func testCancelAll() {
        var board = TaskBoard()
        board.start("A")
        board.start("B")
        board.cancelAll()
        XCTAssertTrue(board.activeTasks.isEmpty)
        XCTAssertEqual(board.finishedTasks.map(\.status), [.cancelled, .cancelled])
    }

    func testFinishedTasksAreTrimmedOldestFirst() {
        var board = TaskBoard(maxFinishedTasks: 2)
        let base = Date(timeIntervalSince1970: 1_000)
        var ids: [UUID] = []
        for i in 0..<4 {
            ids.append(board.start("T\(i)", now: base))
        }
        for (i, id) in ids.enumerated() {
            board.finish(id, status: .succeeded, now: base.addingTimeInterval(Double(i)))
        }
        XCTAssertEqual(board.finishedTasks.map(\.title), ["T2", "T3"])
    }
}

final class FeatureAvailabilityTests: XCTestCase {
    func testPhaseOneMarksNothingAsImplemented() {
        // PHASE 1은 UI 골격만 제공한다. 동작하지 않는 기능을 "사용 가능"으로 표시하면 안 된다.
        for feature in JarvisFeature.allCases {
            XCTAssertFalse(feature.isImplemented, "\(feature) should not be implemented in PHASE 1")
            XCTAssertEqual(feature.availabilityLabel, "PHASE \(feature.plannedPhase) 예정")
        }
    }

    func testEngineRoles() {
        XCTAssertEqual(AIEngineKind.remoteEngines, [.nvidia, .google, .claude])
        XCTAssertEqual(AIEngineKind.claude.roleDescription, "AI 두뇌 · 에이전트")
    }

    func testStatusLevels() {
        XCTAssertEqual(EngineConnectionStatus.configured.level, .ok)
        XCTAssertEqual(EngineConnectionStatus.notConfigured.level, .warning)
        XCTAssertEqual(EngineConnectionStatus.unreachable("timeout").level, .failure)
        XCTAssertEqual(EngineConnectionStatus.unreachable("timeout").detail, "timeout")
        XCTAssertNotEqual(EngineConnectionStatus.configured.displayName, EngineConnectionStatus.connected.displayName)
    }
}
