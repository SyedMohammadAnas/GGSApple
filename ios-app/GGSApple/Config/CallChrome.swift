import SwiftUI
import UIKit

// MARK: - Snap points

/// Bottom drawer snap points — heights are resolved against the container (see `resolvedHeight`).
enum CallDrawerSnap: CaseIterable, Equatable {
    /// Handle + evenly spaced call controls, tucked to the bottom.
    case collapsed
    /// Same controls on top; search + recent assets expand underneath (seamless).
    case peek
    /// Full assets dashboard — call controls hidden until dragged back down.
    case expanded

    /// Stable pixel height for each snap (collapsed matches real content — no hug/frame swap).
    func resolvedHeight(totalHeight: CGFloat, safeBottom: CGFloat) -> CGFloat {
        let bottomPad = max(safeBottom - 2, 6)
        switch self {
        case .collapsed:
            // Body (handle + controls only) + home-indicator pad.
            // Do NOT add bottomPad into the clipped body — that was revealing the search bar.
            return Self.collapsedBodyHeight + bottomPad
        case .peek:
            return totalHeight * 0.48
        case .expanded:
            return totalHeight * 0.92
        }
    }

    /// Clipped sheet body while collapsed — tight so search/recent stay fully hidden.
    /// handle + controls row + trimmed gap (peek still uses full 30pt padding when open).
    static let collapsedBodyHeight: CGFloat = 24 + 96 + 8

    static func nearest(to height: CGFloat, totalHeight: CGFloat, safeBottom: CGFloat) -> CallDrawerSnap {
        allCases.min(by: {
            abs($0.resolvedHeight(totalHeight: totalHeight, safeBottom: safeBottom) - height)
                < abs($1.resolvedHeight(totalHeight: totalHeight, safeBottom: safeBottom) - height)
        }) ?? .collapsed
    }
}

// MARK: - Call control model

struct CallControlAction: Identifiable {
    let id: String
    let title: String
    let systemImage: String
    var isDestructive: Bool = false
    let action: () -> Void
}

// MARK: - Drawer

/// Shared Offline Assist + live Call bottom drawer.
/// Drag only on the drawer body/handle so freehand pans on the AR view stay free.
struct CallBottomDrawer: View {
    @Binding var snap: CallDrawerSnap
    let controls: [CallControlAction]
    /// Expert-loaded models for peek/expanded assets grid (empty = show hint).
    var recentAssetItems: [AssetPlaceholderItem] = []
    /// Full catalog — shown in expanded drawer for customer model pick.
    var catalogAssetItems: [AssetPlaceholderItem] = []
    var selectedModelId: String? = nil
    var placedModelId: String? = nil
    var isLoadingModels: Bool = false
    var modelError: String? = nil
    var onSelectAsset: ((AssetPlaceholderItem) -> Void)? = nil
    var onPreviewAsset: ((AssetPlaceholderItem) -> Void)? = nil
    var onRemoveModel: (() -> Void)? = nil
    @State private var dragTranslation: CGFloat = 0
    @State private var searchText = ""
    @State private var selectedCategory = AssetCategory.tempCategories[0]
    @State private var showFilterSheet = false

