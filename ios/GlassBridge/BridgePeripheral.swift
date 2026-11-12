import Foundation
import CoreBluetooth
import UIKit

/// CoreBluetooth peripheral. Advertises the GlassBridge service; Glass (central) writes to rx and subscribes to tx.
/// Link state comes from heartbeats (5 s each way, 15 s silence = lost), not from CoreBluetooth callbacks alone.
final class BridgePeripheral: NSObject, ObservableObject, CBPeripheralManagerDelegate {
    @Published var statusLine = "Scanning"
    @Published var detail = "Starting"
    @Published var subscribed = false
    @Published var authed = false
    @Published var queued = 0
    @Published var log: [String] = []

    private let serviceUUID = CBUUID(string: BridgeProtocol.service)
    private let rxUUID = CBUUID(string: BridgeProtocol.rxChar)
    private let txUUID = CBUUID(string: BridgeProtocol.txChar)
    private var manager: CBPeripheralManager!
    private var tx: CBMutableCharacteristic?
    private var reasm = Framing.Reassembler()
    private var outFrames: [Data] = []
    private var held: [[String: Any]] = []   // messages waiting for auth
    private var nonce = ""
    private var phase: LinkPhase = .scanning
    private var btProblem: String?
    private var mon = LinkMonitor()
    private var timer: Timer?

    private static var now: Double { ProcessInfo.processInfo.systemUptime }

