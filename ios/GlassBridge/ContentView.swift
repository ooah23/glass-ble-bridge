import SwiftUI

struct ContentView: View {
    @ObservedObject var bridge: BridgePeripheral
    @State private var secret = Keychain.get("secret") ?? ""
    @State private var cardText = ""
    @State private var npTitle = ""
    @State private var npArtist = ""
    @State private var npState = "playing"
    @State private var pollURL = UserDefaults.standard.string(forKey: "pollURL") ?? ""
    @State private var lastPoll = ""
    private let timer = Timer.publish(every: 15, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationView {
            Form {
                Section("Status") {
                    Text(bridge.statusLine)
                        .font(.headline)
                        .foregroundColor(bridge.authed ? .green : (bridge.statusLine.hasPrefix("Link lost") ? .red : .primary))
                    Text(bridge.detail).font(.footnote).foregroundColor(.secondary)
                    if bridge.queued > 0 { Text("\(bridge.queued) message(s) queued until Glass connects") }
                }
                Section("Shared secret (stored in keychain)") {
                    TextField("Pair code shown on the Glass", text: $secret).textInputAutocapitalization(.characters).autocorrectionDisabled()
                    Button("Save code") { secret = String(secret.filter { !$0.isWhitespace }).uppercased(); Keychain.set("secret", secret) }
                }
                Section("Send card") {
                    TextField("Text", text: $cardText)
                    Button("Send") {
                        if !cardText.isEmpty { bridge.sendCard(text: cardText); cardText = "" }
                    }
                }
                Section("Now playing") {
                    TextField("Title", text: $npTitle)
                    TextField("Artist", text: $npArtist)
                    Picker("State", selection: $npState) {
                        Text("playing").tag("playing")
                        Text("paused").tag("paused")
                        Text("stopped").tag("stopped")
                    }.pickerStyle(.segmented)
                    Button("Send") { bridge.sendNowPlaying(title: npTitle, artist: npArtist, state: npState) }
                }
                Section("Optional poll URL (GET returns {\"now_playing\":{title,artist,state}})") {
                    TextField("https://...", text: $pollURL)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                    Button("Save") { UserDefaults.standard.set(pollURL, forKey: "pollURL") }
                }
                Section("Log") {
                    ForEach(bridge.log, id: \.self) { Text($0).font(.footnote) }
                }
            }
            .navigationTitle("GlassBridge")
            .onReceive(timer) { _ in poll() }
        }
        .navigationViewStyle(.stack)
    }

    private func poll() {
        guard let url = URL(string: pollURL), !pollURL.isEmpty else { return }
        URLSession.shared.dataTask(with: url) { data, _, _ in
            guard let data = data,
                  let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let np = obj["now_playing"] as? [String: Any] else { return }
            let t = np["title"] as? String ?? ""
            let a = np["artist"] as? String ?? ""
            let s = np["state"] as? String ?? "playing"
            DispatchQueue.main.async {
                let key = "\(t)|\(a)|\(s)"
                if key != lastPoll { lastPoll = key; bridge.sendNowPlaying(title: t, artist: a, state: s) }
            }
        }.resume()
    }
}