    var body: some View {
        GeometryReader { geo in
            let totalHeight = geo.size.height
            let safeBottom = geo.safeAreaInsets.bottom
            let baseHeight = snap.resolvedHeight(totalHeight: totalHeight, safeBottom: safeBottom)
            let minHeight = CallDrawerSnap.collapsed.resolvedHeight(totalHeight: totalHeight, safeBottom: safeBottom)
            let maxHeight = CallDrawerSnap.expanded.resolvedHeight(totalHeight: totalHeight, safeBottom: safeBottom)
            // Always the same height model (pinned to bottom) — never swap hug ↔ framed mid-drag.
            let currentHeight = min(max(baseHeight - dragTranslation, minHeight), maxHeight)

            VStack(spacing: 0) {
                Spacer(minLength: 0)
                    .allowsHitTesting(false)

                drawerCard(
                    totalHeight: totalHeight,
                    currentHeight: currentHeight,
                    safeBottom: safeBottom
                )
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            .ignoresSafeArea(edges: .bottom)
        }
        .sheet(isPresented: $showFilterSheet) {
            NavigationStack {
                List {
                    ForEach(AssetCategory.tempCategories) { category in
                        Button {
                            selectedCategory = category
                            showFilterSheet = false
                        } label: {
                            HStack {
                                Text(category.name)
                                Spacer()
                                if category.id == selectedCategory.id {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(AppTheme.orange)
                                }
                            }
                        }
                    }
                }
                .navigationTitle("Filter")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Done") { showFilterSheet = false }
                    }
                }
            }
            .presentationDetents([.medium])
        }
    }

    private func drawerCard(totalHeight: CGFloat, currentHeight: CGFloat, safeBottom: CGFloat) -> some View {
        let bottomPad = max(safeBottom - 2, 6)
        // Home-indicator padding sits OUTSIDE the clipped body so collapsed never shows search.
        let bodyHeight = max(currentHeight - bottomPad, CallDrawerSnap.collapsedBodyHeight)

        return VStack(spacing: 0) {
            VStack(spacing: 0) {
                // Drag ONLY on the handle — keeps control buttons tappable.
                drawerHandle
                    .gesture(drawerDrag(totalHeight: totalHeight, safeBottom: safeBottom))

                // Controls stay mounted for collapsed + peek.
                // Same gap in collapsed + peek so search never crowds the controls mid-drag.
                if snap != .expanded {
                    evenlySpacedControls
                        .padding(.bottom, 30)
                }

                if snap == .expanded {
                    AssetsDrawerPanel(
                        mode: .full,
                        searchText: $searchText,
                        selectedCategory: $selectedCategory,
                        recentItems: recentAssetItems,
                        catalogItems: catalogAssetItems,
                        selectedModelId: selectedModelId,
                        placedModelId: placedModelId,
                        isLoadingModels: isLoadingModels,
                        modelError: modelError,
                        onSelectItem: onSelectAsset,
                        onPreviewItem: onPreviewAsset,
                        onRemoveModel: onRemoveModel,
                        onFilterTap: { showFilterSheet = true }
                    )
                } else {
                    // Mounted while collapsed; clipped by bodyHeight so search stays hidden.
                    AssetsDrawerPanel(
                        mode: .peek,
                        searchText: $searchText,
                        selectedCategory: $selectedCategory,
                        recentItems: recentAssetItems,
                        catalogItems: catalogAssetItems,
                        selectedModelId: selectedModelId,
                        placedModelId: placedModelId,
                        isLoadingModels: isLoadingModels,
                        modelError: modelError,
                        onSelectItem: onSelectAsset,
                        onPreviewItem: onPreviewAsset,
                        onRemoveModel: onRemoveModel,
                        onFilterTap: { showFilterSheet = true }
                    )
                }
            }
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity)
            .frame(height: bodyHeight, alignment: .top)
            .clipped()

            Color.clear
                .frame(height: bottomPad)
        }
        .frame(maxWidth: .infinity)
        .assistDarkBlurRounded(28)
    }

    private var drawerHandle: some View {
        VStack(spacing: 0) {
            Capsule()
                .fill(Color.white.opacity(0.35))
                .frame(width: 36, height: 4)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 24)
        .contentShape(Rectangle())
    }

    /// Four call buttons in equal columns across the width.
    private var evenlySpacedControls: some View {
        HStack(spacing: 0) {
            ForEach(controls) { control in
                Button(action: {
                    print("[CallDrawer] control=\(control.id)")
                    control.action()
                }) {
                    VStack(spacing: 6) {
                        Image(systemName: control.systemImage)
                            .font(.title2.weight(.semibold))
                            .foregroundStyle(.white.opacity(0.95))
                            .frame(width: 64, height: 64)
                            .background {
                                if control.isDestructive {
                                    // Solid bright red — no material wash that dulls the end button.
                                    Circle().fill(Color(red: 1.0, green: 0.18, blue: 0.14))
                                } else {
                                    ZStack {
                                        Circle().fill(Color.black.opacity(0.35))
                                        Circle().fill(.ultraThinMaterial)
                                    }
                                    .environment(\.colorScheme, .dark)
                                }
                            }
                            .clipShape(Circle())

                        Text(control.title)
                            .font(.caption2)
                            .foregroundStyle(.white.opacity(0.75))
                    }
                    .frame(maxWidth: .infinity)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .frame(height: 96)
        .zIndex(2)
    }

    private func drawerDrag(totalHeight: CGFloat, safeBottom: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 6, coordinateSpace: .global)
            .onChanged { value in
                // Up = negative translation → taller drawer (grows upward, stays bottom-pinned).
                dragTranslation = value.translation.height
            }
            .onEnded { value in
                let base = snap.resolvedHeight(totalHeight: totalHeight, safeBottom: safeBottom)
                let predictedHeight = base - value.predictedEndTranslation.height
                let next = CallDrawerSnap.nearest(
                    to: predictedHeight,
                    totalHeight: totalHeight,
                    safeBottom: safeBottom
                )
                withAnimation(.interactiveSpring(response: 0.34, dampingFraction: 0.86, blendDuration: 0.15)) {
                    snap = next
                    dragTranslation = 0
                }
                print("[CallDrawer] snap=\(next)")
            }
    }
}

// MARK: - Assets panel

enum AssetsDrawerMode {
    case peek
    case full
}

struct AssetCategory: Identifiable, Hashable {
    let id: String
    let name: String

    static let tempCategories: [AssetCategory] = [
        .init(id: "recent", name: "Recent"),
        .init(id: "machines", name: "Machines"),
        .init(id: "parts", name: "Parts"),
        .init(id: "tools", name: "Tools"),
        .init(id: "docs", name: "Docs"),
        .init(id: "safety", name: "Safety")
    ]
}

struct AssetPlaceholderItem: Identifiable {
    let id: String
    let title: String
    let systemImage: String
    var modelURL: URL? = nil
    var thumbnailURL: String? = nil
}

struct AssetsDrawerPanel: View {
    let mode: AssetsDrawerMode
    @Binding var searchText: String
    @Binding var selectedCategory: AssetCategory
    var recentItems: [AssetPlaceholderItem] = []
    var catalogItems: [AssetPlaceholderItem] = []
    var selectedModelId: String? = nil
    var placedModelId: String? = nil
    var isLoadingModels: Bool = false
    var modelError: String? = nil
    var onSelectItem: ((AssetPlaceholderItem) -> Void)? = nil
    var onPreviewItem: ((AssetPlaceholderItem) -> Void)? = nil
    var onRemoveModel: (() -> Void)? = nil
    var onFilterTap: () -> Void

    private let columns = [
        GridItem(.flexible(), spacing: 10),
        GridItem(.flexible(), spacing: 10),
        GridItem(.flexible(), spacing: 10)
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            searchRow

            if mode == .full {
                categoryCapsules
            } else {
                Text(mode == .full ? "3D models — tap to select" : "Recent models")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.55))
            }

            ScrollView {
                if isLoadingModels {
                    HStack(spacing: 8) {
                        ProgressView()
                            .tint(.white.opacity(0.7))
                            .scaleEffect(0.8)
                        Text("Loading 3D models…")
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.7))
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 4)
                } else if filteredItems.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(mode == .full ? "No 3D models available" : "No models yet")
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.6))
                        if let error = modelError {
                            Text(error)
                                .font(.caption2)
                                .foregroundStyle(.orange.opacity(0.8))
                        } else if mode != .full {
                            Text("Expand to browse catalog")
                                .font(.caption2)
                                .foregroundStyle(.white.opacity(0.4))
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 4)
                } else {
                    LazyVGrid(columns: columns, spacing: 10) {
                        ForEach(filteredItems) { item in
                            assetTile(item)
                        }
                    }
                    .padding(.bottom, 8)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    private var searchRow: some View {
        HStack(spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.white.opacity(0.45))
                TextField("Search assets", text: $searchText)
                    .textInputAutocapitalization(.never)
                    .disableAutocorrection(true)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 11)
            .background(Color.white.opacity(0.08))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

            if mode == .full {
                Button(action: onFilterTap) {
                    Image(systemName: "line.3.horizontal.decrease.circle")
                        .font(.title3.weight(.semibold))
                        .frame(width: 44, height: 44)
                        .foregroundStyle(.white)
                }
                .assistDarkBlurRounded(12)
            }
        }
    }

    private var categoryCapsules: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(AssetCategory.tempCategories) { category in
                    Button {
                        selectedCategory = category
                    } label: {
                        Text(category.name)
                            .font(.caption.weight(.semibold))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .foregroundStyle(selectedCategory.id == category.id ? .black : .white.opacity(0.85))
                            .background(
                                Capsule().fill(
                                    selectedCategory.id == category.id
                                        ? AppTheme.orange
                                        : Color.white.opacity(0.10)
                                )
                            )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private var filteredItems: [AssetPlaceholderItem] {
        let base = mode == .full ? catalogItems : recentItems
        guard !searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return base
        }
        return base.filter { $0.title.localizedCaseInsensitiveContains(searchText) }
    }

    private func assetTile(_ item: AssetPlaceholderItem) -> some View {
        let isSelected = selectedModelId == item.id
        let isPlaced = placedModelId == item.id
        let isHighlighted = isSelected || isPlaced
        
        VStack(spacing: 8) {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color.white.opacity(isHighlighted ? 0.16 : 0.08))
                .frame(height: mode == .peek ? 72 : 96)
                .overlay {
                    Group {
                        if let thumbnailURL = item.thumbnailURL, 
                           let url = URL(string: thumbnailURL) {
                            AsyncImage(url: url) { image in
                                image
                                    .resizable()
                                    .aspectRatio(contentMode: .fit)
                                    .clipped()
                            } placeholder: {
                                Image(systemName: item.systemImage)
                                    .font(.title3)
                                    .foregroundStyle(.white.opacity(0.7))
                            }
                        } else {
                            Image(systemName: item.systemImage)
                                .font(.title3)
                                .foregroundStyle(.white.opacity(0.7))
                        }
                    }
                    .foregroundStyle(
                        isSelected ? AppTheme.orange : 
                        isPlaced ? .green : .white.opacity(0.4)
                    )
                }
                .overlay {
                    if isSelected {
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .stroke(AppTheme.orange, lineWidth: 2)
                    } else if isPlaced {
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .stroke(.green, lineWidth: 2)
                    }
                }

            Text(item.title)
                .font(.caption2)
                .foregroundStyle(
                    isPlaced ? .green.opacity(0.9) : .white.opacity(0.7)
                )
                .lineLimit(1)
        }
        .contentShape(Rectangle())
        .onTapGesture {
            onSelectItem?(item)
        }
        .onLongPressGesture {
            if isPlaced {
                onRemoveModel?()
            } else {
                onPreviewItem?(item)
            }
        }
    }
}

