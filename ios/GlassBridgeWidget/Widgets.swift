import WidgetKit
import SwiftUI
import ActivityKit

@main
struct HubWidgets: WidgetBundle {
    var body: some Widget { BoardWidget(); BoardLiveActivity() }
}

// MARK: home / lock-screen widget (timeline polls the tailnet URL; iOS budgets ~40-70 refreshes a day)
struct BoardEntry: TimelineEntry { let date: Date; let s: BoardSummary?; let stale: Bool }

struct BoardProvider: TimelineProvider {
    func placeholder(in: Context) -> BoardEntry { BoardEntry(date: Date(), s: .placeholder, stale: false) }
    func getSnapshot(in: Context, completion: @escaping (BoardEntry) -> Void) { completion(placeholder(in: in)) }
    func getTimeline(in: Context, completion: @escaping (Timeline<BoardEntry>) -> Void) {
        Task {
            let b = try? await fetchBoard()
            let e = BoardEntry(date: Date(), s: b.map { BoardSummary($0) }, stale: b?.isStale() ?? true)
            completion(Timeline(entries: [e], policy: .after(Date().addingTimeInterval(15 * 60))))
        }
    }
}

struct BoardWidgetView: View {
    @Environment(\.widgetFamily) var family
    let e: BoardEntry
    var body: some View {
        if let s = e.s {
            VStack(alignment: .leading, spacing: 2) {
                ForEach(s.lines.prefix(family == .systemSmall || family == .accessoryRectangular ? 2 : 3), id: \.self) {
                    Text($0).font(.caption.monospaced())
                }
                if family == .systemMedium, !s.top.isEmpty { Text(s.top).font(.caption2).lineLimit(2) }
                Text(s.healthText + (e.stale ? " STALE" : "")).font(.caption2).foregroundColor(e.stale ? .orange : .secondary)
            }
        } else {
            Text("Hub unreachable (Tailscale?)").font(.caption)
        }
    }
}

struct BoardWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "HubBoard", provider: BoardProvider()) { e in
            if #available(iOS 17.0, *) { BoardWidgetView(e: e).containerBackground(.fill.tertiary, for: .widget) }
            else { BoardWidgetView(e: e).padding() }
        }
        .configurationDisplayName("Hub board")
        .description("Per-project progress and hub health.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular])
    }
}

// MARK: Live Activity + Dynamic Island
struct BoardLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: BoardAttributes.self) { ctx in
            VStack(alignment: .leading, spacing: 2) {
                ForEach(ctx.state.lines, id: \.self) { Text($0).font(.footnote.monospaced()) }
                if !ctx.state.top.isEmpty { Text(ctx.state.top).font(.caption).lineLimit(2) }
                Text(ctx.state.healthText + (ctx.isStale ? " STALE" : "")).font(.caption2)
                    .foregroundColor(ctx.isStale ? .orange : .secondary)
            }.padding()
        } dynamicIsland: { ctx in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) { Text(ctx.state.lines.first ?? "hub").font(.caption.monospaced()) }
                DynamicIslandExpandedRegion(.trailing) { Text(ctx.state.healthText).font(.caption2) }
                DynamicIslandExpandedRegion(.bottom) { Text(ctx.isStale ? "STALE" : ctx.state.top).font(.caption).lineLimit(2) }
            } compactLeading: { Text("hub") } compactTrailing: {
                Text(ctx.isStale ? "stale" : (ctx.state.lines.first.flatMap { $0.split(separator: " ").dropFirst().first.map(String.init) } ?? "-"))
            } minimal: { Text("hub").font(.caption2) }
        }
    }
}
