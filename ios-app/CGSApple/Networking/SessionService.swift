import Foundation
import Auth

enum AppMode: String, CaseIterable, Identifiable {
    case customer
    case expert

    var id: String { rawValue }

    var title: String {
        switch self {
        case .customer: return "Customer"
        case .expert: return "Expert"
        }
    }
}

struct SessionCredentials: Equatable {
    let sessionId: String
    let roomName: String
    let joinCode: String
    let status: String
    let token: String
    let role: AppMode
}

enum SessionAPIError: LocalizedError {
    case message(String)
    case http(Int, String)
    case badURL

    var errorDescription: String? {
        switch self {
        case .message(let m): return m
        case .http(let code, let body): return "HTTP \(code): \(body)"
        case .badURL: return "Invalid API URL"
        }
    }
}

@MainActor
final class SessionService {
    static let shared = SessionService()

    private init() {}

    func joinByPublicId(_ publicId: String, accessToken: String) async throws -> SessionCredentials {
        let digits = publicId.filter(\.isNumber)
        let body = ["targetPublicId": digits]
        let json = try await postJSON(
            path: "/api/sessions/join-by-id",
            accessToken: accessToken,
            body: body
        )
        return try decodeCredentials(json, role: .expert)
    }

    func customerEnter(accessToken: String) async throws -> SessionCredentials {
        let json = try await postJSON(
            path: "/api/sessions/customer-enter",
            accessToken: accessToken,
            body: [:] as [String: String]
        )
        return try decodeCredentials(json, role: .customer)
    }

    func endSession(sessionId: String, accessToken: String) async {
        do {
            _ = try await postJSON(
                path: "/api/sessions/\(sessionId)/end",
                accessToken: accessToken,
                body: [:] as [String: String]
            )
            print("[Session] ended \(sessionId)")
        } catch {
            print("[Session] end failed: \(error.localizedDescription)")
        }
    }

    private func decodeCredentials(_ json: [String: Any], role: AppMode) throws -> SessionCredentials {
        guard
            let sessionId = json["sessionId"] as? String,
            let roomName = json["roomName"] as? String,
            let joinCode = json["joinCode"] as? String,
            let status = json["status"] as? String,
            let token = json["token"] as? String
        else {
            throw SessionAPIError.message("Malformed session response")
        }
        return SessionCredentials(
            sessionId: sessionId,
            roomName: roomName,
            joinCode: joinCode,
            status: status,
            token: token,
            role: role
        )
    }

    private func postJSON(
        path: String,
        accessToken: String,
        body: [String: String]
    ) async throws -> [String: Any] {
        let base = RuntimeConfig.apiURL
        guard let url = URL(string: path, relativeTo: base)?.absoluteURL else {
            throw SessionAPIError.badURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        print("[Session] POST \(url.absoluteString)")
        let (data, response) = try await URLSession.shared.data(for: request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        let text = String(data: data, encoding: .utf8) ?? ""

        guard (200...299).contains(code) else {
            if let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let err = obj["error"] as? String {
                throw SessionAPIError.message(err)
            }
            throw SessionAPIError.http(code, text)
        }

        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw SessionAPIError.message("Invalid JSON")
        }
        return obj
    }
}

enum PublicIdFormat {
    static func display(_ raw: String?) -> String {
        let digits = (raw ?? "").filter(\.isNumber)
        guard digits.count == 11 else { return raw ?? "—" }
        let s = Array(digits)
        return "\(s[0])-\(String(s[1...3]))-\(String(s[4...6]))-\(String(s[7...10]))"
    }

    static func digitsOnly(_ raw: String) -> String {
        raw.filter(\.isNumber)
    }
}
