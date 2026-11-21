import SwiftUI

/// 전체 배경: 블랙→네이비 그라디언트 + 은은한 격자 + 상단 비네트.
struct HUDBackgroundView: View {
    var body: some View {
        ZStack {
            JarvisTheme.backgroundGradient

            Canvas { context, size in
                let spacing: CGFloat = 28
                var grid = Path()
                var x: CGFloat = 0
                while x <= size.width {
                    grid.move(to: CGPoint(x: x, y: 0))
                    grid.addLine(to: CGPoint(x: x, y: size.height))
                    x += spacing
                }
                var y: CGFloat = 0
                while y <= size.height {
                    grid.move(to: CGPoint(x: 0, y: y))
                    grid.addLine(to: CGPoint(x: size.width, y: y))
                    y += spacing
                }
                context.stroke(grid, with: .color(JarvisTheme.cyan.opacity(0.045)), lineWidth: 0.5)
            }

            RadialGradient(
                colors: [JarvisTheme.cyan.opacity(0.10), .clear],
                center: .init(x: 0.5, y: 0.32),
                startRadius: 10,
                endRadius: 380
            )
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }
}
