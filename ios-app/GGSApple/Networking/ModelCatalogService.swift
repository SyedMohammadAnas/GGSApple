import Foundation

/// Catalog entry from Assist AR `/api/models` (Vercel proxy → homelab manifest).
struct CatalogModel: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let url: String
    let thumbnailUrl: String?
    let fileSizeBytes: Int?
    let description: String?
    let createdAt: String?

    var assetURL: URL? { URL(string: url) }
}

@MainActor
enum ModelCatalogService {
    /// Public catalog — same route the expert web sidebar uses (no auth on client).
    static func fetchCatalog() async throws -> [CatalogModel] {
        let base = RuntimeConfig.apiURL
        guard let url = URL(string: "/api/models", relativeTo: base)?.absoluteURL else {
            throw SessionAPIError.badURL
        }

        print("[Models] GET \(url.absoluteString)")
        let (data, response) = try await URLSession.shared.data(from: url)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200...299).contains(code) else {
            let text = String(data: data, encoding: .utf8) ?? ""
            throw SessionAPIError.http(code, text)
        }

        let decoded = try JSONDecoder().decode(CatalogResponse.self, from: data)
        print("[Models] catalog count=\(decoded.models.count)")
        return decoded.models
    }

    private struct CatalogResponse: Codable {
        let models: [CatalogModel]
    }
}
