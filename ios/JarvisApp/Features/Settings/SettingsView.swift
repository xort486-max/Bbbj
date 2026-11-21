import SwiftUI
import JarvisCore

/// 앱 설정: 백엔드 주소, 앱 인증 토큰(Keychain), 디자인 점검(DEBUG).
struct SettingsView: View {
    let model: MainViewModel

    @Environment(\.dismiss) private var dismiss
    @AppStorage(AppSettingsKey.backendURL) private var backendURL = ""
    @State private var tokenInput = ""
    @State private var hasStoredToken = false
    @State private var message: String?
    @State private var messageIsError = false

    private let tokenStore = KeychainTokenStore()

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("https://jarvis.example.com", text: $backendURL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .font(JarvisTheme.hud(14))
                    if let validation = urlValidationMessage {
                        Text(validation)
                            .font(JarvisTheme.body(12))
                            .foregroundStyle(JarvisTheme.warning)
                    }
                } header: {
                    Text("백엔드 서버")
                } footer: {
                    Text("AI API Key는 앱에 저장하지 않습니다. 앱은 이 서버를 통해서만 NVIDIA · Google · Claude에 접근합니다. 개발용 로컬 서버(localhost, 사설 IP)만 HTTP를 허용합니다.")
                }

                Section {
                    SecureField(hasStoredToken ? "저장됨 — 변경하려면 새 토큰 입력" : "앱 인증 토큰", text: $tokenInput)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    HStack {
                        Button("토큰 저장") { saveToken() }
                            .disabled(tokenInput.trimmingCharacters(in: .whitespaces).isEmpty)
                        Spacer()
                        Button("토큰 삭제", role: .destructive) { deleteToken() }
                            .disabled(!hasStoredToken)
                    }
                    .buttonStyle(.borderless)
                } header: {
                    Text("앱 인증 토큰")
                } footer: {
                    Text("백엔드의 JARVIS_APP_TOKEN 값과 같아야 합니다. iOS Keychain(이 기기 전용)에 저장됩니다.")
                }

                Section("연결") {
                    Button {
                        Task { await testConnection() }
                    } label: {
                        Label("연결 테스트", systemImage: "antenna.radiowaves.left.and.right")
                    }
                    LabeledContent("백엔드", value: model.backendStatus.displayName)
                    ForEach(AIEngineKind.remoteEngines) { engine in
                        LabeledContent("\(engine.displayName) · \(engine.roleDescription)",
                                       value: (model.engineStatuses[engine] ?? .unknown).displayName)
                    }
                    if let message {
                        Text(message)
                            .font(JarvisTheme.body(12))
                            .foregroundStyle(messageIsError ? JarvisTheme.failure : JarvisTheme.ok)
                    }
                }

                #if DEBUG
                Section {
                    ForEach(PreviewData.allStates, id: \.displayName) { state in
                        Button(state.displayName) {
                            model.debugForceState(state)
                        }
                    }
                    Button("예시 대화 추가") { model.debugSeedConversation() }
                    Button("대화 기록 지우기", role: .destructive) { model.clearConversation() }
                } header: {
                    Text("디자인 점검 (DEBUG 빌드 전용)")
                } footer: {
                    Text("화면 상태 표시를 확인하기 위한 도구입니다. 실제 AI 처리가 아닙니다.")
                }
                #endif

                Section("정보") {
                    LabeledContent("버전", value: Bundle.main.appVersionString)
                    LabeledContent("개발 단계", value: "PHASE 1 — 기본 프로젝트")
                }
            }
            .scrollContentBackground(.hidden)
            .background(JarvisTheme.backgroundGradient.ignoresSafeArea())
            .navigationTitle("설정")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("완료") { dismiss() }
                }
            }
        }
        .preferredColorScheme(.dark)
        .tint(JarvisTheme.cyan)
        .onAppear { hasStoredToken = tokenStore.hasToken }
    }

    private var urlValidationMessage: String? {
        guard !backendURL.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        do {
            _ = try BackendConfiguration.validated(backendURL)
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    private func saveToken() {
        do {
            try tokenStore.save(tokenInput.trimmingCharacters(in: .whitespacesAndNewlines))
            tokenInput = ""
            hasStoredToken = true
            show("토큰을 Keychain에 저장했습니다.", isError: false)
        } catch {
            show(error.localizedDescription, isError: true)
        }
    }

    private func deleteToken() {
        do {
            try tokenStore.delete()
            hasStoredToken = false
            show("토큰을 삭제했습니다.", isError: false)
        } catch {
            show(error.localizedDescription, isError: true)
        }
    }

    private func testConnection() async {
        await model.refreshEngineStatus()
        switch model.backendStatus {
        case .connected:
            show("백엔드 연결 성공", isError: false)
        case .notConfigured:
            show("백엔드 주소를 입력하세요.", isError: true)
        case let .unreachable(reason):
            show(reason, isError: true)
        default:
            break
        }
    }

    private func show(_ text: String, isError: Bool) {
        message = text
        messageIsError = isError
    }
}

extension Bundle {
    var appVersionString: String {
        let version = object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let build = object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(version) (\(build))"
    }
}
