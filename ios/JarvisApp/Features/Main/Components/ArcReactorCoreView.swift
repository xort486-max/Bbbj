import SwiftUI
import JarvisCore

/// 중앙 AI 코어 — 상태에 따라 색상·회전 속도·맥동이 바뀌는 반응형 표시 장치.
///
/// `audioLevel`(0...1)은 PHASE 3에서 마이크 입력/TTS 출력 레벨과 연결된다.
struct ArcReactorCoreView: View {
    let state: JarvisState
    var audioLevel: Double = 0

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 60.0, paused: reduceMotion)) { timeline in
            let time = timeline.date.timeIntervalSinceReferenceDate
            Canvas { context, size in
                draw(in: &context, size: size, time: reduceMotion ? 0 : time)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityElement()
        .accessibilityLabel("JARVIS 코어")
        .accessibilityValue(state.displayName)
    }

    private func draw(in context: inout GraphicsContext, size: CGSize, time: TimeInterval) {
        let color = JarvisTheme.color(for: state)
        let energy = JarvisTheme.energy(for: state)
        let level = min(max(audioLevel, 0), 1)
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let radius = min(size.width, size.height) / 2
        let pulse = 0.5 + 0.5 * sin(time * (1.2 + energy * 1.5))

        // 1. 외곽 광채
        let glowRadius = radius * (0.95 + 0.04 * pulse + 0.08 * level)
        context.fill(
            Path(ellipseIn: CGRect(x: center.x - glowRadius, y: center.y - glowRadius,
                                   width: glowRadius * 2, height: glowRadius * 2)),
            with: .radialGradient(
                Gradient(colors: [color.opacity(0.28), color.opacity(0.06), .clear]),
                center: center, startRadius: radius * 0.2, endRadius: glowRadius
            )
        )

        // 2. 바깥 눈금 링 (천천히 회전)
        let tickCount = 72
        let tickRotation = time * 0.12 * energy
        var ticks = Path()
        for index in 0..<tickCount {
            let angle = Double(index) / Double(tickCount) * 2 * .pi + tickRotation
            let isMajor = index % 6 == 0
            let inner = radius * (isMajor ? 0.84 : 0.87)
            let outer = radius * 0.91
            ticks.move(to: point(center, inner, angle))
            ticks.addLine(to: point(center, outer, angle))
        }
        context.stroke(ticks, with: .color(color.opacity(0.55)), lineWidth: 1.2)

        // 3. 분할 호 링 (반대 방향 회전, 글로우)
        let arcRotation = -time * 0.55 * energy
        context.drawLayer { layer in
            layer.addFilter(.blur(radius: 6))
            strokeSegments(in: &layer, center: center, radius: radius * 0.76, count: 3,
                           sweep: 0.9, rotation: arcRotation, color: color.opacity(0.9), width: 6)
        }
        strokeSegments(in: &context, center: center, radius: radius * 0.76, count: 3,
                       sweep: 0.9, rotation: arcRotation, color: color, width: 3)

        // 4. 안쪽 링 (빠른 회전 짧은 호)
        strokeSegments(in: &context, center: center, radius: radius * 0.63, count: 6,
                       sweep: 0.35, rotation: time * 0.9 * energy, color: color.opacity(0.7), width: 2)
        context.stroke(
            Path(ellipseIn: CGRect(x: center.x - radius * 0.56, y: center.y - radius * 0.56,
                                   width: radius * 1.12, height: radius * 1.12)),
            with: .color(color.opacity(0.25)), lineWidth: 1
        )

        // 5. 중심 코어 (맥동 + 오디오 레벨)
        let coreRadius = radius * (0.30 + 0.025 * pulse + 0.10 * level)
        context.drawLayer { layer in
            layer.addFilter(.blur(radius: 14))
            layer.fill(
                Path(ellipseIn: CGRect(x: center.x - coreRadius, y: center.y - coreRadius,
                                       width: coreRadius * 2, height: coreRadius * 2)),
                with: .color(color.opacity(0.65))
            )
        }
        context.fill(
            Path(ellipseIn: CGRect(x: center.x - coreRadius, y: center.y - coreRadius,
                                   width: coreRadius * 2, height: coreRadius * 2)),
            with: .radialGradient(
                Gradient(colors: [Color.white.opacity(0.95), color.opacity(0.85), color.opacity(0.15)]),
                center: center, startRadius: 0, endRadius: coreRadius
            )
        )

        // 6. 코어 내부 삼각 구조 (아크 리액터 모티프)
        var triangle = Path()
        let triRadius = coreRadius * 0.62
        let triRotation = -.pi / 2 + time * 0.2 * energy
        for index in 0..<3 {
            let angle = triRotation + Double(index) * 2 * .pi / 3
            let p = point(center, triRadius, angle)
            if index == 0 { triangle.move(to: p) } else { triangle.addLine(to: p) }
        }
        triangle.closeSubpath()
        context.stroke(triangle, with: .color(Color.white.opacity(0.55)), lineWidth: 1.5)
    }

    private func strokeSegments(
        in context: inout GraphicsContext,
        center: CGPoint, radius: CGFloat, count: Int,
        sweep: Double, rotation: Double, color: Color, width: CGFloat
    ) {
        let step = 2 * Double.pi / Double(count)
        var path = Path()
        for index in 0..<count {
            let start = Double(index) * step + rotation
            path.addArc(center: center, radius: radius,
                        startAngle: .radians(start), endAngle: .radians(start + step * sweep),
                        clockwise: false)
        }
        context.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: width, lineCap: .round))
    }

    private func point(_ center: CGPoint, _ radius: CGFloat, _ angle: Double) -> CGPoint {
        CGPoint(x: center.x + radius * CGFloat(cos(angle)), y: center.y + radius * CGFloat(sin(angle)))
    }
}

#if DEBUG
#Preview("Core states") {
    ScrollView {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150))], spacing: 16) {
            ForEach(PreviewData.allStates, id: \.displayName) { state in
                VStack {
                    ArcReactorCoreView(state: state, audioLevel: state == .listening ? 0.6 : 0)
                        .frame(width: 140, height: 140)
                    Text(state.displayName)
                        .font(JarvisTheme.hud(11))
                        .foregroundStyle(JarvisTheme.textSecondary)
                }
            }
        }
        .padding()
    }
    .background(HUDBackgroundView())
}
#endif
