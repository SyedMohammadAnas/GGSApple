import SwiftUI

struct RootView: View {
    @Bindable var authViewModel: AuthViewModel

    var body: some View {
        Group {
            switch authViewModel.phase {
            case .bootstrapping:
                ZStack {
                    Color.black.ignoresSafeArea()
                    ProgressView("Loading…")
                        .tint(.white)
                        .foregroundStyle(.white)
                }
            case .signedOut, .failed:
                AuthView(viewModel: authViewModel)
            case .signedIn(_, let profile):
                HomeView(authViewModel: authViewModel, profile: profile)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: phaseKey)
    }

    private var phaseKey: String {
        switch authViewModel.phase {
        case .bootstrapping: return "boot"
        case .signedOut: return "out"
        case .signedIn: return "in"
        case .failed: return "fail"
        }
    }
}
