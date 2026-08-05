import Foundation

/// Central runtime config for Instant customer app (experts use Assist AR web).
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

    // MARK: - Defaults (Assist AR production — bump configEpoch when URLs change)

    /// Bump this when production API/LiveKit defaults change so stale Debug overrides are cleared.
    static let configEpoch = 4

    /// Session/token API — Assist AR Next.js on Vercel.
    static let defaultAPIURL = URL(string: "https://ggsexpert.vercel.app")!
    /// Homelab LiveKit WSS (Tailscale Serve). iMac Lab media retired 2026-07-27.
    static let defaultLiveKitURL = "wss://server-laptop-anassyed.tail3bc01f.ts.net:7880"

    // MARK: - Product

    static let appDisplayName = "Instant"
    static let expertWebURL = URL(string: "https://ggsexpert.vercel.app")!
    static let isPremium = false
}

/// UserDefaults overrides for debug backend URL sheet.
@MainActor
enum RuntimeConfig {
    private static let apiKey = "runtime.apiURL"
    private static let liveKitKey = "runtime.liveKitURL"
    private static let epochKey = "runtime.configEpoch"

    /// Call once at launch so phones stop hitting stale lab `:3002` after production cutover.
    static func migrateIfNeeded() {
        let stored = UserDefaults.standard.integer(forKey: epochKey)
        if stored == AppConfig.configEpoch {
            return
        }
        // Drop old overrides that pointed at lab HTTP while experts use Vercel.
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
}
