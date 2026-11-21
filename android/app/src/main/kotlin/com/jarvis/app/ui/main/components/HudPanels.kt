package com.jarvis.app.ui.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.app.ui.main.MicrophoneStatus
import com.jarvis.app.ui.theme.JarvisColors
import com.jarvis.app.ui.theme.JarvisType
import com.jarvis.app.ui.theme.hudPanel
import com.jarvis.core.model.AiEngine
import com.jarvis.core.model.ConnectionStatus
import com.jarvis.core.model.JarvisTask
import com.jarvis.core.state.JarvisState

/** 패널 제목 (예: "// CURRENT TASK"). */
@Composable
fun HudSectionTitle(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 3.dp, height = 10.dp).background(JarvisColors.Cyan))
        Spacer(Modifier.width(6.dp))
        Text(title, style = JarvisType.hud(10.sp, FontWeight.SemiBold), letterSpacing = 1.5.sp)
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = JarvisType.hud(10.sp, color = JarvisColors.TextDim), maxLines = 1)
        }
    }
}

/** 상단 헤더: 타이틀, 작동 상태, 마이크 상태, 설정 버튼. */
@Composable
fun StatusHeader(state: JarvisState, microphone: MicrophoneStatus, onSettings: () -> Unit) {
    val stateColor = JarvisColors.forState(state)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) { contentDescription = "작동 상태: ${state.displayName}" },
        ) {
            Text(
                "J.A.R.V.I.S.",
                style = JarvisType.hud(18.sp, FontWeight.Bold, JarvisColors.TextPrimary),
                letterSpacing = 3.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .shadow(4.dp, CircleShape, ambientColor = stateColor, spotColor = stateColor)
                        .background(stateColor, CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text(state.hudCode, style = JarvisType.hud(10.sp, FontWeight.SemiBold, stateColor), letterSpacing = 1.2.sp)
            }
        }
        MicrophoneIndicator(microphone)
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onSettings,
            modifier = Modifier
                .size(40.dp)
                .background(JarvisColors.Panel, CircleShape)
                .border(1.dp, JarvisColors.PanelBorder, CircleShape),
        ) {
            Icon(Icons.Outlined.Settings, contentDescription = "설정", tint = JarvisColors.TextSecondary)
        }
    }
}

/** 마이크 사용 여부 표시. 마이크가 켜져 있으면 반드시 눈에 띄게 표시한다. */
@Composable
fun MicrophoneIndicator(status: MicrophoneStatus) {
    val live = status == MicrophoneStatus.LIVE
    val fg = if (live) JarvisColors.Failure else JarvisColors.TextDim
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .background(if (live) JarvisColors.Failure.copy(alpha = 0.15f) else JarvisColors.Panel, shape)
            .border(1.dp, fg.copy(alpha = 0.5f), shape)
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .clearAndSetSemantics { contentDescription = status.description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (live) Icons.Outlined.Mic else Icons.Outlined.MicOff, null, tint = fg, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(status.label, style = JarvisType.hud(9.sp, FontWeight.SemiBold, fg))
    }
}

/** AI 엔진 연결 상태 + 백엔드 상태 + API 사용량 영역. */
@Composable
fun EngineStatusStrip(
    backend: ConnectionStatus,
    engines: Map<AiEngine, ConnectionStatus>,
    activeEngine: AiEngine?,
    onRefresh: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().hudPanel(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HudSectionTitle("// AI ENGINES", trailing = "BACKEND · ${backend.displayName}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AiEngine.remoteEngines.forEach { engine ->
                EngineChip(engine, engines[engine] ?: ConnectionStatus.Unknown, engine == activeEngine, Modifier.weight(1f))
            }
            IconButton(onClick = onRefresh, enabled = backend != ConnectionStatus.Checking, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Refresh, contentDescription = "엔진 상태 새로고침", tint = JarvisColors.Cyan)
            }
        }
        // API 사용량: 요청 기록은 PHASE 2, 대시보드는 PHASE 7에서 구현.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.BarChart, null, tint = JarvisColors.TextDim, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                "API 사용량 · AI 연결(PHASE 2) 후 표시됩니다",
                style = JarvisType.hud(9.sp, color = JarvisColors.TextDim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (backend is ConnectionStatus.Unreachable) {
            Text(backend.reason, style = JarvisType.body(11.sp, color = JarvisColors.Failure), maxLines = 2)
        }
    }
}

@Composable
private fun EngineChip(engine: AiEngine, status: ConnectionStatus, active: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .background(if (active) JarvisColors.Cyan.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.25f), shape)
            .border(if (active) 1.2.dp else 0.8.dp, if (active) JarvisColors.Cyan else JarvisColors.PanelBorder, shape)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clearAndSetSemantics { contentDescription = "${engine.displayName} ${engine.roleDescription}, ${status.displayName}" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(JarvisColors.forLevel(status.level), CircleShape))
            Spacer(Modifier.width(5.dp))
            Text(engine.displayName, style = JarvisType.hud(10.sp, FontWeight.Bold, JarvisColors.TextPrimary))
        }
        Text(status.displayName, style = JarvisType.hud(9.sp))
    }
}

/** 현재 실행 중인 작업 표시. */
@Composable
fun TaskStatusPanel(tasks: List<JarvisTask>, state: JarvisState, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().hudPanel(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudSectionTitle("// CURRENT TASK", Modifier.weight(1f))
            if (state.isBusy) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.height(28.dp),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisColors.Failure),
                ) { Text("중단", style = JarvisType.hud(10.sp, FontWeight.SemiBold, JarvisColors.Failure)) }
            }
        }
        if (tasks.isEmpty()) {
            Text(
                if (state.isBusy) state.displayName else "실행 중인 작업 없음",
                style = JarvisType.body(13.sp, color = JarvisColors.TextDim),
            )
        } else {
            tasks.forEach { TaskRow(it) }
        }
    }
}

@Composable
private fun TaskRow(task: JarvisTask) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (icon, tint) = when (task.status) {
                JarvisTask.Status.PENDING -> Icons.Outlined.Schedule to JarvisColors.TextDim
                JarvisTask.Status.RUNNING -> Icons.Outlined.ElectricBolt to JarvisColors.Cyan
                JarvisTask.Status.SUCCEEDED -> Icons.Outlined.CheckCircle to JarvisColors.Ok
                JarvisTask.Status.FAILED -> Icons.Outlined.WarningAmber to JarvisColors.Failure
                JarvisTask.Status.CANCELLED -> Icons.Outlined.Cancel to JarvisColors.TextDim
            }
            Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                task.title,
                style = JarvisType.body(13.sp, FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            task.engine?.let {
                Text(it.displayName, style = JarvisType.hud(9.sp, FontWeight.Bold, JarvisColors.Cyan))
                Spacer(Modifier.width(6.dp))
            }
            Text(task.status.displayName, style = JarvisType.hud(9.sp))
        }
        if (task.status == JarvisTask.Status.RUNNING) {
            val progress = task.progress
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    color = JarvisColors.Cyan,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp), color = JarvisColors.Cyan)
            }
        }
        task.detail?.let {
            Text(it, style = JarvisType.body(11.sp, color = JarvisColors.TextDim), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
