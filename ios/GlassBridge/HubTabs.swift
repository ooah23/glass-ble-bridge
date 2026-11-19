import SwiftUI

struct BoardView: View {
    @ObservedObject var store: BoardStore
    var body: some View {
        NavigationView {
            List {
                if !store.error.isEmpty { Text(store.error).foregroundColor(.red) }
                if let b = store.board {
                    Section { Text(BoardSummary(b).healthText + (b.isStale() ? "  STALE" : "")).foregroundColor(b.isStale() ? .orange : .primary) }
                    ForEach(b.live) { p in
                        VStack(alignment: .leading, spacing: 2) {
                            HStack { Text(p.name).font(.headline); Spacer(); Text("\(p.pct ?? 0)%  \(p.done ?? 0)/\(p.total ?? 0)").font(.footnote.monospaced()) }
                            ProgressView(value: Double(p.pct ?? 0), total: 100)
                            if let n = p.next?.first { Text(boardClean(n)).font(.caption).foregroundColor(.secondary).lineLimit(2) }
                        }
                    }
                }
                Section {
                    Button(store.liveOn ? "Stop Live Activity" : "Start Live Activity") {
                        Task { if store.liveOn { await store.stopLive() } else { await store.startLive() } }
                    }
                }
            }
            .navigationTitle("Board")
            .refreshable { await store.refresh() }
        }.navigationViewStyle(.stack)
    }
}

/// Lead chats: derived from board data (open items + hours since the backlog last changed). No extra hub JSON needed yet.
struct LeadChatsView: View {
    @ObservedObject var store: BoardStore
    var body: some View {
        NavigationView {
            List(store.board?.live ?? []) { p in
                let idle = (p.open ?? 0) > 0 && (p.age_h ?? 0) > 3
                HStack {
                    VStack(alignment: .leading) {
                        Text(p.name).font(.headline)
                        Text("open \(p.open ?? 0)  waiting on N \(p.waiting ?? 0)  hold \(p.hold ?? 0)").font(.caption)
                    }
                    Spacer()
                    Text(String(format: "%.0fh", p.age_h ?? 0)).foregroundColor(idle ? .orange : .secondary)
                }
            }.navigationTitle("Lead chats")
        }.navigationViewStyle(.stack)
    }
}

/// Desk alerts: tailnet only, fixed titles only (no money figures). Source: hub /desk-alerts.json (hub-kit #338): {"alerts":[{id,ts,title,kind}]}.
struct DeskAlertsView: View {
    @State private var titles: [String] = []
    @State private var note = ""
    @State private var url = UserDefaults.standard.string(forKey: "deskURL") ?? "http://100.84.41.47:3020/desk-alerts.json"
    var body: some View {
        NavigationView {
            List {
                Section("Event titles only") {
                    if url.isEmpty { Text("No source set. Enter the hub desk-alerts URL below.").foregroundColor(.secondary) }
                    ForEach(titles, id: \.self) { Text($0) }
                    if !note.isEmpty { Text(note).foregroundColor(.red).font(.caption) }
                }
                Section("Source URL (tailnet)") {
                    TextField("http://100.84.41.47:3020/...", text: $url).autocapitalization(.none).disableAutocorrection(true)
                    Button("Save and load") { UserDefaults.standard.set(url, forKey: "deskURL"); Task { await load() } }
                }
            }.navigationTitle("Desk alerts").task { await load() }
        }.navigationViewStyle(.stack)
    }
    private func load() async {
        guard let u = URL(string: url), !url.isEmpty else { return }
        do {
            let (d, _) = try await URLSession.shared.data(from: u)
            let o = try JSONSerialization.jsonObject(with: d) as? [String: Any]
            let rows = (o?["alerts"] as? [[String: Any]]) ?? (o?["events"] as? [[String: Any]]) ?? []
            titles = rows.compactMap { r -> String? in
                guard let t = r["title"] as? String else { return nil }
                let ts = (r["ts"] as? String).map { String($0.dropFirst(5).prefix(11)).replacingOccurrences(of: "T", with: " ") } ?? ""
                return ts.isEmpty ? boardClean(t) : ts + "  " + boardClean(t)
            }
            note = ""
        } catch { note = error.localizedDescription }
    }
}
