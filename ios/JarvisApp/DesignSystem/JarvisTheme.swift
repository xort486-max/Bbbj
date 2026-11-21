import SwiftUI
import JarvisCore

/// JARVIS 디자인 시스템 — 블랙/네이비 배경, 시안/블루 포인트.
enum JarvisTheme {
    // MARK: 배경
    static let backgroundTop = Color(red: 0.01, green: 0.02, blue: 0.05)
    static let backgroundBottom = Color(red: 0.02, green: 0.07, blue: 0.16)
    static let panel = Color(red: 0.03, green: 0.10, blue: 0.20).opacity(0.55)
    static let panelBorder = Color(red: 0.25, green: 0.80, blue: 1.0).opacity(0.28)

    // MARK: 포인트 색상
    static let cyan = Color(red: 0.00, green: 0.85, blue: 1.00)
    static let blue = Color(red: 0.20, green: 0.55, blue: 1.00)
    static let ice = Color(red: 0.75, green: 0.95, blue: 1.00)
    static let textPrimary = Color(red: 0.88, green: 0.97, blue: 1.00)
    static let textSecondary = Color(red: 0.55, green: 0.72, blue: 0.85)
    static let textDim = Color(red: 0.35, green: 0.48, blue: 0.60)
    static let ok = Color(red: 0.30, green: 1.00, blue: 0.70)
    static let warning = Color(red: 1.00, green: 0.78, blue: 0.30)
    static let failure = Color(red: 1.00, green: 0.32, blue: 0.38)

    static var backgroundGradient: LinearGradient {
        LinearGradient(colors: [backgroundTop, backgroundBottom], startPoint: .top, endPoint: .bottom)
    }

    // MARK: 글꼴
    static func hud(_ size: CGFloat, weight: Font.Weight = .medium) -> Font {
        .system(size: size, weight: weight, design: .monospaced)
    }

    static func body(_ size: CGFloat = 15, weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .rounded)
    }

    // MARK: 상태별 색상

    static func color(for state: JarvisState) -> Color {
        switch state {
        case .idle: cyan
        case .listening: Color(red: 0.25, green: 1.00, blue: 0.85)
        case .analyzing: Color(red: 0.40, green: 0.65, blue: 1.00)
        case .nvidiaResponding: Color(red: 0.45, green: 1.00, blue: 0.65)
        case .googleSearching: Color(red: 0.30, green: 0.75, blue: 1.00)
        case .claudeReasoning: Color(red: 0.62, green: 0.55, blue: 1.00)
        case .executingTask: blue
        case .completed: ok
        case .error: failure
        }
    }

    static func color(for level: EngineConnectionStatus.Level) -> Color {
        switch level {
        case .neutral: textDim
        case .ok: ok
        case .warning: warning
        case .failure: failure
        }
    }

    /// 상태별 링 회전 속도 배율.
    static func energy(for state: JarvisState) -> Double {
        switch state {
        case .idle, .completed: 0.35
        case .error: 0.15
        case .listening: 0.8
        case .analyzing, .executingTask: 1.3
        case .nvidiaResponding, .googleSearching: 1.1
        case .claudeReasoning: 1.6
        }
    }
}

/// HUD 패널 스타일 (반투명 네이비 + 시안 테두리).
struct HUDPanel: ViewModifier {
    var padding: CGFloat = 12
    var cornerRadius: CGFloat = 14

    func body(content: Content) -> some View {
        content
            .padding(padding)
            .background(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(JarvisTheme.panel)
            )
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(JarvisTheme.panelBorder, lineWidth: 1)
            )
    }
}

extension View {
    func hudPanel(padding: CGFloat = 12, cornerRadius: CGFloat = 14) -> some View {
        modifier(HUDPanel(padding: padding, cornerRadius: cornerRadius))
    }
}

/// 패널 제목 (예: "// CURRENT TASK").
struct HUDSectionTitle: View {
    let title: String
    var trailing: String? = nil

    var body: some View {
        HStack(spacing: 6) {
            Rectangle()
                .fill(JarvisTheme.cyan)
                .frame(width: 3, height: 10)
            Text(title)
                .font(JarvisTheme.hud(10, weight: .semibold))
                .tracking(1.5)
                .foregroundStyle(JarvisTheme.textSecondary)
            Spacer(minLength: 0)
            if let trailing {
                Text(trailing)
                    .font(JarvisTheme.hud(10))
                    .foregroundStyle(JarvisTheme.textDim)
            }
        }
    }
}
