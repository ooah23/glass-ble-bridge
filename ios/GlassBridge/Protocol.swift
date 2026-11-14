import Foundation

/// Constants shared with the Glass app. Keep in sync with docs/PROTOCOL.md and glass/.../core/Protocol.java.
enum BridgeProtocol {
    static let service = "8a6f0000-5b1e-4c3a-9d2f-7e1b0c4a6d10"
    /// Glass writes here (glass to phone).
    static let rxChar = "8a6f0001-5b1e-4c3a-9d2f-7e1b0c4a6d10"
    /// Phone notifies here (phone to glass).
    static let txChar = "8a6f0002-5b1e-4c3a-9d2f-7e1b0c4a6d10"
    static let localName = "GlassBridge"
    /// Link encryption on the GATT characteristics. false = no iOS pairing prompt needed (diagnosis: Android 4.4 discovery stalled against encrypted characteristics); the app-level pair-code HMAC still authenticates every session. Re-enable once Glass-side bonding is added.
    static let requireEncryption = false
    static let frameSize = 20
    static let maxMessage = 4096
}
