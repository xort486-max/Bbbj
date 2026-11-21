package com.jarvis.core.model

import java.util.UUID

/** 대화 기록의 한 항목. */
data class ConversationMessage(
    val role: Role,
    val text: String,
    /** 응답을 생성한 엔진. 사용자/시스템 메시지는 null. */
    val engine: AiEngine? = null,
    val id: String = UUID.randomUUID().toString(),
    val timestampMillis: Long = System.currentTimeMillis(),
) {
    enum class Role {
        /** 사용자 발화/입력 */
        USER,

        /** AI 엔진 응답 */
        ASSISTANT,

        /** JARVIS 시스템 안내 (엔진 미연결, 오류, 권한 안내 등) */
        SYSTEM,
    }
}

/**
 * 화면에 표시되는 대화 기록 (불변). 최대 개수를 넘으면 오래된 메시지부터 제거한다.
 * 오래된 메시지 요약(토큰 절약)은 PHASE 7에서 백엔드 요약 기능과 연결한다.
 */
data class ConversationLog(
    val messages: List<ConversationMessage> = emptyList(),
    val maxMessages: Int = 200,
) {
    init {
        require(maxMessages > 0) { "maxMessages must be positive" }
    }

    val isEmpty: Boolean get() = messages.isEmpty()
    val last: ConversationMessage? get() = messages.lastOrNull()

    fun append(message: ConversationMessage): ConversationLog =
        copy(messages = (messages + message).takeLast(maxMessages))

    /** 스트리밍 응답 등으로 메시지 본문을 갱신한다. 없는 id면 그대로 반환. */
    fun updateText(id: String, text: String): ConversationLog =
        copy(messages = messages.map { if (it.id == id) it.copy(text = text) else it })

    fun cleared(): ConversationLog = copy(messages = emptyList())
}
