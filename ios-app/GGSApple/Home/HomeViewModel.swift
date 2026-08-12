import Foundation
import UIKit
import Observation
import Auth
import Supabase

@MainActor
@Observable
final class HomeViewModel {
    var statusMessage: String = ""
    var isBusy = false
    var showSoloAR = false
    var showDebugSheet = false
    var showMenu = false
    var joiningPromptVisible = false
    var activeCall: SessionCredentials?
    /// Shown in status bar so we can see which API the phone is actually hitting.
    var apiReachabilityHint: String = ""

    /// Home mode — Expert toggle only for expert/admin profiles.
    var appMode: AppMode = .customer
    var canUseExpertMode = false
    var expertTargetId: String = ""

    private var pollTask: Task<Void, Never>?
    private var accessToken: String = ""
    private var userId: UUID?
    private var consecutivePollFailures = 0

    func configure(session: Session, profile: Profile?) {
        RuntimeConfig.migrateIfNeeded()
        accessToken = session.accessToken
        userId = session.user.id
        canUseExpertMode = profile?.canActAsExpert == true
        if canUseExpertMode {
            appMode = RuntimeConfig.preferredAppMode
        } else {
            appMode = .customer
            RuntimeConfig.preferredAppMode = .customer
        }
        apiReachabilityHint = "API \(RuntimeConfig.apiURL.host ?? RuntimeConfig.apiURL.absoluteString)"
        print(
            "[Home] configure mode=\(appMode.rawValue) canExpert=\(canUseExpertMode) user=\(session.user.id) publicId=\(profile?.publicId ?? "nil") role=\(profile?.role ?? "nil") api=\(RuntimeConfig.apiURL.absoluteString) livekit=\(RuntimeConfig.liveKitURL)"
        )
        applyModeSideEffects()
    }

    /// Home-only mode switch (ignored while a call is active).
    func setAppMode(_ mode: AppMode) {
        guard activeCall == nil else {
            print("[Home] ignore mode switch — call active")
            return
        }
        guard mode == .customer || canUseExpertMode else {
            statusMessage = "Expert mode requires an expert account"
            appMode = .customer
            return
        }
        appMode = mode
        if canUseExpertMode {
            RuntimeConfig.preferredAppMode = mode
        }
        statusMessage = ""
        applyModeSideEffects()
        print("[Home] appMode=\(mode.rawValue)")
    }

    private func applyModeSideEffects() {
        stopWatchers()
        if appMode == .customer {
            startCustomerWatcherIfNeeded()
        }
    }

    func stopWatchers() {
        pollTask?.cancel()
        pollTask = nil
    }

