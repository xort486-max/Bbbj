package com.jarvis.core

import com.jarvis.core.model.AiEngine
import com.jarvis.core.model.ConnectionStatus
import com.jarvis.core.model.ConversationLog
import com.jarvis.core.model.ConversationMessage
import com.jarvis.core.model.JarvisFeature
import com.jarvis.core.model.JarvisTask
import com.jarvis.core.model.TaskBoard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConversationLogTest {
    @Test
    fun appendAndTrim() {
        var log = ConversationLog(maxMessages = 3)
        for (i in 1..5) log = log.append(ConversationMessage(ConversationMessage.Role.USER, "m$i"))
        assertEquals(listOf("m3", "m4", "m5"), log.messages.map { it.text })
        assertEquals("m5", log.last?.text)
    }

    @Test
    fun updateTextForStreaming() {
        val message = ConversationMessage(ConversationMessage.Role.ASSISTANT, "", AiEngine.NVIDIA)
        val log = ConversationLog().append(message).updateText(message.id, "안녕하세요")
        assertEquals("안녕하세요", log.last?.text)
        assertEquals(log, log.updateText("missing", "x"))
    }
}

class TaskBoardTest {
    @Test
    fun startProgressFinish() {
        var (board, id) = TaskBoard().start("백엔드 연결 확인")
        assertEquals(1, board.activeTasks.size)

        board = board.updateProgress(id, 1.7, "응답 대기")
        assertEquals(1.0, board.tasks.first().progress, "진행률은 0..1로 제한")
        assertEquals("응답 대기", board.tasks.first().detail)

        board = board.finish(id, JarvisTask.Status.SUCCEEDED)
        assertTrue(board.activeTasks.isEmpty())
        assertEquals(JarvisTask.Status.SUCCEEDED, board.finishedTasks.first().status)
        assertNotNull(board.finishedTasks.first().finishedAtMillis)

        board = board.finish(id, JarvisTask.Status.FAILED)
        assertEquals(JarvisTask.Status.SUCCEEDED, board.finishedTasks.first().status, "종료된 작업은 다시 바뀌지 않는다")
    }

    @Test
    fun cancelAll() {
        val board = TaskBoard().start("A").first.start("B").first.cancelAll()
        assertTrue(board.activeTasks.isEmpty())
        assertEquals(listOf(JarvisTask.Status.CANCELLED, JarvisTask.Status.CANCELLED), board.finishedTasks.map { it.status })
    }

    @Test
    fun finishedTasksAreTrimmedOldestFirst() {
        var board = TaskBoard(maxFinishedTasks = 2)
        val ids = (0 until 4).map { i ->
            val (next, id) = board.start("T$i", nowMillis = 1_000)
            board = next
            id
        }
        ids.forEachIndexed { i, id -> board = board.finish(id, JarvisTask.Status.SUCCEEDED, nowMillis = 2_000L + i) }
        assertEquals(listOf("T2", "T3"), board.finishedTasks.map { it.title })
    }
}

class FeatureAndStatusTest {
    @Test
    fun onlyActuallyImplementedFeaturesAreMarkedReady() {
        // 동작하지 않는 기능을 "사용 가능"으로 표시하면 안 된다. PHASE 2 기준: 검색만 구현.
        val implemented = JarvisFeature.entries.filter { it.isImplemented }
        assertEquals(listOf(JarvisFeature.SEARCH), implemented)
        for (feature in JarvisFeature.entries - JarvisFeature.SEARCH) {
            assertEquals("PHASE ${feature.plannedPhase} 예정", feature.availabilityLabel)
        }
        assertEquals("사용 가능", JarvisFeature.SEARCH.availabilityLabel)
    }

    @Test
    fun engineRoles() {
        assertEquals(listOf(AiEngine.NVIDIA, AiEngine.GOOGLE), AiEngine.remoteEngines)
        assertEquals("검색 · 분석", AiEngine.GOOGLE.roleDescription)
        assertEquals(AiEngine.GOOGLE, AiEngine.fromId("google"))
    }

    @Test
    fun statusLevels() {
        assertEquals(ConnectionStatus.Level.OK, ConnectionStatus.Configured.level)
        assertEquals(ConnectionStatus.Level.WARNING, ConnectionStatus.NotConfigured.level)
        assertEquals(ConnectionStatus.Level.FAILURE, ConnectionStatus.Unreachable("timeout").level)
        assertNotEquals(ConnectionStatus.Configured.displayName, ConnectionStatus.Connected.displayName)
    }
}
