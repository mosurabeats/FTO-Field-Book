import Foundation
import LocalAuthentication
import Security
import SwiftUI

enum Keychain {
    private static let service = "com.mosurabeats.fieldbook"

    static func get(_ account: String) -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: account, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    static func set(_ account: String, _ value: String?) {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
        SecItemDelete(base as CFDictionary)
        guard let v = value, !v.isEmpty else { return }
        var add = base
        add[kSecValueData as String] = Data(v.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }
}

/// Face ID / passcode lock for the trainee records.
final class LockManager: ObservableObject {
    @AppStorage("lockEnabled") var enabled = false
    @Published var locked = false
    @Published var lastError: String? = nil
    private var unlockedAt: Date? = nil

    init() { locked = UserDefaults.standard.bool(forKey: "lockEnabled") }

    var biometryName: String {
        let c = LAContext(); _ = c.canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
        switch c.biometryType { case .faceID: return "Face ID"; case .touchID: return "Touch ID"; case .opticID: return "Optic ID"; default: return "Passcode" }
    }

    func appWentToBackground() { if enabled { locked = true } }

    func unlock() {
        guard enabled else { locked = false; return }
        let c = LAContext()
        c.localizedCancelTitle = "Cancel"
        var err: NSError?
        guard c.canEvaluatePolicy(.deviceOwnerAuthentication, error: &err) else {
            // No passcode set on the phone: nothing to check against.
            locked = false; return
        }
        c.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Unlock FieldBook to view trainee records.") { ok, e in
            DispatchQueue.main.async {
                if ok { self.locked = false; self.unlockedAt = Date(); self.lastError = nil }
                else { self.lastError = e?.localizedDescription }
            }
        }
    }

    func setEnabled(_ on: Bool) {
        if on {
            let c = LAContext()
            c.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Turn on the FieldBook lock.") { ok, _ in
                DispatchQueue.main.async { self.enabled = ok; self.locked = false }
            }
        } else {
            enabled = false; locked = false
        }
    }
}
