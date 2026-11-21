package com.jarvis.core.model

import java.util.UUID

/** 현재 실행 중이거나 완료된 작업 ("현재 작업 상태" 패널). */
data class JarvisTask(
    val title: String,
    val status: Status = Status.PENDING,
    /** 0.0 ~ 1.0. 진행률을 알 수 없으면 null. */
    val progress: Double? = null,
    val engine: AiEngine? = null,
    val detail: String? = null,
    val id: String = UUID.randomUUID().toString(),
    val createdAtMillis: Long = System.currentTimeMillis(),
    val finishedAtMillis: Long? = null,
) {
    enum class Status(val displayName: String, val isFinished: Boolean) {
        PENDING("대기", false),
        RUNNING("진행 중", false),
        SUCCEEDED("완료", true),
        FAILED("실패", true),
        CANCELLED("취소됨", true),
    }
}

/** 작업 목록 (불변). 종료된 작업은 최근 [maxFinishedTasks]개만 유지한다. */
data class TaskBoard(
    val tasks: List<JarvisTask> = emptyList(),
    val maxFinishedTasks: Int = 20,
) {
    val activeTasks: List<JarvisTask> get() = tasks.filterNot { it.status.isFinished }
    val finishedTasks: List<JarvisTask> get() = tasks.filter { it.status.isFinished }

    /** 새 작업을 진행 중 상태로 추가한다. */
    fun start(
        title: String,
        engine: AiEngine? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): Pair<TaskBoard, String> {
        val task = JarvisTask(title = title, status = JarvisTask.Status.RUNNING, engine = engine, createdAtMillis = nowMillis)
        return copy(tasks = tasks + task) to task.id
    }

    fun updateProgress(id: String, progress: Double?, detail: String? = null): TaskBoard =
        copy(tasks = tasks.map {
            if (it.id == id) it.copy(progress = progress?.coerceIn(0.0, 1.0), detail = detail ?: it.detail) else it
        })

    /** 작업을 종료 상태로 전환한다. 이미 종료된 작업은 변경하지 않는다. */
    fun finish(
        id: String,
        status: JarvisTask.Status,
        detail: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): TaskBoard {
        require(status.isFinished) { "finish() requires a finished status" }
        val updated = tasks.map {
            if (it.id == id && !it.status.isFinished) {
                it.copy(
                    status = status,
                    finishedAtMillis = nowMillis,
                    progress = if (status == JarvisTask.Status.SUCCEEDED) 1.0 else it.progress,
                    detail = detail ?: it.detail,
                )
            } else {
                it
            }
        }
        return copy(tasks = updated).trimmed()
    }

    /** 진행 중인 모든 작업을 취소 처리한다 (음성 대화 중단 등). */
    fun cancelAll(nowMillis: Long = System.currentTimeMillis()): TaskBoard =
        activeTasks.fold(this) { board, task -> board.finish(task.id, JarvisTask.Status.CANCELLED, nowMillis = nowMillis) }

    private fun trimmed(): TaskBoard {
        val finished = finishedTasks
        if (finished.size <= maxFinishedTasks) return this
        val removeIds = finished
            .sortedBy { it.finishedAtMillis ?: it.createdAtMillis }
            .take(finished.size - maxFinishedTasks)
            .map { it.id }
            .toSet()
        return copy(tasks = tasks.filterNot { it.id in removeIds })
    }
}
