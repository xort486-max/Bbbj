import SwiftUI

/// 텍스트 명령 입력 + 마이크 버튼.
///
/// 마이크 버튼은 PHASE 3(음성 시스템)에서 활성화된다. 그 전에는 비활성 상태로 표시한다.
struct CommandInputBar: View {
    @Binding var text: String
    var isMicrophoneAvailable: Bool
    var isBusy: Bool
    var onSubmit: () -> Void
    var onMicrophone: () -> Void

    @FocusState private var isFocused: Bool

    var body: some View {
        HStack(spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "chevron.right")
                    .font(JarvisTheme.hud(12, weight: .bold))
                    .foregroundStyle(JarvisTheme.cyan)
                TextField(
                    "",
                    text: $text,
                    prompt: Text("명령을 입력하세요").foregroundStyle(JarvisTheme.textDim)
                )
                .font(JarvisTheme.body(15))
                .foregroundStyle(JarvisTheme.textPrimary)
                .tint(JarvisTheme.cyan)
                .focused($isFocused)
                .submitLabel(.send)
                .onSubmit(submit)

                if !text.isEmpty {
                    Button(action: submit) {
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.system(size: 24))
                            .foregroundStyle(JarvisTheme.cyan)
                    }
                    .disabled(isBusy)
                    .accessibilityLabel("보내기")
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(Capsule().fill(JarvisTheme.panel))
            .overlay(Capsule().strokeBorder(isFocused ? JarvisTheme.cyan.opacity(0.7) : JarvisTheme.panelBorder))

            Button(action: onMicrophone) {
                Image(systemName: isMicrophoneAvailable ? "mic.fill" : "mic.slash.fill")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(isMicrophoneAvailable ? Color.black : JarvisTheme.textDim)
                    .frame(width: 48, height: 48)
                    .background(
                        Circle().fill(isMicrophoneAvailable ? JarvisTheme.cyan : JarvisTheme.panel)
                    )
                    .overlay(Circle().strokeBorder(JarvisTheme.panelBorder))
            }
            .disabled(!isMicrophoneAvailable)
            .accessibilityLabel(isMicrophoneAvailable ? "음성 명령 시작" : "음성 명령 (PHASE 3 예정)")
        }
    }

    private func submit() {
        guard !isBusy else { return }
        onSubmit()
    }
}
