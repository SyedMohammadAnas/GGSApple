import SwiftUI

/// AR Assist home — Customer / Expert toggle matching mockups.
struct HomeView: View {
    @Bindable var authViewModel: AuthViewModel
    let profile: Profile?
    @State private var home = HomeViewModel()

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 0) {
                topBar
                ScrollView {
                    VStack(spacing: 20) {
                        hero
                        modeBody
                        tutorialButton
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 8)
                    .padding(.bottom, 24)
                }
                statusBar
            }

            if home.joiningPromptVisible {
                joiningOverlay
            }
        }
        .preferredColorScheme(.dark)
        .onAppear {
            if case .signedIn(let session, _) = authViewModel.phase {
                home.configure(session: session, profile: profile)
            }
        }
        .onDisappear { home.stopWatchers() }
        .fullScreenCover(isPresented: $home.showSoloAR) {
            SoloARView()
        }
        .fullScreenCover(item: Binding(
            get: { home.activeCall.map(CallRoute.init) },
            set: { if $0 == nil { Task { await home.endCallAndReset() } } }
        )) { route in
            CallView(credentials: route.credentials) {
                Task { await home.endCallAndReset() }
            }
        }
        .sheet(isPresented: $home.showDebugSheet) {
            DebugBackendSheet()
        }
    }

    // MARK: - Top bar

    private var topBar: some View {
        HStack(spacing: 10) {
            HStack(spacing: 8) {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(AppTheme.orange)
                    .frame(width: 28, height: 28)
                    .overlay {
                        Image(systemName: "bag.fill")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(.white)
                    }
                Text(AppConfig.appDisplayName)
                    .font(.headline.weight(.bold))
                    .foregroundStyle(.white)
            }

            Spacer()

            Toggle("", isOn: Binding(
                get: { home.mode == .expert },
                set: { home.setMode($0 ? .expert : .customer) }
            ))
            .labelsHidden()
            .tint(AppTheme.orange)
            .accessibilityLabel("Expert mode")

            Menu {
                Button("Debug backend URL") { home.showDebugSheet = true }
                Button("Clear cache") { home.clearCachePreservingAuth() }
                Divider()
                Button("Copy my ID") { home.copyPublicId(profile) }
                Button("Sign out", role: .destructive) {
                    home.stopWatchers()
                    Task { await authViewModel.signOut() }
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.body.weight(.bold))
                    .foregroundStyle(.white)
                    .frame(width: 36, height: 36)
                    .background(Color.white.opacity(0.1))
                    .clipShape(Circle())
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    // MARK: - Hero

    private var hero: some View {
        Image("AppPreview")
            .resizable()
            .scaledToFit()
            .frame(maxWidth: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(Color.cyan.opacity(0.55), lineWidth: 2)
            )
    }

    // MARK: - Mode body

    @ViewBuilder
    private var modeBody: some View {
        if home.mode == .customer {
            customerBlock
        } else {
            expertBlock
        }

        if !home.statusMessage.isEmpty {
            Text(home.statusMessage)
                .font(.footnote)
                .foregroundStyle(AppTheme.orange)
                .multilineTextAlignment(.center)
        }
    }

    private var customerBlock: some View {
        VStack(spacing: 14) {
            Text("Share your ID to receive quick remote support")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)

            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Your ID")
                        .font(.caption2)
                        .foregroundStyle(.white.opacity(0.45))
                    Text(PublicIdFormat.display(profile?.publicId))
                        .font(.title3.monospaced().weight(.bold))
                        .foregroundStyle(.white)
                }
                Spacer()
                Button {
                    home.copyPublicId(profile)
                } label: {
                    Image(systemName: "doc.on.doc")
                        .foregroundStyle(.white.opacity(0.7))
                        .padding(10)
                }
            }
            .padding(14)
            .background(AppTheme.panel)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))

            Button {
                home.sharePublicId(profile)
            } label: {
                Label("Share your ID", systemImage: "square.and.arrow.up")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(AppTheme.orange)
                    .foregroundStyle(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
        }
    }

    private var expertBlock: some View {
        VStack(spacing: 14) {
            Text("Enter customer ID to provide quick remote support")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)

            HStack {
                TextField("Enter the ID", text: $home.expertIDInput)
                    .keyboardType(.numberPad)
                    .textInputAutocapitalization(.never)
                    .foregroundStyle(.white)
                Button {
                    home.pasteIntoExpertField()
                } label: {
                    Image(systemName: "clipboard")
                        .foregroundStyle(.white.opacity(0.7))
                        .padding(10)
                }
            }
            .padding(14)
            .background(AppTheme.panel)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))

            Button {
                Task { await home.joinAsExpert() }
            } label: {
                Label(home.isBusy ? "Joining…" : "Join the session", systemImage: "rectangle.portrait.and.arrow.right")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(AppTheme.orange)
                    .foregroundStyle(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .disabled(home.isBusy)
        }
    }

    private var tutorialButton: some View {
        Button {
            home.showSoloAR = true
        } label: {
            Label("Create video tutorial", systemImage: "video")
                .font(.headline)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .foregroundStyle(.white)
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(Color.white.opacity(0.55), lineWidth: 1.5)
                )
        }
    }

    private var statusBar: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(AppTheme.statusGreen)
                .frame(width: 8, height: 8)
            Text("Ready to connect (connection is secure)")
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.55))
            Spacer()
            Image(systemName: "wifi")
                .foregroundStyle(AppTheme.statusGreen)
            Image(systemName: "bolt.fill")
                .foregroundStyle(AppTheme.statusGreen)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Color.black)
    }

    private var joiningOverlay: some View {
        ZStack {
            Color.black.opacity(0.72).ignoresSafeArea()
            VStack(spacing: 16) {
                ProgressView()
                    .tint(AppTheme.orange)
                    .scaleEffect(1.3)
                Text(home.mode == .expert ? "Joining customer…" : "Expert is joining…")
                    .font(.headline)
                    .foregroundStyle(.white)
                Text("Setting up secure AR session")
                    .font(.footnote)
                    .foregroundStyle(.white.opacity(0.55))
            }
            .padding(28)
            .background(AppTheme.panel)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
    }
}

private struct CallRoute: Identifiable {
    let credentials: SessionCredentials
    var id: String { credentials.sessionId }
}

struct DebugBackendSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var api = RuntimeConfig.apiURL.absoluteString
    @State private var livekit = RuntimeConfig.liveKitURL

    var body: some View {
        NavigationStack {
            Form {
                Section("API") {
                    TextField("http://100.83.95.8:3000", text: $api)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                Section("LiveKit") {
                    TextField("ws://100.83.95.8:7880", text: $livekit)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                Section {
                    Button("Save") {
                        RuntimeConfig.setAPIURL(api)
                        RuntimeConfig.setLiveKitURL(livekit)
                        dismiss()
                    }
                    Button("Reset to Tailscale defaults", role: .destructive) {
                        RuntimeConfig.clearOverrides()
                        api = AppConfig.defaultAPIURL.absoluteString
                        livekit = AppConfig.defaultLiveKitURL
                    }
                }
            }
            .navigationTitle("Debug backend URL")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}
