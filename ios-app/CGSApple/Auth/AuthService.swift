import Foundation
import Supabase
import Auth
import Observation

/// Thin wrapper around supabase-swift Auth for Google OAuth.
@MainActor
final class AuthService {
    static let shared = AuthService()

    let client: SupabaseClient

    private init() {
        client = SupabaseClient(
            supabaseURL: AppConfig.supabaseURL,
            supabaseKey: AppConfig.supabaseAnonKey,
            options: SupabaseClientOptions(
                auth: .init(
                    redirectToURL: AppConfig.authCallbackURL,
                    // Opt into upcoming supabase-swift default (PR #822).
                    emitLocalSessionAsInitialSession: true
                )
            )
        )
    }

    /// Current session if restored from keychain.
    func currentSession() async -> Session? {
        try? await client.auth.session
    }

    /// Google OAuth via ASWebAuthenticationSession → `remotear://auth-callback`.
    @discardableResult
    func signInWithGoogle() async throws -> Session {
        try await client.auth.signInWithOAuth(
            provider: .google,
            redirectTo: AppConfig.authCallbackURL
        ) { session in
            // Prefer ephemeral session so the system browser sheet is clearer on device.
            session.prefersEphemeralWebBrowserSession = false
        }
    }

    func signOut() async throws {
        try await client.auth.signOut()
    }

    /// Fetch `profiles` row for the signed-in user (parity with Android).
    func fetchProfile(userId: UUID) async throws -> Profile {
        try await client
            .from("profiles")
            .select()
            .eq("id", value: userId.uuidString)
            .single()
            .execute()
            .value
    }
}

struct Profile: Codable, Equatable {
    let id: UUID
    let role: String?
    let publicId: String?
    let displayName: String?

    enum CodingKeys: String, CodingKey {
        case id
        case role
        case publicId = "public_id"
        case displayName = "display_name"
    }
}