// MARK: - Screenshot (chrome-free)

enum CallScreenshotCapture {
    /// Snapshot a specific UIView (ARView / LiveKit VideoView) — excludes SwiftUI chrome.
    @MainActor
    static func capture(view: UIView?) -> UIImage? {
        guard let view else {
            print("[Screenshot] miss — no view")
            return nil
        }
        let format = UIGraphicsImageRendererFormat()
        format.scale = UIScreen.main.scale
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(bounds: view.bounds, format: format)
        let image = renderer.image { _ in
            view.drawHierarchy(in: view.bounds, afterScreenUpdates: true)
        }
        print("[Screenshot] captured \(Int(image.size.width))x\(Int(image.size.height))")
        return image
    }

    @MainActor
    static func saveToPhotos(_ image: UIImage) {
        UIImageWriteToSavedPhotosAlbum(image, nil, nil, nil)
        print("[Screenshot] saved to Photos")
    }
}

/// White circle shutter control for call / offline Assist.
struct CallScreenshotButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Circle()
                .fill(Color.white)
                .frame(width: 44, height: 44)
                .overlay {
                    Circle()
                        .strokeBorder(Color.black.opacity(0.12), lineWidth: 1)
                }
                .overlay {
                    Image(systemName: "camera.fill")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.black.opacity(0.75))
                }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Take screenshot")
    }
}

