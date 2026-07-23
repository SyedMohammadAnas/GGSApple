import SwiftUI

@main
struct CGSAppleApp: App {
    @State private var authViewModel = AuthViewModel()

    var body: some Scene {
        WindowGroup {
            RootView(authViewModel: authViewModel)
                .preferredColorScheme(.dark)
                .task {
                    await authViewModel.bootstrap()
                }
                // Handle OAuth return if the system delivers the URL to the app.
                .onOpenURL { url in
                    print("[App] onOpenURL \(url.absoluteString)")
                    Task {
                        do {
                            try await AuthService.shared.client.auth.session(from: url)
                            await authViewModel.bootstrap()
                        } catch {
                            print("[App] session(from:) failed: \(error.localizedDescription)")
                        }
                    }
                }
        }
    }
}
