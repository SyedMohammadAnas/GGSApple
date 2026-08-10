import Foundation
import UIKit
import Observation
import Auth
import Supabase

@MainActor
@Observable
final class HomeViewModel {
    /// Instant app is customer-only; expert lives on Assist AR web.
    var statusMessage: String = ""
    var isBusy = false
    var showSoloAR = false
    var showDebugSheet = false
    var showMenu = false
    var joiningPromptVisible = false
    var activeCall: SessionCredentials?
    /// Shown in status bar so we can see which API the phone is actually hitting.
    var apiReachabilityHint: String = ""

    private var pollTask: Task<Void, Never>?
    private var accessToken: String = ""
    private var userId: UUID?
    private var consecutivePollFailures = 0

    func configure(session: Session, profile: Profile?) {
        RuntimeConfig.migrateIfNeeded()
        accessToken = session.accessToken
        userId = session.user.id
        apiReachabilityHint = "API \(RuntimeConfig.apiURL.host ?? RuntimeConfig.apiURL.absoluteString)"
        print(
            "[Home] configure customer-only user=\(session.user.id) publicId=\(profile?.publicId ?? "nil") api=\(RuntimeConfig.apiURL.absoluteString) livekit=\(RuntimeConfig.liveKitURL)"
        )
        startCustomerWatcherIfNeeded()
    }

    func stopWatchers() {
        pollTask?.cancel()
        pollTask = nil
    }

    /// Wait until Assist AR expert activates a session for this customer.
    func startCustomerWatcherIfNeeded() {
        pollTask?.cancel()
        guard !accessToken.isEmpty, activeCall == nil else { return }

        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                // Pause polling while offline Assist is open (saves network + main-thread noise).
                if self?.showSoloAR == true {
                    try? await Task.sleep(nanoseconds: 2_500_000_000)
                    continue
                }
                await self?.pollForIncomingExpert()
                // Faster poll so phone notices expert Connect within ~1–2s.
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
        guard activeCall == nil, !joiningPromptVisible else { return }

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
            statusMessage = "Assist AR expert is joining…"
            apiReachabilityHint = "Session \(creds.joinCode)"
            // Brief prompt, then enter call.
            do {
                try await Task.sleep(nanoseconds: 900_000_000)
            } catch {
                // Watcher cancelled (call ended / solo AR) — do not leave join chrome stuck.
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
            // Expert may have ended during the brief join prompt — do not enter a dead call.
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
            // Expected while waiting — keep status calm.
            if statusMessage.hasPrefix("API error") || statusMessage.hasPrefix("Cannot reach") {
                statusMessage = ""
            }
        } catch {
            consecutivePollFailures += 1
            let detail = error.localizedDescription
            print("[Home] customer-enter FAILED #\(consecutivePollFailures): \(detail) api=\(RuntimeConfig.apiURL.absoluteString)")
            // Surface real failures so we do not sit silent while expert is already live.
            if consecutivePollFailures >= 2 {
                statusMessage = "Cannot reach Assist AR API — \(detail)"
                apiReachabilityHint = "Check Debug backend URL → \(RuntimeConfig.apiURL.host ?? "?")"
            }
        }
    }

    func sharePublicId(_ profile: Profile?) {
        let id = PublicIdFormat.display(profile?.publicId)
        let text = """
        My Instant ID: \(id)
        Open Assist AR, sign in as expert, then Connect with this ID.
        \(AppConfig.expertWebURL.absoluteString)
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
        // Clear stale join / session chrome so home returns to idle waiting.
        statusMessage = ""
        apiReachabilityHint = "API \(RuntimeConfig.apiURL.host ?? RuntimeConfig.apiURL.absoluteString)"
        print("[Home] call ended — idle waiting for next Assist AR expert")
        startCustomerWatcherIfNeeded()
    }
}


private extension UIWindowScene {
    var keyWindow: UIWindow? { windows.first { $0.isKeyWindow } ?? windows.first }
}
