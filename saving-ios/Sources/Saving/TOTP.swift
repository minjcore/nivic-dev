#if os(iOS)
import Foundation
import CryptoKit

// RFC 6238 TOTP using HMAC-SHA256.
// Secret is 20 random bytes, stored in UserDefaults as Base32.
// QR enrollment: saving://totp-enroll?uid=XXXX&secret=BASE32

// ─── Base32 ───────────────────────────────────────────────────────────────────

private let b32Chars = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")

func base32Encode(_ data: Data) -> String {
    var result = ""
    var buffer = 0; var bitsLeft = 0
    for byte in data {
        buffer = (buffer << 8) | Int(byte)
        bitsLeft += 8
        while bitsLeft >= 5 {
            bitsLeft -= 5
            result.append(b32Chars[(buffer >> bitsLeft) & 0x1f])
        }
    }
    if bitsLeft > 0 { result.append(b32Chars[(buffer << (5 - bitsLeft)) & 0x1f]) }
    return result
}

func base32Decode(_ input: String) -> Data? {
    var data = Data()
    var buffer = 0; var bitsLeft = 0
    for c in input.uppercased() {
        guard let idx = b32Chars.firstIndex(of: c) else { continue }
        buffer = (buffer << 5) | idx
        bitsLeft += 5
        if bitsLeft >= 8 {
            bitsLeft -= 8
            data.append(UInt8((buffer >> bitsLeft) & 0xff))
        }
    }
    return data.isEmpty ? nil : data
}

// ─── TOTP ─────────────────────────────────────────────────────────────────────
// Produces a 32-char Base32 token: base32(HMAC-SHA256(secret, counter)[0:20])
// This is time-based (30 s window) and encodes to a scannable QR.
// QR format: saving://totp-pay?uid=XXXX&token=32CHARTOKEN

enum TOTP {
    static let step: Int64 = 30

    static func code(secret: Data, at date: Date = Date()) -> String {
        let counter = Int64(date.timeIntervalSince1970) / step
        var bigEndian = counter.bigEndian
        let msg = Data(bytes: &bigEndian, count: 8)
        let key = SymmetricKey(data: secret)
        let mac = HMAC<SHA256>.authenticationCode(for: msg, using: key)
        let truncated = Data(mac.prefix(20))        // 20 bytes → 32 Base32 chars
        return base32Encode(truncated)
    }

    static func verify(secret: Data, token: String, at date: Date = Date(), drift: Int64 = 1) -> Bool {
        let base = Int64(date.timeIntervalSince1970) / step
        for delta in -drift...drift {
            var be = (base + delta).bigEndian
            let msg = Data(bytes: &be, count: 8)
            let key = SymmetricKey(data: secret)
            let mac = HMAC<SHA256>.authenticationCode(for: msg, using: key)
            if base32Encode(Data(mac.prefix(20))) == token.uppercased() { return true }
        }
        return false
    }

    static func secondsRemaining(at date: Date = Date()) -> Int {
        let elapsed = Int(date.timeIntervalSince1970) % Int(step)
        return Int(step) - elapsed
    }
}

// ─── TOTPManager ──────────────────────────────────────────────────────────────

class TOTPManager: ObservableObject {
    static let shared = TOTPManager()

    @Published var code    = ""
    @Published var remaining: Int = 0

    private let secretKey = "totp_secret_b32"
    private var timer: Timer?

    private(set) var secret: Data

    init() {
        let ud = UserDefaults.standard
        if let b32 = ud.string(forKey: secretKey), let s = base32Decode(b32) {
            secret = s
        } else {
            var bytes = [UInt8](repeating: 0, count: 20)
            _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
            secret = Data(bytes)
            ud.set(base32Encode(secret), forKey: secretKey)
        }
        refresh()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.refresh()
        }
    }

    private func refresh() {
        let now = Date()
        code      = TOTP.code(secret: secret, at: now)
        remaining = TOTP.secondsRemaining(at: now)
    }

    func enrollmentURL(uid: UInt32) -> String {
        "saving://totp-enroll?uid=\(uid)&secret=\(base32Encode(secret))"
    }

    func paymentURL(uid: UInt32) -> String {
        "saving://totp-pay?uid=\(uid)&token=\(code)"
    }
}
#endif
