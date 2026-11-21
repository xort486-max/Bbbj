import SwiftUI
import JarvisCore

/// AI 엔진 연결 상태 + 백엔드 상태 + API 사용량 표시.
struct EngineStatusStrip: View {
    let backend: EngineConnectionStatus
    let engines: [AIEngineKind: EngineConnectionStatus]
    let activeEngine: AIEngineKind?
    var onRefresh: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HUDSectionTitle(title: "// AI ENGINES", trailing: "BACKEND · \(backend.displayName)")

            HStack(spacing: 8) {
                ForEach(AIEngineKind.remoteEngines) { engine in
                    EngineChip(
                        engine: engine,
                        status: engines[engine] ?? .unknown,
                        isActive: activeEngine == engine
                    )
                }
                Button(action: onRefresh) {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(JarvisTheme.cyan)
                        .frame(width: 30, height: 30)
                }
                .accessibilityLabel("엔진 상태 새로고침")
                .disabled(backend == .checking)
            }

            // API 사용량: 사용량 추적은 PHASE 2(요청 기록) · PHASE 7(대시보드)에서 구현.
            HStack(spacing: 6) {
                Image(systemName: "chart.bar.xaxis")
                    .font(.system(size: 10))
                Text("API 사용량 · 사용량 추적은 AI 연결(PHASE 2) 후 표시됩니다")
                    .font(JarvisTheme.hud(9))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .foregroundStyle(JarvisTheme.textDim)

            if let detail = backend.detail {
                Text(detail)
                    .font(JarvisTheme.body(11))
                    .foregroundStyle(JarvisTheme.failure)
                    .lineLimit(2)
            }
        }
        .hudPanel(padding: 10)
    }
}

private struct EngineChip: View {
    let engine: AIEngineKind
    let status: EngineConnectionStatus
    let isActive: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 5) {
                Circle()
                    .fill(JarvisTheme.color(for: status.level))
                    .frame(width: 6, height: 6)
                Text(engine.displayName)
                    .font(JarvisTheme.hud(10, weight: .bold))
                    .foregroundStyle(JarvisTheme.textPrimary)
            }
            Text(status.displayName)
                .font(JarvisTheme.hud(9))
                .foregroundStyle(JarvisTheme.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(isActive ? JarvisTheme.cyan.opacity(0.14) : Color.black.opacity(0.25))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .strokeBorder(isActive ? JarvisTheme.cyan : JarvisTheme.panelBorder, lineWidth: isActive ? 1.2 : 0.8)
        )
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(engine.displayName) \(engine.roleDescription), \(status.displayName)")
    }
}
