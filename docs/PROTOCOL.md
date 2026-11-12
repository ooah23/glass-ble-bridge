# GlassBridge protocol v1

iPhone = BLE peripheral (CoreBluetooth). Glass = BLE central (Android 4.4, API 19).

## GATT

| Item | UUID | Properties | Direction |
|---|---|---|---|
| Service | `8a6f0000-5b1e-4c3a-9d2f-7e1b0c4a6d10` | primary | |
| RX | `8a6f0001-...` | write, write-without-response, encrypted write | Glass to phone |
| TX | `8a6f0002-...` | notify, encrypted read (CCCD `0x2902`) | phone to Glass |

Advertisement: local name `GlassBridge` plus the 128-bit service UUID. iOS cannot advertise manufacturer data, so there is no manufacturer marker; Glass matches on the service UUID or the exact local name. iOS drops the local name and moves the service UUID to the overflow area while the app is in the background, so the first connection is easiest with the app in the foreground.

Link security: both characteristics need an encrypted link, so the first access triggers iOS pairing (accept the prompt on the phone) and the bond is reused after. Application-level auth below applies regardless of the bond.

## Framing

Each BLE payload is a frame of at most 20 bytes (default MTU 23): 1 header byte + up to 19 payload bytes.

Header: `bit7 START | bit6 END | bits0-5 SEQ`. SEQ is 0 on the START frame and increments by 1 mod 64 per frame.

A message is `u16 big-endian length` + `UTF-8 JSON` (max 4096 bytes), split across frames in order. The receiver resets on START, rejects a frame whose SEQ is not the expected one (drops the message, resyncs on the next START), and on END checks that the length prefix equals the bytes received.

Vector: `{"t":"card","text":"Hello Glass"}` becomes two frames
`8000217b2274223a2263617264222c2274657874` and `41223a2248656c6c6f20476c617373227d`. Both sides test this vector.

## Authentication

1. Glass subscribes to TX, then sends `{"t":"hello"}`.
2. Phone replies `{"t":"chal","n":"<32 hex chars>"}` (16 random bytes).
3. Glass sends `{"t":"auth","h":"<hex>"}` where `h = HMAC-SHA256(key = secret UTF-8, msg = n UTF-8)`, lowercase hex.
4. Phone compares in constant time, then sends `{"t":"ok"}` or `{"t":"deny"}`. Until `ok`, the phone queues outbound messages (max 20) and ignores Glass commands; Glass ignores everything except `chal`, `ok`, `deny`.

The nonce is single-use. The secret is a string you pick: typed into the iPhone app once (stored in the keychain) and placed on the Glass at `/sdcard/glass-bridge/secret` with adb. It is never in the APK or the repo.

## Messages (JSON, key `t` = type)

Phone to Glass:

- `{"t":"card","id":"<uuid>","title":"","text":"short text","ttl":0}` show a card (wakes the screen).
- `{"t":"now_playing","title":"..","artist":"..","state":"playing|paused|stopped"}`
- `{"t":"chal"}`, `{"t":"ok"}`, `{"t":"deny"}` as above.

Glass to phone:

- `{"t":"hello"}`, `{"t":"auth","h":".."}`
- `{"t":"cmd","c":"play|pause|next|prev|vol_up|vol_down"}` logged by the app (iOS gives third-party apps no control over other apps' media; a hub can act on them).

Unknown `t` values are ignored, so the protocol can grow.

## Glass behavior

Scan unfiltered in 2 s windows (8 s off) (API 19 `startLeScan`), match by parsing the scan record, connect, discover, subscribe, authenticate. Any failure or disconnect closes GATT and retries after 2, 4, 8 ... 30 s (reset after a successful auth). While connected there is no scanning. A rejected secret retries every 30 s.

Controls: tap = play/pause, swipe forward/back = next/prev, up/down = volume (key mapping is untested on hardware).

## Heartbeat and link state (v1.1)

After auth each side sends `{"t":"hb","n":<counter>}` every 5 s (counter starts at 1 per link, per side). The receiver answers `{"t":"hback","n":<same n>}`. Either frame counts as proof of life. If neither arrives for 15 s the link is lost: Glass does disconnect, GATT cache refresh, close, rescan; the iPhone marks the link lost, drops subscriber state and re-advertises. Both screens derive their state from these frames, not from the BLE connection flag alone. Unknown `t` values are still ignored, so v1 peers keep working (they just cannot detect a dead link).

Displayed states (same wording on both ends): `Scanning` (Glass scanning / iPhone advertising), `Connecting`, `Authenticating`, `Paired - last seen Ns ago`, `Link lost (reason) - last seen Ns ago`. Reasons: `heartbeat timeout 15s`, `gatt 8 supervision timeout`, `gatt 19 remote terminated`, `gatt 22 local terminated`, `gatt 133 GATT_ERROR`, `Glass unsubscribed`, `Bluetooth is off`, `wrong pair code`, `secret rejected`.

Glass radio hygiene: one scan or GATT at a time; scan 2 s on / 8 s off while searching; scanning stops once a match is found or connecting; `connectGatt` at least 5 s apart; failure backoff 2/4/8..30 s; the scan callback only compares name/UUID. The iPhone uses CoreBluetooth state restoration (`dev.glassbridge.peripheral`), `bluetooth-peripheral` background mode, and re-advertises on powered-on, foreground and unsubscribe. The pair code stays in the keychain.
