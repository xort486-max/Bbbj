import SwiftUI
import JarvisCore

/// 상단 헤더: 타이틀, 작동 상태, 마이크 상태, 설정 버튼.
struct StatusHeaderView: View {
    let state: JarvisState
    let microphone: MicrophoneStatus
    var onSettings: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("J.A.R.V.I.S.")
                    .font(JarvisTheme.hud(18, weight: .bold))
                    .tracking(3)
                    .foregroundStyle(JarvisTheme.textPrimary)
                HStack(spacing: 6) {
                    Circle()
                        .fill(JarvisTheme.color(for: state))
                        .frame(width: 6, height: 6)
                        .shadow(color: JarvisTheme.color(for: state), radius: 4)
                    Text(state.hudCode)
                        .font(JarvisTheme.hud(10, weight: .semibold))
                        .tracking(1.2)
                        .foregroundStyle(JarvisTheme.color(for: state))
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("작동 상태: \(state.displayName)")
            }

            Spacer()

            MicrophoneIndicatorView(status: microphone)

            Button(action: onSettings) {
                Image(systemName: "gearshape")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(JarvisTheme.textSecondary)
                    .frame(width: 36, height: 36)
                    .background(Circle().fill(JarvisTheme.panel))
                    .overlay(Circle().strokeBorder(JarvisTheme.panelBorder))
            }
            .accessibilityLabel("설정")
        }
    }
}

/// 마이크 사용 여부 표시. 마이크가 켜져 있으면 반드시 눈에 띄게 표시한다.
struct MicrophoneIndicatorView: View {
    let status: MicrophoneStatus

    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: status == .live ? "mic.fill" : "mic.slash")
                .font(.system(size: 11, weight: .semibold))
            Text(status.label)
                .font(JarvisTheme.hud(9, weight: .semibold))
        }
        .foregroundStyle(foreground)
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .background(Capsule().fill(background))
        .overlay(Capsule().strokeBorder(foreground.opacity(0.5)))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(status.accessibilityText)
    }

    private var foreground: Color {
        switch status {
        case .live: JarvisTheme.failure
        case .off, .unavailable: JarvisTheme.textDim
        }
    }

    private var background: Color {
        status == .live ? JarvisTheme.failure.opacity(0.15) : JarvisTheme.panel
    }
}
