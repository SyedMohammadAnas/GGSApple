import Foundation
import Observation
import Supabase
import Auth

@MainActor
@Observable
final class AuthViewModel {
    enum Phase: Equatable {
        case bootstrapping
        case signedOut
        case signedIn(session: Session, profile: Profile?)
        case failed(String)
    }

    private(set) var phase: Phase = .bootstrapping
    var isBusy = false
    var statusMessage: String = ""

    private let auth = AuthService.shared

    func bootstrap() async {
        phase = .bootstrapping
        statusMessage = "Checking session…"
        print("[Auth] bootstrap start")

        do {
            let session = try await auth.client.auth.session
            await loadSignedIn(session: session)
        } catch {
            // No persisted session is normal on first launch.
            phase = .signedOut
            statusMessage = ""
            print("[Auth] bootstrap: \(error.localizedDescription)")
        }
    }

    func signInWithGoogle() async {
        isBusy = true
        statusMessage = "Opening Google sign-in…"
        print("[Auth] signInWithGoogle start")

        do {
            let session = try await auth.signInWithGoogle()
            await loadSignedIn(session: session)
            print("[Auth] signInWithGoogle success user=\(session.user.id)")
        } catch {
            let message = error.localizedDescription
            phase = .failed(message)
            statusMessage = message
            print("[Auth] signInWithGoogle FAILED: \(message)")
            // Allow retry from signed-out UI.
            phase = .signedOut
        }

        isBusy = false
    }

    func signOut() async {
        isBusy = true
        print("[Auth] signOut")
        do {
            try await auth.signOut()
        } catch {
            print("[Auth] signOut error: \(error.localizedDescription)")
        }
        phase = .signedOut
        statusMessage = ""
        isBusy = false
    }

    private func loadSignedIn(session: Session) async {
        var profile: Profile?
        do {
            profile = try await auth.fetchProfile(userId: session.user.id)
            print("[Auth] profile role=\(profile?.role ?? "nil") publicId=\(profile?.publicId ?? "nil")")
        } catch {
            // Profile may lag behind first login trigger — still treat as signed in.
            print("[Auth] profile fetch skipped/failed: \(error.localizedDescription)")
        }
        phase = .signedIn(session: session, profile: profile)
        statusMessage = ""
    }
}
