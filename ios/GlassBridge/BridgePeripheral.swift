import Foundation
import CoreBluetooth

/// CoreBluetooth peripheral. Advertises the GlassBridge service; Glass (central) writes to rx and subscribes to tx.
final class BridgePeripheral: NSObject, ObservableObject, CBPeripheralManagerDelegate {
    @Published var status = "Starting"
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

    override init() {
        super.init()
        manager = CBPeripheralManager(delegate: self, queue: nil)
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

    // MARK: CBPeripheralManagerDelegate

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn: setup()
        case .poweredOff: status = "Bluetooth is off"
        case .unauthorized: status = "Bluetooth permission denied"
        default: status = "Bluetooth unavailable"
        }
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
        if let error = error { status = "Service error: \(error.localizedDescription)"; return }
        peripheral.startAdvertising([CBAdvertisementDataLocalNameKey: BridgeProtocol.localName,
                                     CBAdvertisementDataServiceUUIDsKey: [serviceUUID]])
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        status = error == nil ? "Advertising, waiting for Glass" : "Advertise error: \(error!.localizedDescription)"
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        subscribed = true
        authed = false
        reasm = Framing.Reassembler()
        outFrames.removeAll()
        status = "Glass connected, authenticating"
        note("Glass subscribed")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        subscribed = false
        authed = false
        nonce = ""
        status = "Advertising, waiting for Glass"
        note("Glass disconnected")
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
                status = "Glass authenticated"
                note("Glass authenticated")
                let pending = held
                held.removeAll()
                queued = 0
                pending.forEach { enqueue($0) }
            } else {
                enqueue(["t": "deny"])
                note(secret.isEmpty ? "Glass rejected: no secret set in app" : "Glass rejected: wrong secret")
            }
        case "cmd":
            if authed, let c = obj["c"] as? String { note("Glass command: \(c)") }
        default:
            break
        }
    }

    private func enqueue(_ msg: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: msg) else { return }
        outFrames.append(contentsOf: Framing.chunk(data))
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
