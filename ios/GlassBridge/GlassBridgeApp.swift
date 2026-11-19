import SwiftUI

@main
struct GlassBridgeApp: App {
    @StateObject private var bridge = BridgePeripheral()
    @StateObject private var board: BoardStore
    @Environment(\.scenePhase) private var phase
    private let tick = Timer.publish(every: 60, on: .main, in: .common).autoconnect()

    init() { let b = BoardStore(); b.registerBackground(); _board = StateObject(wrappedValue: b) } // BGTask must register before launch ends

    var body: some Scene {
        WindowGroup {
            TabView {
                BoardView(store: board).tabItem { Label("Board", systemImage: "chart.bar") }
                ContentView(bridge: bridge).tabItem { Label("Glass", systemImage: "eyeglasses") }
                LeadChatsView(store: board).tabItem { Label("Leads", systemImage: "bubble.left.and.bubble.right") }
                DeskAlertsView().tabItem { Label("Desk", systemImage: "bell") }
            }
            .onOpenURL { handle($0) }
            .task { await board.refresh(); await board.startLive() }
            .onReceive(tick) { _ in Task { await board.refresh() } }
            .onChange(of: phase) { p in
                if p == .background { board.scheduleBackground() } else if p == .active { Task { await board.refresh() } }
            }
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
