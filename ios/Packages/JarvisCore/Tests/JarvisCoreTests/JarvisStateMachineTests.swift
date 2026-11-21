import XCTest
@testable import JarvisCore

final class JarvisStateMachineTests: XCTestCase {
    func testStartsIdle() {
        let machine = JarvisStateMachine()
        XCTAssertEqual(machine.state, .idle)
        XCTAssertFalse(machine.state.isBusy)
        XCTAssertTrue(machine.history.isEmpty)
    }

    func testVoiceConversationFlow() throws {
        var machine = JarvisStateMachine()
        try machine.transition(to: .listening)
        try machine.transition(to: .analyzing)
        try machine.transition(to: .nvidiaResponding)
        try machine.transition(to: .completed)
        try machine.transition(to: .idle)
        XCTAssertEqual(machine.state, .idle)
        XCTAssertEqual(machine.history.count, 5)
        XCTAssertEqual(machine.history.first?.from, .idle)
        XCTAssertEqual(machine.history.first?.to, .listening)
    }

    func testAgentToolLoopFlow() throws {
        var machine = JarvisStateMachine()
        try machine.transition(to: .analyzing)
        try machine.transition(to: .claudeReasoning)
        try machine.transition(to: .googleSearching)
        try machine.transition(to: .claudeReasoning)
        try machine.transition(to: .executingTask)
        try machine.transition(to: .claudeReasoning)
        try machine.transition(to: .completed)
        XCTAssertEqual(machine.state, .completed)
    }

    func testRejectsSkippingAnalysis() {
        var machine = JarvisStateMachine()
        XCTAssertThrowsError(try machine.transition(to: .claudeReasoning)) { error in
            XCTAssertEqual(
                error as? JarvisStateMachine.TransitionError,
                .notAllowed(from: .idle, to: .claudeReasoning)
            )
        }
        XCTAssertEqual(machine.state, .idle, "거부된 전이는 상태를 바꾸지 않아야 한다")
        XCTAssertTrue(machine.history.isEmpty)
    }

    func testErrorAndIdleReachableFromEveryState() {
        let all: [JarvisState] = [
            .idle, .listening, .analyzing, .nvidiaResponding, .googleSearching,
            .claudeReasoning, .executingTask, .completed, .error(message: "x"),
        ]
        for state in all {
            XCTAssertTrue(JarvisStateMachine.canTransition(from: state, to: .error(message: "e")), "\(state) → error")
            XCTAssertTrue(JarvisStateMachine.canTransition(from: state, to: .idle), "\(state) → idle")
        }
    }

    func testErrorMessageUpdateIsRecorded() throws {
        var machine = JarvisStateMachine()
        try machine.transition(to: .error(message: "첫 오류"))
        try machine.transition(to: .error(message: "두 번째 오류"))
        XCTAssertEqual(machine.state.errorMessage, "두 번째 오류")
        XCTAssertEqual(machine.history.count, 2)
    }

    func testSameStateTransitionIsNoOp() throws {
        var machine = JarvisStateMachine()
        try machine.transition(to: .listening)
        try machine.transition(to: .listening)
        XCTAssertEqual(machine.history.count, 1)
    }

    func testResetCancelsBusyState() throws {
        var machine = JarvisStateMachine()
        try machine.transition(to: .analyzing)
        try machine.transition(to: .claudeReasoning)
        machine.reset()
        XCTAssertEqual(machine.state, .idle)
        machine.reset()
        XCTAssertEqual(machine.history.count, 3, "이미 대기 중이면 reset은 기록을 남기지 않는다")
    }

    func testHistoryIsBounded() throws {
        var machine = JarvisStateMachine(maxHistory: 4)
        for _ in 0..<10 {
            try machine.transition(to: .listening)
            try machine.transition(to: .idle)
        }
        XCTAssertEqual(machine.history.count, 4)
    }

    func testEveryStateHasKoreanDisplayNameAndEngineMapping() {
        XCTAssertEqual(JarvisState.idle.displayName, "대기 중")
        XCTAssertEqual(JarvisState.listening.displayName, "음성 인식 중")
        XCTAssertEqual(JarvisState.analyzing.displayName, "사용자 명령 분석 중")
        XCTAssertEqual(JarvisState.nvidiaResponding.displayName, "NVIDIA 응답 중")
        XCTAssertEqual(JarvisState.googleSearching.displayName, "Google 검색 중")
        XCTAssertEqual(JarvisState.claudeReasoning.displayName, "Claude 추론 중")
        XCTAssertEqual(JarvisState.executingTask.displayName, "작업 실행 중")
        XCTAssertEqual(JarvisState.completed.displayName, "작업 완료")
        XCTAssertEqual(JarvisState.error(message: "x").displayName, "오류 발생")

        XCTAssertEqual(JarvisState.nvidiaResponding.activeEngine, .nvidia)
        XCTAssertEqual(JarvisState.googleSearching.activeEngine, .google)
        XCTAssertEqual(JarvisState.claudeReasoning.activeEngine, .claude)
        XCTAssertNil(JarvisState.idle.activeEngine)
        XCTAssertEqual(Set(JarvisState.Kind.allCases.map(\.rawValue)).count, 9)
    }
}