    /// Wait until an expert activates a session for this customer.
    func startCustomerWatcherIfNeeded() {
        pollTask?.cancel()
        guard appMode == .customer else { return }
        guard !accessToken.isEmpty, activeCall == nil else { return }

        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                if self?.showSoloAR == true {
                    try? await Task.sleep(nanoseconds: 2_500_000_000)
                    continue
                }
                await self?.pollForIncomingExpert()
                try? await Task.sleep(nanoseconds: 1_500_000_000)
            }
        }
    }

    private func refreshAccessTokenIfPossible() async {
        do {
            let session = try await AuthService.shared.client.auth.session
            accessToken = session.accessToken
            userId = session.user.id
        } catch {
            print("[Home] token refresh skipped: \(error.localizedDescription)")
        }
    }

    private func pollForIncomingExpert() async {
        guard appMode == .customer, activeCall == nil, !joiningPromptVisible else { return }

        await refreshAccessTokenIfPossible()
        guard !accessToken.isEmpty else {
            statusMessage = "Signed out — open Instant again."
            return
        }

        do {
            let creds = try await SessionService.shared.customerEnter(accessToken: accessToken)
            consecutivePollFailures = 0
            print(
                "[Home] incoming active session \(creds.sessionId) livekit=\(creds.livekitUrl)"
            )
            joiningPromptVisible = true
            statusMessage = "Expert is joining…"
            apiReachabilityHint = "Session \(creds.joinCode)"
            do {
                try await Task.sleep(nanoseconds: 900_000_000)
            } catch {
                joiningPromptVisible = false
                statusMessage = ""
                apiReachabilityHint =
                    "API \(RuntimeConfig.apiURL.host ?? RuntimeConfig.apiURL.absoluteString)"
                print("[Home] join prompt cancelled — back to idle")
                return
            }
            guard !Task.isCancelled, activeCall == nil else {
                joiningPromptVisible = false
                return
            }
            do {
                let status = try await SessionService.shared.fetchSessionStatus(
                    sessionId: creds.sessionId,
                    accessToken: accessToken
                )
                if status != "active" {
                    joiningPromptVisible = false
                    statusMessage = ""
                    apiReachabilityHint =
                        "API \(RuntimeConfig.apiURL.host ?? RuntimeConfig.apiURL.absoluteString)"
                    print("[Home] session \(creds.sessionId) already \(status) — skip enter")
                    return
                }
            } catch {
                print("[Home] pre-enter status check failed: \(error.localizedDescription)")
            }
            joiningPromptVisible = false
            activeCall = creds
            stopWatchers()
        } catch let error as SessionAPIError where error.isWaitingForExpert {
            consecutivePollFailures = 0
            if statusMessage.hasPrefix("API error") || statusMessage.hasPrefix("Cannot reach") {
                statusMessage = ""
            }
        } catch {
            consecutivePollFailures += 1
            let detail = error.localizedDescription
            print("[Home] customer-enter FAILED #\(consecutivePollFailures): \(detail) api=\(RuntimeConfig.apiURL.absoluteString)")
            if consecutivePollFailures >= 2 {
                statusMessage = "Cannot reach API — \(detail)"
                apiReachabilityHint = "Check Debug backend URL → \(RuntimeConfig.apiURL.host ?? "?")"
            }
        }
    }

    func joinAsExpert() async {
        guard appMode == .expert, canUseExpertMode else { return }
        guard activeCall == nil, !isBusy else { return }

        let digits = PublicIdFormat.digitsOnly(expertTargetId)
        guard digits.count == 11 else {
            statusMessage = "Enter an 11-digit customer ID"
            return
        }

        isBusy = true
        statusMessage = "Joining…"
        await refreshAccessTokenIfPossible()
        defer { isBusy = false }

        do {
            let creds = try await SessionService.shared.joinById(
                targetPublicId: digits,
                accessToken: accessToken
            )
            print("[Home] expert joined session \(creds.sessionId) livekit=\(creds.livekitUrl)")
            statusMessage = ""
            apiReachabilityHint = "Session \(creds.joinCode)"
            activeCall = creds
            stopWatchers()
        } catch {
            statusMessage = error.localizedDescription
            print("[Home] join-by-id FAILED: \(error.localizedDescription)")
        }
    }

    func pasteCustomerId() {
        if let clip = UIPasteboard.general.string, !clip.isEmpty {
            expertTargetId = PublicIdFormat.display(PublicIdFormat.digitsOnly(clip))
            print("[Home] pasted customer ID")
        }
    }

    func sharePublicId(_ profile: Profile?) {
        let id = PublicIdFormat.display(profile?.publicId)
        let text = """
        My AR Assist ID: \(id)
        Open AR Assist as Expert, then Join with this ID.
        """
        let av = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let root = scene.keyWindow?.rootViewController else { return }
        root.present(av, animated: true)
        print("[Home] share ID \(id)")
    }

    func copyPublicId(_ profile: Profile?) {
        UIPasteboard.general.string = PublicIdFormat.display(profile?.publicId)
        statusMessage = "ID copied"
        print("[Home] copied ID")
    }

    func clearCachePreservingAuth() {
        URLCache.shared.removeAllCachedResponses()
        RuntimeConfig.clearOverrides()
        RuntimeConfig.migrateIfNeeded()
        apiReachabilityHint = "API \(RuntimeConfig.apiURL.host ?? RuntimeConfig.apiURL.absoluteString)"
        statusMessage = "Cache + URL overrides cleared"
        print("[Home] clear cache + URL overrides → \(RuntimeConfig.apiURL.absoluteString)")
    }

    func endCallAndReset() async {
        if let call = activeCall {
            await refreshAccessTokenIfPossible()
            await SessionService.shared.endSession(sessionId: call.sessionId, accessToken: accessToken)
        }
        activeCall = nil
        joiningPromptVisible = false
        consecutivePollFailures = 0
        statusMessage = ""
        apiReachabilityHint = "API \(RuntimeConfig.apiURL.host ?? RuntimeConfig.apiURL.absoluteString)"
        print("[Home] call ended — back to \(appMode.rawValue) home")
        applyModeSideEffects()
    }
}

private extension UIWindowScene {
    var keyWindow: UIWindow? { windows.first { $0.isKeyWindow } ?? windows.first }
}
