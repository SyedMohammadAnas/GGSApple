import Foundation
import UIKit
import Observation
import Auth
import Supabase

@MainActor
@Observable
final class HomeViewModel {
    var mode: AppMode = .customer
    var expertIDInput: String = ""
    var statusMessage: String = ""
    var isBusy = false
    var showSoloAR = false
    var showDebugSheet = false
    var showMenu = false
    var joiningPromptVisible = false
    var activeCall: SessionCredentials?

    private var pollTask: Task<Void, Never>?
    private var accessToken: String = ""
    private var userId: UUID?

    func configure(session: Session, profile: Profile?) {
        accessToken = session.accessToken
        userId = session.user.id
        print("[Home] configure user=\(session.user.id) publicId=\(profile?.publicId ?? "nil")")
        startCustomerWatcherIfNeeded()
    }

    func setMode(_ mode: AppMode) {
        self.mode = mode
        statusMessage = ""
        startCustomerWatcherIfNeeded()
    }

    func stopWatchers() {
        pollTask?.cancel()
        pollTask = nil
    }

    /// Customer: wait until expert creates/activates a session for this user.
    private func startCustomerWatcherIfNeeded() {
        pollTask?.cancel()
        guard mode == .customer, !accessToken.isEmpty else { return }

        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.pollForIncomingExpert()
                try? await Task.sleep(nanoseconds: 2_500_000_000)
            }
        }
    }

    private func pollForIncomingExpert() async {
        guard mode == .customer, activeCall == nil, !joiningPromptVisible else { return }
        do {
            let creds = try await SessionService.shared.customerEnter(accessToken: accessToken)
            print("[Home] incoming active session \(creds.sessionId)")
            joiningPromptVisible = true
            statusMessage = "Expert is joining…"
            // Brief prompt, then enter call.
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            joiningPromptVisible = false
            activeCall = creds
            stopWatchers()
        } catch {
            // 404 = no active session yet — expected while waiting.
        }
    }

    func sharePublicId(_ profile: Profile?) {
        let text = PublicIdFormat.display(profile?.publicId)
        let av = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let root = scene.keyWindow?.rootViewController else { return }
        root.present(av, animated: true)
        print("[Home] share ID \(text)")
    }

    func copyPublicId(_ profile: Profile?) {
        UIPasteboard.general.string = PublicIdFormat.display(profile?.publicId)
        statusMessage = "ID copied"
        print("[Home] copied ID")
    }

    func pasteIntoExpertField() {
        if let s = UIPasteboard.general.string {
            expertIDInput = PublicIdFormat.digitsOnly(s)
        }
    }

    func joinAsExpert() async {
        let digits = PublicIdFormat.digitsOnly(expertIDInput)
        guard digits.count == 11 else {
            statusMessage = "Enter an 11-digit customer ID"
            return
        }
        isBusy = true
        statusMessage = "Joining…"
        joiningPromptVisible = true
        print("[Home] expert join-by-id \(digits)")
        do {
            let creds = try await SessionService.shared.joinByPublicId(digits, accessToken: accessToken)
            try? await Task.sleep(nanoseconds: 800_000_000)
            joiningPromptVisible = false
            activeCall = creds
            statusMessage = ""
            print("[Home] expert joined session \(creds.sessionId)")
        } catch {
            joiningPromptVisible = false
            statusMessage = error.localizedDescription
            print("[Home] expert join FAILED: \(error.localizedDescription)")
        }
        isBusy = false
    }

    func clearCachePreservingAuth() {
        // Clear URL overrides + any image caches; keep Supabase session.
        URLCache.shared.removeAllCachedResponses()
        statusMessage = "Cache cleared"
        print("[Home] clear cache (auth preserved)")
    }

    func endCallAndReset() async {
        if let call = activeCall {
            await SessionService.shared.endSession(sessionId: call.sessionId, accessToken: accessToken)
        }
        activeCall = nil
        joiningPromptVisible = false
        startCustomerWatcherIfNeeded()
    }
}


private extension UIWindowScene {
    var keyWindow: UIWindow? { windows.first { $0.isKeyWindow } ?? windows.first }
}
