import Foundation

/// A frame from the companion. M1 acts only on `welcome`; M2 decodes the rest.
enum ServerFrame: Equatable {
    case welcome
    case other(String)

    /// Throws unless `json` is an object with a string `t`.
    init(json: Data) throws {
        struct Envelope: Decodable { let t: String }
        switch try JSONDecoder().decode(Envelope.self, from: json).t {
        case "welcome": self = .welcome
        case let t: self = .other(t)
        }
    }
}
