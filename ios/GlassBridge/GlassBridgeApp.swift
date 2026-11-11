import SwiftUI

@main
struct GlassBridgeApp: App {
    @StateObject private var bridge = BridgePeripheral()

    var body: some Scene {
        WindowGroup {
            ContentView(bridge: bridge)
                .onOpenURL { handle($0) }
        }
    }

    /// glassbridge://send?text=...&title=...   glassbridge://now_playing?title=...&artist=...&state=playing
    private func handle(_ url: URL) {
        guard url.scheme == "glassbridge", let c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return }
        func q(_ n: String) -> String { c.queryItems?.first(where: { $0.name == n })?.value ?? "" }
        switch c.host ?? "" {
        case "send":
            if !q("text").isEmpty { bridge.sendCard(text: q("text"), title: q("title")) }
        case "now_playing":
            bridge.sendNowPlaying(title: q("title"), artist: q("artist"), state: q("state").isEmpty ? "playing" : q("state"))
        default:
            break
        }
    }
}
