import Foundation

/// Heartbeat bookkeeping, pure (caller supplies a monotonic clock in seconds). Mirrors glass/.../core/LinkMonitor.java.
/// Send every 5 s; no hb/hback for 15 s = link lost.
struct LinkMonitor {
    static let interval: Double = 5
    static let timeout: Double = 15
    enum Action { case none, send, lost }

    private var base: Double = 0
    private var lastSent: Double?
    private(set) var lastAck: Double?
    private(set) var counter = 0

    /// Call when the link becomes authenticated. lastAck survives so "last seen" keeps counting across reconnects.
    mutating func start(now: Double) { base = now; lastSent = nil }
    /// Any hb or hback from the peer.
    mutating func received(now: Double) { lastAck = now }

    mutating func tick(now: Double) -> Action {
        let ref = max(base, lastAck ?? -Double.infinity)
        if now - ref >= LinkMonitor.timeout { return .lost }
        if lastSent == nil || now - lastSent! >= LinkMonitor.interval { lastSent = now; counter += 1; return .send }
        return .none
    }

    /// Seconds since the peer was last heard, nil if never.
    func ageSeconds(now: Double) -> Int? {
        guard let a = lastAck else { return nil }
        return max(0, Int(now - a))
    }
}

enum LinkPhase: Equatable {
    case scanning, connecting, authenticating, paired
    case lost(String)
}

/// Same wording as glass/.../core/Status.java.
func linkStatusText(_ phase: LinkPhase, ageSec: Int?) -> String {
    var s: String
    var showAge = false
    switch phase {
    case .scanning: s = "Scanning"; showAge = true
    case .connecting: s = "Connecting"
    case .authenticating: s = "Authenticating"
    case .paired: s = "Paired"; showAge = true
    case .lost(let r): s = "Link lost (\(r))"; showAge = true
    }
    if showAge, let a = ageSec { s += " - last seen \(a)s ago" }
    return s
}
