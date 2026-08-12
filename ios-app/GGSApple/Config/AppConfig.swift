import Foundation

/// Central runtime config for Instant (Customer + Expert modes on m2m).
enum AppConfig {
    // MARK: - Supabase (cloud demo — same as Android)

    static let supabaseURL = URL(string: "https://suuellchcoegerddqyjb.supabase.co")!
    static let supabaseAnonKey =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InN1dWVsbGNoY29lZ2VyZGRxeWpiIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODE2NzAyMjAsImV4cCI6MjA5NzI0NjIyMH0.uV04X1_SPMXWdzvHJBv1CDBrys3RfQOHsAhzD3SBUeU"

    /// Cross-platform deep link — must match Android + Supabase redirect URLs.
    static let authCallbackURL = URL(string: "remotear://auth-callback")!
    static let authCallbackScheme = "remotear"

    // MARK: - Google OAuth (iOS client)

    static let googleIOSClientID =
        "664161950009-jik3hu1hkgfosc83i4v3oplporpbk0br.apps.googleusercontent.com"
    static let googleReversedClientID =
        "com.googleusercontent.apps.664161950009-jik3hu1hkgfosc83i4v3oplporpbk0br"

    // MARK: - Defaults (m2m lab — anas-imaclab; bump configEpoch when URLs change)

    /// Epoch 6 = iMac Lab Express API + LiveKit for m2m native expert testing.
    static let configEpoch = 6

    /// Express API on anas-imaclab Tailscale (Mac Mini offline during m2m).
    static let defaultAPIURL = URL(string: "http://100.83.95.8:3000")!
    /// LiveKit on anas-imaclab — phone must be on Tailscale; Simulator can use host/Tailscale.
    static let defaultLiveKitURL = "ws://100.83.95.8:7880"

    // MARK: - Product

    static let appDisplayName = "AR Assist"
    /// Legacy web expert URL (not required for m2m native switch).
    static let expertWebURL = URL(string: "http://100.83.95.8:3000")!
    static let isPremium = false
}

/// UserDefaults overrides for debug backend URL sheet.
@MainActor
enum RuntimeConfig {
    private static let apiKey = "runtime.apiURL"
    private static let liveKitKey = "runtime.liveKitURL"
    private static let epochKey = "runtime.configEpoch"
    private static let appModeKey = "runtime.appMode"

    /// Call once at launch so phones drop stale Mac Mini / Homelab Debug overrides for m2m lab.
    static func migrateIfNeeded() {
        let stored = UserDefaults.standard.integer(forKey: epochKey)
        if stored == AppConfig.configEpoch {
            return
        }
        clearOverrides()
        UserDefaults.standard.set(AppConfig.configEpoch, forKey: epochKey)
        print("[Config] migrated to epoch=\(AppConfig.configEpoch) api=\(apiURL.absoluteString) livekit=\(liveKitURL)")
    }

    static var apiURL: URL {
        if let raw = UserDefaults.standard.string(forKey: apiKey),
           let url = URL(string: raw), !raw.isEmpty {
            return url
        }
        return AppConfig.defaultAPIURL
    }

    static var liveKitURL: String {
        let raw = UserDefaults.standard.string(forKey: liveKitKey) ?? ""
        return raw.isEmpty ? AppConfig.defaultLiveKitURL : raw
    }

    static func setAPIURL(_ value: String) {
        UserDefaults.standard.set(value.trimmingCharacters(in: .whitespacesAndNewlines), forKey: apiKey)
        print("[Config] API_URL override=\(apiURL.absoluteString)")
    }

    static func setLiveKitURL(_ value: String) {
        UserDefaults.standard.set(value.trimmingCharacters(in: .whitespacesAndNewlines), forKey: liveKitKey)
        print("[Config] LIVEKIT_URL override=\(liveKitURL)")
    }

    static func clearOverrides() {
        UserDefaults.standard.removeObject(forKey: apiKey)
        UserDefaults.standard.removeObject(forKey: liveKitKey)
        print("[Config] cleared URL overrides → api=\(AppConfig.defaultAPIURL.absoluteString) livekit=\(AppConfig.defaultLiveKitURL)")
    }

    static func markEpochCurrent() {
        UserDefaults.standard.set(AppConfig.configEpoch, forKey: epochKey)
    }

    /// Preferred home mode for expert-capable accounts (Customer otherwise).
    static var preferredAppMode: AppMode {
        get {
            let raw = UserDefaults.standard.string(forKey: appModeKey) ?? AppMode.customer.rawValue
            return AppMode(rawValue: raw) ?? .customer
        }
        set {
            UserDefaults.standard.set(newValue.rawValue, forKey: appModeKey)
        }
    }
}
