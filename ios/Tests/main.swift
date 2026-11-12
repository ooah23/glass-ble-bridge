import Foundation

// Compiled with Framing.swift, Auth.swift, Protocol.swift by CI (see .github/workflows/ios.yml). Vectors match CoreTest.java.
var failures = 0
func check(_ ok: Bool, _ what: String) { if !ok { failures += 1; print("FAIL: \(what)") } }
func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }

let vec = Data("{\"t\":\"card\",\"text\":\"Hello Glass\"}".utf8)
let frames = Framing.chunk(vec)
check(frames.map(hex).joined(separator: " ") == "8000217b2274223a2263617264222c2274657874 41223a2248656c6c6f20476c617373227d", "framing vector")

var r = Framing.Reassembler()
var got: Data?
for f in frames { got = r.feed(f) }
check(got == vec, "roundtrip vector")

for n in [0, 1, 16, 17, 18, 19, 20, 36, 37, 38, 100, 1000, 4096] {
    let msg = Data((0..<n).map { _ in UInt8.random(in: 0...255) })
    var rr = Framing.Reassembler()
    var out: Data?
    for f in Framing.chunk(msg) { check(f.count <= 20, "frame size"); out = rr.feed(f) }
    check(out == msg, "roundtrip \(n)")
}

// seq gap then recovery
var g = Framing.Reassembler()
let fr = Framing.chunk(Data(count: 100))
_ = g.feed(fr[0]); _ = g.feed(fr[2])
check(g.errors == 1, "gap detected")
check(g.feed(fr[3]) == nil, "orphan dropped")
var rec: Data?
for f in frames { rec = g.feed(f) }
check(rec == vec, "recovers")

check(Auth.response(secret: "test-secret", nonce: "00112233445566778899aabbccddeeff")
      == "84b7660ccd62a3d5848a05112cd1cff4e753779cb4464c156e0e57d7c9b6cef3", "hmac vector")
check(Auth.constantTimeEqual("ab", "ab") && !Auth.constantTimeEqual("ab", "ac"), "ct equal")

// heartbeat / link monitor (vectors match CoreTest.java)
var lm = LinkMonitor()
lm.start(now: 1)
check(lm.tick(now: 1) == .send, "hb first send")
check(lm.tick(now: 3) == .none, "hb quiet")
check(lm.tick(now: 6) == .send, "hb 5s send")
check(lm.counter == 2, "hb counter")
check(lm.ageSeconds(now: 6) == nil, "never seen")
lm.received(now: 10)
check(lm.tick(now: 20) == .send, "alive 10s after ack")
check(lm.tick(now: 24.9) == .none, "alive 14.9s")
check(lm.tick(now: 25) == .lost, "lost at 15s")
check(lm.ageSeconds(now: 25) == 15, "age")
lm.start(now: 30)
check(lm.tick(now: 30) == .send, "restart grace")
check(lm.tick(now: 44.9) != .lost, "not lost before 15s")
check(lm.tick(now: 45) == .lost, "lost after restart")
check(linkStatusText(.paired, ageSec: 3) == "Paired - last seen 3s ago", "text paired")
check(linkStatusText(.lost("heartbeat timeout"), ageSec: 17) == "Link lost (heartbeat timeout) - last seen 17s ago", "text lost")
check(linkStatusText(.connecting, ageSec: 4) == "Connecting", "text connecting")
check(linkStatusText(.authenticating, ageSec: nil) == "Authenticating", "text auth")
check(linkStatusText(.scanning, ageSec: nil) == "Scanning", "text scanning")

if failures == 0 { print("OK swift tests") } else { exit(1) }
