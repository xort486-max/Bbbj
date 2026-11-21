import SwiftUI
import JarvisCore

/// 빠른 기능 실행 메뉴.
///
/// 구현되지 않은 기능은 비활성화하고 예정 단계를 표시한다 — 동작하는 것처럼 보이지 않게.
struct QuickActionsBar: View {
    var onSelect: (JarvisFeature) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(JarvisFeature.allCases) { feature in
                    Button {
                        onSelect(feature)
                    } label: {
                        VStack(spacing: 4) {
                            Image(systemName: feature.symbolName)
                                .font(.system(size: 16, weight: .medium))
                            Text(feature.title)
                                .font(JarvisTheme.body(11, weight: .medium))
                            Text(feature.isImplemented ? "READY" : "P\(feature.plannedPhase)")
                                .font(JarvisTheme.hud(8, weight: .bold))
                                .foregroundStyle(feature.isImplemented ? JarvisTheme.ok : JarvisTheme.textDim)
                        }
                        .foregroundStyle(feature.isImplemented ? JarvisTheme.cyan : JarvisTheme.textSecondary.opacity(0.6))
                        .frame(width: 64, height: 64)
                        .background(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(JarvisTheme.panel)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .strokeBorder(JarvisTheme.panelBorder.opacity(feature.isImplemented ? 1 : 0.4))
                        )
                    }
                    .buttonStyle(.plain)
                    .disabled(!feature.isImplemented)
                    .accessibilityLabel("\(feature.title), \(feature.availabilityLabel)")
                }
            }
            .padding(.horizontal, 2)
        }
    }
}
