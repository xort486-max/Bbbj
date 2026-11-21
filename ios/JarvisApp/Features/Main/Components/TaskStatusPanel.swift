import SwiftUI
import JarvisCore

/// 현재 실행 중인 작업 표시.
struct TaskStatusPanel: View {
    let tasks: [JarvisTask]
    let state: JarvisState
    var onCancel: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                HUDSectionTitle(title: "// CURRENT TASK")
                if state.isBusy, let onCancel {
                    Button("중단", role: .destructive, action: onCancel)
                        .font(JarvisTheme.hud(10, weight: .semibold))
                        .buttonStyle(.bordered)
                        .tint(JarvisTheme.failure)
                        .controlSize(.mini)
                }
            }

            if tasks.isEmpty {
                Text(state.isBusy ? state.displayName : "실행 중인 작업 없음")
                    .font(JarvisTheme.body(13))
                    .foregroundStyle(JarvisTheme.textDim)
            } else {
                ForEach(tasks) { task in
                    TaskRow(task: task)
                }
            }
        }
        .hudPanel(padding: 10)
    }
}

private struct TaskRow: View {
    let task: JarvisTask

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                statusIcon
                Text(task.title)
                    .font(JarvisTheme.body(13, weight: .medium))
                    .foregroundStyle(JarvisTheme.textPrimary)
                    .lineLimit(1)
                Spacer(minLength: 4)
                if let engine = task.engine {
                    Text(engine.displayName)
                        .font(JarvisTheme.hud(9, weight: .bold))
                        .foregroundStyle(JarvisTheme.cyan)
                }
                Text(task.status.displayName)
                    .font(JarvisTheme.hud(9))
                    .foregroundStyle(JarvisTheme.textSecondary)
            }
            if task.status == .running {
                if let progress = task.progress {
                    ProgressView(value: progress).tint(JarvisTheme.cyan)
                } else {
                    ProgressView().progressViewStyle(.linear).tint(JarvisTheme.cyan)
                }
            }
            if let detail = task.detail {
                Text(detail)
                    .font(JarvisTheme.body(11))
                    .foregroundStyle(JarvisTheme.textDim)
                    .lineLimit(2)
            }
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var statusIcon: some View {
        switch task.status {
        case .pending:
            Image(systemName: "clock").foregroundStyle(JarvisTheme.textDim)
        case .running:
            Image(systemName: "bolt.horizontal.circle").foregroundStyle(JarvisTheme.cyan)
        case .succeeded:
            Image(systemName: "checkmark.circle").foregroundStyle(JarvisTheme.ok)
        case .failed:
            Image(systemName: "exclamationmark.triangle").foregroundStyle(JarvisTheme.failure)
        case .cancelled:
            Image(systemName: "xmark.circle").foregroundStyle(JarvisTheme.textDim)
        }
    }
}
