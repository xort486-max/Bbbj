import SwiftUI
import JarvisCore

/// 실시간 대화 기록. 새 메시지가 추가되면 맨 아래로 자동 스크롤한다.
struct ConversationLogView: View {
    let messages: [ConversationMessage]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HUDSectionTitle(title: "// CONVERSATION LOG", trailing: "\(messages.count)")

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        if messages.isEmpty {
                            Text("\"자비스\"라고 부르거나 아래에 명령을 입력하세요.")
                                .font(JarvisTheme.body(13))
                                .foregroundStyle(JarvisTheme.textDim)
                                .frame(maxWidth: .infinity, alignment: .center)
                                .padding(.vertical, 16)
                        }
                        ForEach(messages) { message in
                            MessageRow(message: message)
                                .id(message.id)
                        }
                    }
                    .padding(.vertical, 4)
                }
                .onChange(of: messages.last?.id) { _, lastID in
                    guard let lastID else { return }
                    withAnimation(.easeOut(duration: 0.25)) {
                        proxy.scrollTo(lastID, anchor: .bottom)
                    }
                }
            }
        }
        .hudPanel(padding: 10)
    }
}

private struct MessageRow: View {
    let message: ConversationMessage

    var body: some View {
        switch message.role {
        case .user:
            HStack {
                Spacer(minLength: 40)
                Text(message.text)
                    .font(JarvisTheme.body(14))
                    .foregroundStyle(JarvisTheme.textPrimary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(JarvisTheme.blue.opacity(0.28))
                    )
                    .textSelection(.enabled)
            }
            .accessibilityLabel("사용자: \(message.text)")

        case .assistant:
            VStack(alignment: .leading, spacing: 4) {
                Text("JARVIS" + (message.engine.map { " · \($0.displayName)" } ?? ""))
                    .font(JarvisTheme.hud(9, weight: .bold))
                    .foregroundStyle(JarvisTheme.cyan)
                Text(message.text)
                    .font(JarvisTheme.body(14))
                    .foregroundStyle(JarvisTheme.textPrimary)
                    .textSelection(.enabled)
            }
            .padding(.trailing, 32)
            .accessibilityElement(children: .combine)

        case .system:
            HStack(alignment: .top, spacing: 6) {
                Image(systemName: "info.circle")
                    .font(.system(size: 11))
                Text(message.text)
                    .font(JarvisTheme.body(12))
            }
            .foregroundStyle(JarvisTheme.warning.opacity(0.9))
            .padding(8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(JarvisTheme.warning.opacity(0.08))
            )
            .accessibilityElement(children: .combine)
        }
    }
}
