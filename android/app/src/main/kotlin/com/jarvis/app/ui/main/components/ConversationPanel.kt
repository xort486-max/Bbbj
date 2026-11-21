package com.jarvis.app.ui.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.app.ui.theme.JarvisColors
import com.jarvis.app.ui.theme.JarvisType
import com.jarvis.app.ui.theme.hudPanel
import com.jarvis.core.model.ConversationMessage
import com.jarvis.core.model.JarvisFeature

/** 실시간 대화 기록. 새 메시지가 추가되면 맨 아래로 자동 스크롤한다. */
@Composable
fun ConversationPanel(messages: List<ConversationMessage>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    Column(modifier.fillMaxWidth().hudPanel(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HudSectionTitle("// CONVERSATION LOG", trailing = "${messages.size}")
        if (messages.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "\"자비스\"라고 부르거나 아래에 명령을 입력하세요.",
                    style = JarvisType.body(13.sp, color = JarvisColors.TextDim),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                items(messages, key = { it.id }) { MessageRow(it) }
            }
        }
    }
}

@Composable
private fun MessageRow(message: ConversationMessage) {
    when (message.role) {
        ConversationMessage.Role.USER -> Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f).width(40.dp))
            SelectionContainer {
                Text(
                    message.text,
                    style = JarvisType.body(14.sp),
                    modifier = Modifier
                        .background(JarvisColors.Blue.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .semantics { contentDescription = "사용자: ${message.text}" },
                )
            }
        }

        ConversationMessage.Role.ASSISTANT -> Column(
            Modifier.padding(end = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "JARVIS" + (message.engine?.let { " · ${it.displayName}" } ?: ""),
                style = JarvisType.hud(9.sp, FontWeight.Bold, JarvisColors.Cyan),
            )
            SelectionContainer {
                Text(
                    message.text + if (message.isStreaming) " ▍" else "",
                    style = JarvisType.body(14.sp),
                )
            }
            // Gemini 근거 결과: 검색 제안 위젯과 출처를 답변과 함께 표시 (약관)
            message.searchSuggestionsHtml?.let { SearchSuggestions(it) }
            SourceLinks(message.sources, message.sourceProvider)
        }

        ConversationMessage.Role.SYSTEM -> Row(
            Modifier
                .fillMaxWidth()
                .background(JarvisColors.Warning.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                .padding(8.dp)
                .semantics(mergeDescendants = true) {},
        ) {
            Icon(Icons.Outlined.Info, null, tint = JarvisColors.Warning.copy(alpha = 0.9f), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(message.text, style = JarvisType.body(12.sp, color = JarvisColors.Warning.copy(alpha = 0.9f)))
        }
    }
}

private val JarvisFeature.icon: ImageVector
    get() = when (this) {
        JarvisFeature.VOICE -> Icons.Outlined.GraphicEq
        JarvisFeature.SEARCH -> Icons.Outlined.Search
        JarvisFeature.CALENDAR -> Icons.Outlined.CalendarMonth
        JarvisFeature.ALARM -> Icons.Outlined.Alarm
        JarvisFeature.NOTE -> Icons.AutoMirrored.Outlined.StickyNote2
        JarvisFeature.PROJECT -> Icons.Outlined.CreateNewFolder
        JarvisFeature.VIDEO -> Icons.Outlined.Movie
    }

/**
 * 빠른 기능 실행 메뉴.
 * 구현되지 않은 기능도 누를 수는 있지만 실행되지 않고, 예정 단계 안내만 대화 기록에 표시한다.
 */
@Composable
fun QuickActionsBar(onSelect: (JarvisFeature) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(JarvisFeature.entries) { feature ->
            val ready = feature.isImplemented
            val fg = if (ready) JarvisColors.Cyan else JarvisColors.TextSecondary.copy(alpha = 0.6f)
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .size(64.dp)
                    .background(JarvisColors.Panel, shape)
                    .border(1.dp, JarvisColors.PanelBorder.copy(alpha = if (ready) 1f else 0.4f), shape)
                    .clickable(role = Role.Button) { onSelect(feature) }
                    .clearAndSetSemantics {
                        contentDescription = "${feature.title}, ${feature.availabilityLabel}"
                        if (!ready) disabled()
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(feature.icon, null, tint = fg, modifier = Modifier.size(18.dp))
                Text(feature.title, style = JarvisType.body(11.sp, FontWeight.Medium, fg), maxLines = 1)
                Text(
                    if (ready) "READY" else "P${feature.plannedPhase}",
                    style = JarvisType.hud(8.sp, FontWeight.Bold, if (ready) JarvisColors.Ok else JarvisColors.TextDim),
                )
            }
        }
    }
}

/** 텍스트 명령 입력 + 마이크 버튼 (마이크는 PHASE 3에서 활성화). */
@Composable
fun CommandInputBar(
    microphoneAvailable: Boolean,
    busy: Boolean,
    placeholder: String = "명령을 입력하세요",
    onSubmit: (String) -> Unit,
    onMicrophone: () -> Unit,
) {
    val input = rememberTextFieldState()
    val send = {
        val text = input.text.toString()
        if (!busy && text.isNotBlank()) {
            onSubmit(text)
            input.clearText()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .background(JarvisColors.Panel, RoundedCornerShape(50))
                .border(1.dp, JarvisColors.PanelBorder, RoundedCornerShape(50))
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ChevronRight, null, tint = JarvisColors.Cyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                if (input.text.isEmpty()) Text(placeholder, style = JarvisType.body(15.sp, color = JarvisColors.TextDim))
                BasicTextField(
                    state = input,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    textStyle = JarvisType.body(15.sp),
                    cursorBrush = SolidColor(JarvisColors.Cyan),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    onKeyboardAction = { send() },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "명령 입력" },
                )
            }
            if (input.text.isNotEmpty()) {
                IconButton(onClick = send, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "보내기", tint = JarvisColors.Cyan)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        IconButton(
            onClick = onMicrophone,
            modifier = Modifier
                .size(52.dp)
                .background(if (microphoneAvailable) JarvisColors.Cyan else JarvisColors.Panel, CircleShape)
                .border(1.dp, JarvisColors.PanelBorder, CircleShape),
        ) {
            Icon(
                if (microphoneAvailable) Icons.Outlined.Mic else Icons.Outlined.MicOff,
                contentDescription = if (microphoneAvailable) "음성 명령 시작" else "음성 명령 (PHASE 3 예정)",
                tint = if (microphoneAvailable) Color.Black else JarvisColors.TextDim,
            )
        }
    }
}
