import SwiftUI
import ActivityKit
import BackgroundTasks

/// Polls the hub board while the process runs (BLE background mode keeps it alive) and mirrors it into the Live Activity.
@MainActor final class BoardStore: ObservableObject {
    static let refreshID = "dev.glassbridge.app.refresh"
    @Published var board: Board?
    @Published var error = ""
    @Published var liveOn = false
    private var activity: Activity<BoardAttributes>?

    var url: String { UserDefaults.standard.string(forKey: "boardURL") ?? Board.defaultURL }

    func refresh() async {
        do {
            let b = try await fetchBoard(url)
            board = b; error = ""
            await pushActivity(BoardSummary(b))
        } catch { self.error = "Hub unreachable: \(error.localizedDescription)" }
    }

    private func content(_ s: BoardSummary) -> ActivityContent<BoardSummary> {
        ActivityContent(state: s, staleDate: Date().addingTimeInterval(Board.staleAfter))
    }

    private func pushActivity(_ s: BoardSummary) async {
        if activity == nil { activity = Activity<BoardAttributes>.activities.first }
        if let a = activity { await a.update(content(s)) }
        liveOn = activity != nil
    }

    func startLive() async {
        guard activity == nil, ActivityAuthorizationInfo().areActivitiesEnabled, let b = board else { return }
        activity = try? Activity.request(attributes: BoardAttributes(), content: content(BoardSummary(b)), pushType: nil)
        liveOn = activity != nil
    }

    func stopLive() async {
        for a in Activity<BoardAttributes>.activities { await a.end(nil, dismissalPolicy: .immediate) }
        activity = nil; liveOn = false
    }

    // BGAppRefreshTask: opportunistic wake when the app is not running.
    func registerBackground() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.refreshID, using: nil) { task in
            let work = Task { @MainActor in
                await self.refresh(); self.scheduleBackground(); task.setTaskCompleted(success: true)
            }
            task.expirationHandler = { work.cancel() }
        }
    }
    func scheduleBackground() {
        let r = BGAppRefreshTaskRequest(identifier: Self.refreshID)
        r.earliestBeginDate = Date().addingTimeInterval(15 * 60)
        try? BGTaskScheduler.shared.submit(r)
    }
}
