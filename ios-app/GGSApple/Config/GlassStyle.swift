import SwiftUI

enum AppChrome {
    static let customerBlue = Color(red: 0.20, green: 0.48, blue: 1.0)
}

extension View {
    /// Liquid Glass on iOS 26+; ultra-thin material fallback otherwise.
    @ViewBuilder
    func assistGlass(
        tint: Color? = nil,
        interactive: Bool = true,
        in shape: some Shape
    ) -> some View {
        if #available(iOS 26, *) {
            Group {
                if let tint {
                    if interactive {
                        self.glassEffect(.regular.tint(tint).interactive(), in: shape)
                    } else {
                        self.glassEffect(.regular.tint(tint), in: shape)
                    }
                } else if interactive {
                    self.glassEffect(.regular.interactive(), in: shape)
                } else {
                    self.glassEffect(.regular, in: shape)
                }
            }
        } else {
            self.background(.ultraThinMaterial, in: shape)
        }
    }

    func assistGlassCapsule(tint: Color? = nil, interactive: Bool = true) -> some View {
        assistGlass(tint: tint, interactive: interactive, in: Capsule())
    }

    func assistGlassCircle(tint: Color? = nil, interactive: Bool = true) -> some View {
        assistGlass(tint: tint, interactive: interactive, in: Circle())
    }

    func assistGlassRounded(_ radius: CGFloat = 16, tint: Color? = nil, interactive: Bool = true) -> some View {
        assistGlass(
            tint: tint,
            interactive: interactive,
            in: RoundedRectangle(cornerRadius: radius, style: .continuous)
        )
    }

    /// Dark tinted blur for call drawer / control chrome (no Liquid Glass).
    func assistDarkBlur(in shape: some Shape) -> some View {
        self.background {
            ZStack {
                shape.fill(Color.black.opacity(0.55))
                shape.fill(.ultraThinMaterial)
            }
            .environment(\.colorScheme, .dark)
        }
    }

    func assistDarkBlurRounded(_ radius: CGFloat = 16) -> some View {
        assistDarkBlur(in: RoundedRectangle(cornerRadius: radius, style: .continuous))
    }

    func assistDarkBlurCircle() -> some View {
        assistDarkBlur(in: Circle())
    }

    /// Light tinted blur for the annotation tool rail.
    func assistLightBlur(in shape: some Shape) -> some View {
        self.background {
            ZStack {
                shape.fill(Color.white.opacity(0.55))
                shape.fill(.thinMaterial)
            }
            .environment(\.colorScheme, .light)
        }
    }

    func assistLightBlurRounded(_ radius: CGFloat = 16) -> some View {
        assistLightBlur(in: RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

/// Circular call-control using system Liquid Glass button style when available.
struct AssistGlassCallButton: View {
    let title: String
    let systemName: String
    var tint: Color? = nil
    var role: ButtonRole? = nil
    let action: () -> Void

    var body: some View {
        Button(role: role, action: action) {
            VStack(spacing: 4) {
                Image(systemName: systemName)
                    .font(.title3)
                    .frame(width: 56, height: 56)
                Text(title)
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.75))
            }
        }
        .modifier(CircularGlassButtonModifier(tint: tint, destructive: role == .destructive))
    }
}

private struct CircularGlassButtonModifier: ViewModifier {
    var tint: Color?
    var destructive: Bool

    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            content
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
                .tint(destructive ? .red : (tint ?? .primary))
        } else {
            content
                .buttonStyle(.plain)
                .background(.ultraThinMaterial, in: Circle())
        }
    }
}