    override init() {
        super.init()
        manager = CBPeripheralManager(delegate: self, queue: nil,
                                      options: [CBPeripheralManagerOptionRestoreIdentifierKey: "dev.glassbridge.peripheral"])
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tick() }
        NotificationCenter.default.addObserver(self, selector: #selector(foreground),
                                               name: UIApplication.willEnterForegroundNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(foreground),
                                               name: UIApplication.didBecomeActiveNotification, object: nil)
    }

    // MARK: public API

    /// Queue a message. Held until the Glass has authenticated.
    func send(_ msg: [String: Any]) {
        if authed { enqueue(msg) }
        else {
            held.append(msg)
            if held.count > 20 { held.removeFirst() }
            queued = held.count
        }
    }

    func sendCard(text: String, title: String = "", ttl: Int = 0) {
        send(["t": "card", "id": UUID().uuidString, "title": title, "text": text, "ttl": ttl])
    }

    func sendNowPlaying(title: String, artist: String, state: String) {
        send(["t": "now_playing", "title": title, "artist": artist, "state": state])
    }

    // MARK: state

    private func setPhase(_ p: LinkPhase) {
        phase = p
        refreshStatus()
    }

    private func refreshStatus() {
        if let p = btProblem { statusLine = p; return }
        statusLine = linkStatusText(phase, ageSec: mon.ageSeconds(now: BridgePeripheral.now))
    }

    /// Runs every second: send heartbeats, detect a dead link, refresh "last seen".
    private func tick() {
        if authed {
            switch mon.tick(now: BridgePeripheral.now) {
            case .send: enqueue(["t": "hb", "n": mon.counter])
            case .lost: linkLost("heartbeat timeout 15s")
            case .none: break
            }
        }
        refreshStatus()
    }

    private func linkLost(_ reason: String) {
        note("Link lost: \(reason)")
        subscribed = false
        authed = false
        nonce = ""
        outFrames.removeAll()
        reasm = Framing.Reassembler()
        setPhase(.lost(reason))
        readvertise()
    }

    @objc private func foreground() { ensureAdvertising() }

    /// Stop and start advertising so a fresh Glass scan can find us.
    private func readvertise() {
        guard manager.state == .poweredOn, tx != nil else { return }
        manager.stopAdvertising()
        startAdvertising()
    }

    private func ensureAdvertising() {
        guard manager.state == .poweredOn else { return }
        if tx == nil { setup(); return }
        if !manager.isAdvertising { startAdvertising() }
    }

    private func startAdvertising() {
        manager.startAdvertising([CBAdvertisementDataLocalNameKey: BridgeProtocol.localName,
                                  CBAdvertisementDataServiceUUIDsKey: [serviceUUID]])
    }

    // MARK: CBPeripheralManagerDelegate

    func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {
        note("State restored by iOS")
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn:
            btProblem = nil
            detail = "Bluetooth on"
            setup()
        case .poweredOff:
            btProblem = "Bluetooth is off"
            linkDown("Bluetooth is off")
        case .unauthorized:
            btProblem = "Bluetooth permission denied"
        default:
            btProblem = "Bluetooth unavailable"
        }
        refreshStatus()
    }

    private func linkDown(_ reason: String) {
        if subscribed || authed { linkLost(reason) }
    }

    private func setup() {
        let txc = CBMutableCharacteristic(type: txUUID, properties: [.notify], value: nil, permissions: [.readEncryptionRequired])
        let rxc = CBMutableCharacteristic(type: rxUUID, properties: [.write, .writeWithoutResponse], value: nil, permissions: [.writeEncryptionRequired])
        tx = txc
        let svc = CBMutableService(type: serviceUUID, primary: true)
        svc.characteristics = [rxc, txc]
        manager.removeAllServices()
        manager.add(svc)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error = error { detail = "Service error: \(error.localizedDescription)"; return }
        peripheral.stopAdvertising()
        startAdvertising()
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        detail = error == nil ? "Advertising as GlassBridge" : "Advertise error: \(error!.localizedDescription)"
        note(detail)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        subscribed = true
        authed = false
        reasm = Framing.Reassembler()
        outFrames.removeAll()
        setPhase(.connecting)
        note("Glass subscribed")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        note("Glass unsubscribed")
        linkLost("Glass unsubscribed")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for r in requests where r.characteristic.uuid == rxUUID {
            if let v = r.value, let json = reasm.feed(v) { handle(json) }
        }
        if let first = requests.first { peripheral.respond(to: first, withResult: .success) }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) { flush() }

    // MARK: messages

    private func handle(_ json: Data) {
        guard let obj = try? JSONSerialization.jsonObject(with: json) as? [String: Any],
              let t = obj["t"] as? String else { return }
        switch t {
        case "hello":
            setPhase(.authenticating)
            nonce = Auth.newNonce()
            enqueue(["t": "chal", "n": nonce])
        case "auth":
            let secret = Keychain.get("secret") ?? ""
            let want = secret.isEmpty || nonce.isEmpty ? "" : Auth.response(secret: secret, nonce: nonce)
            let got = obj["h"] as? String ?? ""
            nonce = ""
            if !want.isEmpty && Auth.constantTimeEqual(want, got) {
                authed = true
                enqueue(["t": "ok"])
                mon.start(now: BridgePeripheral.now)
                mon.received(now: BridgePeripheral.now)
                setPhase(.paired)
                note("Glass authenticated")
                let pending = held
                held.removeAll()
                queued = 0
                pending.forEach { enqueue($0) }
            } else {
                enqueue(["t": "deny"])
                setPhase(.lost(secret.isEmpty ? "no pair code set in app" : "wrong pair code"))
                note(secret.isEmpty ? "Glass rejected: no secret set in app" : "Glass rejected: wrong secret")
            }
        case "hb":
            guard authed else { return }
            mon.received(now: BridgePeripheral.now)
            enqueue(["t": "hback", "n": obj["n"] as? Int ?? 0])
            refreshStatus()
        case "hback":
            guard authed else { return }
            mon.received(now: BridgePeripheral.now)
            refreshStatus()
        case "cmd":
            if authed, let c = obj["c"] as? String { note("Glass command: \(c)") }
        default:
            break
        }
    }

    private func enqueue(_ msg: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: msg) else { return }
        outFrames.append(contentsOf: Framing.chunk(data))
        if outFrames.count > 200 { outFrames.removeFirst(outFrames.count - 200) }
        flush()
    }

    private func flush() {
        guard let tx = tx, subscribed else { return }
        while let f = outFrames.first {
            if manager.updateValue(f, for: tx, onSubscribedCentrals: nil) { outFrames.removeFirst() } else { return }
        }
    }

    private func note(_ s: String) {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        log.insert("\(f.string(from: Date())) \(s)", at: 0)
        if log.count > 30 { log.removeLast() }
    }
}
