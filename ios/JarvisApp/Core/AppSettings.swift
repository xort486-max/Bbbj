import Foundation

/// 비밀이 아닌 앱 설정 (UserDefaults). 비밀값(토큰)은 KeychainTokenStore에 저장한다.
enum AppSettingsKey {
    static let backendURL = "jarvis.backendURL"
}

extension UserDefaults {
    var jarvisBackendURL: String {
        get { string(forKey: AppSettingsKey.backendURL) ?? "" }
        set { set(newValue, forKey: AppSettingsKey.backendURL) }
    }
}
