import Foundation

/// Frame: 1 header byte + up to 19 payload bytes. Header bit7 START, bit6 END, bits0-5 seq (mod 64).
/// Message bytes = u16 big-endian length + JSON. Mirrors Framer.java.
enum Framing {
    static let maxPayload = BridgeProtocol.frameSize - 1

    static func chunk(_ json: Data) -> [Data] {
        guard json.count <= BridgeProtocol.maxMessage else { return [] }
        var buf = Data([UInt8((json.count >> 8) & 0xff), UInt8(json.count & 0xff)])
        buf.append(json)
        var out: [Data] = []
        var off = 0
        var seq = 0
        while off < buf.count {
            let n = min(maxPayload, buf.count - off)
            var h = UInt8(seq & 0x3f)
            if off == 0 { h |= 0x80 }
            if off + n == buf.count { h |= 0x40 }
            var f = Data([h])
            f.append(buf.subdata(in: off..<(off + n)))
            out.append(f)
            off += n
            seq += 1
        }
        return out
    }

    struct Reassembler {
        private var buf = Data()
        private var active = false
        private var nextSeq = 0
        private(set) var errors = 0

        /// Returns the complete JSON bytes when the END frame arrives, else nil.
        mutating func feed(_ frame: Data) -> Data? {
            if frame.count < 2 || frame.count > BridgeProtocol.frameSize { fail(); return nil }
            let bytes = [UInt8](frame)
            let h = Int(bytes[0])
            let seq = h & 0x3f
            if h & 0x80 != 0 {
                buf = Data()
                active = true
                nextSeq = 0
            } else if !active {
                errors += 1
                return nil
            }
            if seq != nextSeq { fail(); return nil }
            nextSeq = (nextSeq + 1) & 0x3f
            buf.append(contentsOf: bytes[1...])
            if buf.count > BridgeProtocol.maxMessage + 2 { fail(); return nil }
            if h & 0x40 == 0 { return nil }
            let all = [UInt8](buf)
            active = false
            buf = Data()
            if all.count < 2 { errors += 1; return nil }
            let len = (Int(all[0]) << 8) | Int(all[1])
            if len != all.count - 2 { errors += 1; return nil }
            return Data(all[2...])
        }

        private mutating func fail() {
            errors += 1
            active = false
            buf = Data()
        }
    }
}
