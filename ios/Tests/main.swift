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

if failures == 0 { print("OK swift tests") } else { exit(1) }