// MARK: - Session elapsed timer

enum SessionElapsedFormatting {
    /// `m:ss` under 1 hour, then `h:mm:ss`.
    static func string(from elapsed: TimeInterval) -> String {
        let total = max(0, Int(elapsed))
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let seconds = total % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%d:%02d", minutes, seconds)
    }
}

/// Big top capsule: `AR session 0:00` — starts when the host view appears.
struct ARSessionTimerCapsule: View {
    let startedAt: Date

    var body: some View {
        TimelineView(.periodic(from: startedAt, by: 1)) { context in
            let label = SessionElapsedFormatting.string(
                from: context.date.timeIntervalSince(startedAt)
            )
            Text("AR session \(label)")
                .font(.title3.weight(.semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 22)
                .padding(.vertical, 14)
                .assistDarkBlur(in: Capsule())
        }
    }
}

// MARK: - Collapsible annotation rail

struct AnnotationToolRail: View {
    @Binding var selectedTool: AnnotationTool
    @Binding var isCollapsed: Bool
    var dimmed: Bool = false
    var highlightModelTool: Bool = false
    var onSelect: ((AnnotationTool) -> Void)? = nil

    var body: some View {
        ZStack(alignment: .trailing) {
            if isCollapsed {
                // Peek tab on the right edge — round only the leading corners (flush to screen edge).
                let peekShape = UnevenRoundedRectangle(
                    topLeadingRadius: 16,
                    bottomLeadingRadius: 16,
                    bottomTrailingRadius: 0,
                    topTrailingRadius: 0,
                    style: .continuous
                )
                Button {
                    withAnimation(.spring(response: 0.38, dampingFraction: 0.86)) {
                        isCollapsed = false
                    }
                    print("[AnnotationRail] expanded")
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.body.weight(.bold))
                        .foregroundStyle(.black)
                        .frame(width: 28, height: 88)
                        .assistLightBlur(in: peekShape)
                        .clipShape(peekShape)
                }
                .buttonStyle(.plain)
                .transition(.move(edge: .trailing).combined(with: .opacity))
            } else {
                VStack(spacing: 10) {
                    ForEach(AnnotationTool.allCases) { tool in
                        let selected = selectedTool == tool
                        let highlighted = selected || (tool == .model && highlightModelTool)
                        Button {
                            selectedTool = tool
                            onSelect?(tool)
                        } label: {
                            Image(systemName: tool.systemImage)
                                .font(.body.weight(.semibold))
                                .foregroundStyle(highlighted ? Color.white : Color.black)
                                .frame(width: 44, height: 44)
                                .background {
                                    if highlighted {
                                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                                            .fill(AppTheme.orange)
                                    }
                                }
                        }
                        .buttonStyle(.plain)
                    }

                    // Collapse — slides the rail off to the right.
                    Button {
                        withAnimation(.spring(response: 0.38, dampingFraction: 0.86)) {
                            isCollapsed = true
                        }
                        print("[AnnotationRail] collapsed")
                    } label: {
                        Image(systemName: "chevron.right")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(.black)
                            .frame(width: 44, height: 44)
                    }
                    .buttonStyle(.plain)
                }
                .padding(8)
                .assistLightBlurRounded(18)
                .transition(.move(edge: .trailing).combined(with: .opacity))
            }
        }
        .opacity(dimmed ? 0.35 : 1)
        .allowsHitTesting(!dimmed)
    }
}

// MARK: - Surface coach (offline + live customer)

/// Dim overlay + move-around icon until the first plane is found (or caller clears `isPresented`).
struct SurfaceCoachOverlay: View {
    @Binding var isPresented: Bool

    var body: some View {
        if isPresented {
            ZStack {
                Color.black.opacity(0.60)
                    .ignoresSafeArea()

                VStack(spacing: 20) {
                    Image("MoveAround")
                        .renderingMode(.template)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 120, height: 120)
                        .foregroundStyle(.white)

                    Text("Move around your phone to capture the surfaces")
                        .font(.headline.weight(.semibold))
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 36)
                }
            }
            .transition(.opacity)
            .allowsHitTesting(true)
        }
    }
}
