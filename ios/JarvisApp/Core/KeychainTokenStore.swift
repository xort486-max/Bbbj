import Foundation
import Security
import JarvisCore

/// 백엔드 사용자 인증 토큰을 iOS Keychain에 저장한다.
///
/// AI 제공자(NVIDIA/Google/Claude) API Key는 앱에 저장하지 않는다. 여기 저장되는 것은
/// JARVIS 백엔드가 발급/설정한 앱 전용 토큰뿐이다.
struct KeychainTokenStore: AuthTokenProviding {
    enum KeychainError: Error, LocalizedError {
        case unexpectedStatus(OSStatus)

        var errorDescription: String? {
            switch self {
            case let .unexpectedStatus(status):
                let message = SecCopyErrorMessageString(status, nil) as String? ?? "알 수 없는 오류"
                return "Keychain 오류 (\(status)): \(message)"
            }
        }
    }

    let service: String
    let account: String

    init(service: String = Bundle.main.bundleIdentifier ?? "JARVIS", account: String = "backend-app-token") {
        self.service = service
        self.account = account
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    func currentToken() -> String? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    var hasToken: Bool { currentToken()?.isEmpty == false }

    func save(_ token: String) throws {
        let data = Data(token.utf8)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            // 기기 잠금 해제 후 접근 가능, 백업/다른 기기로 이전되지 않음
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let updateStatus = SecItemUpdate(baseQuery as CFDictionary, attributes as CFDictionary)
        switch updateStatus {
        case errSecSuccess:
            return
        case errSecItemNotFound:
            var query = baseQuery
            query.merge(attributes) { _, new in new }
            let addStatus = SecItemAdd(query as CFDictionary, nil)
            guard addStatus == errSecSuccess else { throw KeychainError.unexpectedStatus(addStatus) }
        default:
            throw KeychainError.unexpectedStatus(updateStatus)
        }
    }

    func delete() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError.unexpectedStatus(status)
        }
    }
}
