import Foundation

/// hub status-board.json (hub-kit #316). No money/health values; Foundation only so CI can unit-test it.
struct Board: Codable {
    struct Health: Codable { var load: Double?; var temp_c: Double? }
    struct Project: Codable, Identifiable {
        var name: String
        var open: Int?, done: Int?, waiting: Int?, hold: Int?, total: Int?, pct: Int?
        var next: [String]?
        var age_h: Double?
        var missing: Bool?
        var id: String { name }
    }
    var updated: Double
    var health: Health?
    var projects: [Project]

    static let defaultURL = "http://100.84.41.47:3020/status-board.json"
    static let staleAfter: Double = 15 * 60

    /// Projects that have data (the hub marks absent ones `missing`).
    var live: [Project] { projects.filter { $0.missing != true && $0.total != nil } }
    func isStale(now: Date = Date()) -> Bool { now.timeIntervalSince1970 - updated > Board.staleAfter }
}

/// Display text: the hub says "paper", never "shadow".
func boardClean(_ s: String) -> String {
    s.replacingOccurrences(of: "shadow", with: "paper").replacingOccurrences(of: "Shadow", with: "Paper")
}

/// Compact form for the Live Activity and widgets.
struct BoardSummary: Codable, Hashable {
    var updated: Double
    var load: Double?
    var tempC: Double?
    var lines: [String]   // "glass 36% 16/45", busiest projects first
    var top: String       // top next item of the busiest project

    init(_ b: Board, maxLines: Int = 3) {
        updated = b.updated
        load = b.health?.load
        tempC = b.health?.temp_c
        let busy = b.live.filter { ($0.open ?? 0) > 0 }.sorted { ($0.open ?? 0) > ($1.open ?? 0) }
        lines = busy.prefix(maxLines).map { "\($0.name) \($0.pct ?? 0)% \($0.done ?? 0)/\($0.total ?? 0)" }
        top = boardClean(busy.compactMap { $0.next?.first }.first ?? "")
    }
    init(updated: Double, load: Double?, tempC: Double?, lines: [String], top: String) {
        self.updated = updated; self.load = load; self.tempC = tempC; self.lines = lines; self.top = top
    }

    var healthText: String {
        [load.map { String(format: "load %.1f", $0) }, tempC.map { String(format: "%.0fC", $0) }]
            .compactMap { $0 }.joined(separator: " ")
    }
    static let placeholder = BoardSummary(updated: 0, load: 1.0, tempC: 50, lines: ["glass 36% 16/45", "kbb 67% 62/93"], top: "next item")
}

func fetchBoard(_ urlString: String = Board.defaultURL) async throws -> Board {
    guard let url = URL(string: urlString) else { throw URLError(.badURL) }
    var req = URLRequest(url: url); req.timeoutInterval = 10
    let (data, _) = try await URLSession.shared.data(for: req)
    return try JSONDecoder().decode(Board.self, from: data)
}
