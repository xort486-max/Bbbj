import SwiftUI
import JarvisCore

/// 음성 파형 애니메이션.
///
/// `level`(0...1)이 0이면 아주 낮은 대기 파형만 표시한다. PHASE 3에서 실제 마이크 입력
/// (AVAudioEngine 탭의 RMS)과 TTS 출력 레벨을 `level`로 전달한다.
struct WaveformView: View {
    let state: JarvisState
    var level: Double = 0
    var barCount: Int = 44

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: reduceMotion)) { timeline in
            let time = timeline.date.timeIntervalSinceReferenceDate
            Canvas { context, size in
                draw(in: &context, size: size, time: reduceMotion ? 0 : time)
            }
        }
        .accessibilityHidden(true)
    }

    private func draw(in context: inout GraphicsContext, size: CGSize, time: TimeInterval) {
        let color = JarvisTheme.color(for: state)
        let clamped = min(max(level, 0), 1)
        // 대기 상태에서는 거의 평평한 파형. 처리 중 상태에서는 약한 활동 파형.
        let baseAmplitude: Double = state.isBusy ? 0.12 : 0.04
        let amplitude = baseAmplitude + clamped * 0.85
        let speed = 2.0 + JarvisTheme.energy(for: state) * 3.0

        let spacing = size.width / CGFloat(barCount)
        let barWidth = max(spacing * 0.45, 1.5)
        let midY = size.height / 2

        for index in 0..<barCount {
            let position = Double(index) / Double(barCount - 1)
            // 가운데가 높고 양 끝이 낮은 포락선
            let envelope = sin(position * .pi)
            let wave = 0.55 * sin(time * speed + Double(index) * 0.42)
                + 0.30 * sin(time * speed * 1.7 + Double(index) * 0.19)
                + 0.15 * sin(time * speed * 0.6 - Double(index) * 0.77)
            let normalized = (abs(wave) * 0.8 + 0.2) * envelope * amplitude
            let height = max(CGFloat(normalized) * size.height, 2)

            let rect = CGRect(
                x: CGFloat(index) * spacing + (spacing - barWidth) / 2,
                y: midY - height / 2,
                width: barWidth,
                height: height
            )
            let opacity = 0.35 + 0.65 * envelope
            context.fill(
                Path(roundedRect: rect, cornerRadius: barWidth / 2),
                with: .color(color.opacity(opacity))
            )
        }
    }
}

#Preview {
    VStack(spacing: 24) {
        WaveformView(state: .idle).frame(height: 44)
        WaveformView(state: .listening, level: 0.7).frame(height: 44)
        WaveformView(state: .claudeReasoning).frame(height: 44)
    }
    .padding()
    .background(HUDBackgroundView())
}
