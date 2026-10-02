# JASIRI SOS Payload v1

## Purpose

An SOS payload is the small fixed-layout byte record that describes an emergency
beacon and the replies to it (acknowledge, claim, cancel, resolve). It is designed
to fit comfortably inside one mesh packet and to be decodable by any JASIRI build.

This payload carries **no signature and no sender identity**. It is carried inside
a signed mesh packet defined in a later ticket; that packet provides signing and
relay. This document covers only the payload bytes.

Reference implementation: `app/src/main/java/com/jasiri/sos/SosPayload.kt`
(`SosCodec.encode` / `SosCodec.decode`).

## Encoding

All multi-byte integers are big-endian.

### Common header (16 bytes, every payload)

| Offset | Type | Field       | Notes |
|-------:|------|-------------|-------|
| 0      | u8   | `version`   | Always `1` |
| 1      | u8   | `kind`      | `1`=SOS, `2`=ACK, `3`=CLAIM, `4`=CANCEL, `5`=RESOLVE |
| 2      | 8B   | `sosId`     | Unsigned 64-bit id chosen by the SOS originator |
| 10     | u16  | `seq`       | Update counter, 0..65535 |
| 12     | u32  | `timestamp` | Unix seconds, 0..4294967295 |

ACK, CLAIM, CANCEL and RESOLVE are header only: 16 bytes total.

### SOS body (17 bytes, kind = SOS only; 33 bytes total)

| Offset | Type | Field            | Notes |
|-------:|------|------------------|-------|
| 16     | u8   | `category`       | `0`=GENERAL `1`=MEDICAL `2`=TRAPPED `3`=FIRE `4`=VIOLENCE `5`=DETAINED `6`=MISSING_PERSON `255`=OTHER |
| 17     | u8   | `severity`       | 1..3, where 3 is most severe |
| 18     | u8   | `flags`          | bit0 = hasLocation, bit1 = locationApproximate; other bits written as 0 |
| 19     | i32  | `latE7`          | Latitude × 10^7, -900000000..900000000; 0 when hasLocation = 0 |
| 23     | i32  | `lonE7`          | Longitude × 10^7, -1800000000..1800000000; 0 when hasLocation = 0 |
| 27     | u16  | `accuracyMeters` | 0..65535; larger values are clamped to 65535 |
| 29     | u16  | `fixAgeSeconds`  | 0..65535; larger values are clamped to 65535 |
| 31     | u8   | `batteryPercent` | 0..100, or 255 = unknown |
| 32     | u8   | `peopleCount`    | 0 = unknown, 1..255 |

When there is no location, `flags` bits 0 and 1 are both 0 and bytes 19..30 are all zero.

### Encoder rules

The encoder rejects a payload (throws `IllegalArgumentException`) when:

- a body is present for a non-SOS kind, or missing for SOS;
- `seq` is outside 0..65535 or `timestamp` outside 0..4294967295;
- `severity` is outside 1..3;
- a location has `latE7` / `lonE7` out of range, or a negative accuracy or fix age;
- `batteryPercent` is not null and outside 0..100;
- `peopleCount` is not null and outside 1..255 (use null for unknown).

## Decode rules

A decoder never fails with an error; it returns "no payload" (`null`) when:

- the input is shorter than the size required for its kind (16, or 33 for SOS);
- `version` is not 1;
- `kind` is not one of 1..5;
- `severity` is outside 1..3;
- hasLocation is set and `latE7` or `lonE7` is out of range;
- `batteryPercent` is outside 0..100 and not 255.

Otherwise:

- Unknown `category` codes decode as OTHER.
- Bytes beyond the required length are ignored.
- hasLocation = 0 means no location; the coordinate, accuracy and fix-age bytes and
  the locationApproximate bit are ignored.
- `batteryPercent` 255 decodes as unknown; `peopleCount` 0 decodes as unknown.
- Reserved `flags` bits (2..7) are ignored.

## Golden vectors

### SOS

| Field | Value |
|-------|-------|
| kind | SOS |
| sosId | `0x0102030405060708` |
| seq | 1 |
| timestamp | 1790000000 |
| category | MEDICAL |
| severity | 2 |
| location | latE7 = -12921000, lonE7 = 368219000, accuracy 15 m, fix age 30 s, not approximate |
| batteryPercent | 64 |
| peopleCount | 1 |

```text
0101010203040506070800016ab13b80010201ff3ad75815f29378000f001e4001
```

### CLAIM

| Field | Value |
|-------|-------|
| kind | CLAIM |
| sosId | `0x0102030405060708` |
| seq | 2 |
| timestamp | 1790000060 |

```text
0103010203040506070800026ab13bbc
```

## Transport (v1)

### Packet

| Field | Value |
|-------|-------|
| Packet type | `0x40` (`MessageType.JASIRI_SOS`) |
| Recipient | Broadcast (`FFFFFFFFFFFFFFFF`) |
| Payload | The `SosCodec` bytes described above, unchanged |
| TTL | Normal mesh TTL; each relay decrements it by 1 and a packet with TTL 0 is not relayed |

### Signing and validation

- The sender signs every SOS packet with its Ed25519 signing key, using the same
  packet signature as other signed mesh types.
- A receiver drops an SOS packet, and does not relay it, when:
  - the packet has no signature;
  - the sender has no verified signing key yet (no verified announce has been received);
  - the signature does not verify;
  - the payload does not decode as a valid v1 payload.
- Valid SOS payloads are published in-process through `com.jasiri.sos.SosInbox`
  (`app/src/main/java/com/jasiri/sos/SosInbox.kt`).

### Relay

- JASIRI relays a valid SOS unconditionally. It is never dropped by the probabilistic
  relay reduction used for other traffic in large networks.
- Stock bitchat Android and iOS clients do not understand type `0x40`. They relay unknown
  broadcast types as usual without displaying them, so they still carry SOS across the mesh.

### Sending

The sender builds a broadcast `JASIRI_SOS` packet with TTL 7, signs it with its Ed25519
signing key and broadcasts it over BLE. A payload that does not decode as a valid v1 payload
is refused locally and never sent. Sending over Wi-Fi Aware is not yet supported.

### Not yet supported

- SOS is not yet carried over Wi-Fi Aware (MeshCore). This is planned for a later version.

## Sender behaviour (v1)

A phone manages at most one SOS of its own at a time. Reference implementation:
`app/src/main/java/com/jasiri/sos/OwnSosController.kt`.

### States

`IDLE` → `ACTIVE` → either `CANCELLING` → `CANCELLED`, or `EXPIRED`. From `CANCELLING`,
`CANCELLED` or `EXPIRED` the phone can start a new SOS; from `CANCELLED` or `EXPIRED` it can
also reset to `IDLE`.

### Rules

1. **Start.** From `IDLE`, `CANCELLED` or `EXPIRED`, starting creates a new random non-zero
   `sosId` with `seq` 0, sends immediately and begins re-broadcasting. Starting while `ACTIVE`
   is an update. Starting an SOS while a previous one is still cancelling begins a new SOS
   (new sosId) and stops the remaining CANCEL repeats.
2. **Payload.** Every SOS send uses `kind` = SOS, the current `sosId`, `seq` and body, and
   `timestamp` = the sender's clock in seconds at the moment of sending. A body that cannot be
   encoded is rejected before any state changes.
3. **Re-broadcast.** While `ACTIVE`, the SOS is re-sent every 30 s during the first 10 minutes
   after start, then every 120 s. After a failed send the next attempt is 10 s later. Six hours
   after start the SOS becomes `EXPIRED` and sending stops; no CANCEL is sent.
4. **Update.** Only while `ACTIVE`: `seq` increases by 1 (wrapping at 65536), the new body is
   sent immediately and the re-broadcast timer restarts from now. The fast/slow phase is still
   measured from the original start.
5. **Cancel.** Only while `ACTIVE`: re-broadcasting stops and a CANCEL payload (same `sosId`,
   `seq` + 1, no body) is sent immediately and then twice more, 30 s apart, with the same `seq`.
   The state is `CANCELLING` until the last CANCEL attempt, then `CANCELLED`. Failed CANCEL
   sends are not retried sooner.
6. **Send status.** Each attempt records its time. A successful hand-off to the transport
   increments the success count, resets the consecutive-failure count and clears the "not sent"
   warning. A refused or failed hand-off increments the consecutive-failure count and sets the
   warning. "Successful" means queued for broadcast, not delivered to anyone.
7. **Signed or not sent.** The packet is signed before the send call returns. If signing fails,
   the packet is not broadcast and the send reports failure (NOT SENT), because receivers drop
   unsigned JASIRI packets.

### Default timings

| Setting | Default |
|---------|---------|
| Fast re-broadcast interval | 30 s |
| Fast phase length (from start) | 10 min |
| Slow re-broadcast interval | 120 s |
| Retry after a failed send | 10 s |
| SOS lifetime (from start) | 6 h |
| CANCEL repeats | 3 |
| Interval between CANCEL repeats | 30 s |

## Receiver behaviour (v1)

Each phone keeps a board of other people's SOS, built from received, signature-verified
payloads. Reference implementation: `app/src/main/java/com/jasiri/sos/SosBoard.kt`.

The "sender" of a payload below is the verified mesh peer that signed the packet. Payloads that
appear to come from this phone itself are ignored. The **origin** of an SOS is the peer that sent
the first SOS payload the board saw for that `sosId`.

### Rules

1. **SOS.**
   - An unknown `sosId` creates a new `ACTIVE` entry, with the sender as origin.
   - An SOS for a known `sosId` from anyone other than the origin is ignored.
   - From the origin, while the entry is `ACTIVE`:
     - a newer `seq` replaces the body, `seq` and timestamp;
     - an equal `seq` is a re-broadcast and only refreshes "last heard";
     - an older `seq` is ignored.
   - "Newer" uses 16-bit serial arithmetic: `seq` b is newer than a when
     `(b − a) mod 65536` is in 1..32767, so 65535 → 0 counts as newer.
   - An SOS for a `CANCELLED` or `RESOLVED` entry is ignored.
2. **ACK.** Recorded when the entry is `ACTIVE` and the sender is not the origin.
3. **CLAIM.** Recorded, as both a claim and an acknowledgement, when the entry is `ACTIVE` and
   the sender is not the origin.
4. **CANCEL.** Closes the entry as `CANCELLED` only when it is `ACTIVE` and the sender is the origin.
5. **RESOLVE.** Closes the entry as `RESOLVED` only when it is `ACTIVE` and the sender is the
   origin or a peer that has already claimed it.
6. **Unknown `sosId`.** ACK, CLAIM, CANCEL or RESOLVE for an `sosId` the board has not seen
   an SOS for is dropped.

### Why only the origin can change an SOS

Any mesh peer can sign packets, and `sosId` values are visible to everyone who hears the SOS.
Without these rules a malicious peer could:

- **Hijack an SOS:** send an SOS with a higher `seq` under someone else's `sosId` and move the
  location or change the details.
- **Fake a cancellation:** send a CANCEL (or an unearned RESOLVE) to make responders stop looking.

Binding the SOS to the peer that first sent it, and requiring a prior CLAIM for a responder's
RESOLVE, prevents both.

### Responder actions

A phone can acknowledge, claim or resolve an `ACTIVE` SOS. Resolving requires that this phone
has claimed it first. The phone sends a payload with that kind, the same `sosId`, `seq` 0, the
current time and no body. It updates its own board immediately, whether or not the send succeeds.

### Staleness and retention (defaults)

| Rule | Default |
|------|---------|
| An `ACTIVE` entry is marked stale when no SOS payload was heard for | 15 min |
| An `ACTIVE` entry is removed when no SOS payload was heard for | 6 h |
| A `CANCELLED` or `RESOLVED` entry is removed after its state change by | 1 h |
| Maximum entries (the entry heard least recently is dropped first) | 500 |

Entries are listed with active, non-stale SOS first, then stale ones, then closed ones. Within
each group the highest severity comes first, then the most recently heard.

### Limitations

- Responses that arrive before the SOS they refer to are dropped (rule 6). They are not buffered.
- An entry that has been removed is forgotten. If a late re-broadcast of a removed SOS arrives,
  it creates a new `ACTIVE` entry. This includes a cancelled SOS whose 1-hour retention has passed.
- A send is counted as successful when at least one BLE peer is "active", meaning a peer seen
  recently. That still does not prove this particular packet was received; delivery confirmation
  comes from ACK/CLAIM.

## App runtime (v1)

Reference implementation: `app/src/main/java/com/jasiri/sos/SosRuntime.kt` and `JasiriSos.kt`.

- **One runtime per process.** The `JasiriSos` singleton owns one `SosRuntime`. The runtime holds
  this phone's own SOS controller and the board of other people's SOS, and shares one sender
  between them.
- **Attach and detach follow the mesh.** `MeshServiceHolder` attaches the runtime whenever the
  mesh is created or reused, passing the mesh peer ID and its SOS send function. It detaches
  when the mesh is cleared.
  - While detached, the board, its entries and the own SOS are kept. Own re-broadcasts fail, and
    are retried every 10 s, until the next attach.
  - Re-attaching with the same peer ID keeps everything.
- **Identity change.** After a panic wipe the mesh comes back with a new peer ID. On the attach
  that brings the new ID:
  - the own SOS is abandoned without sending a CANCEL, because receivers would reject a CANCEL
    that isn't signed by the SOS's origin;
  - the board is discarded and a fresh, empty board starts under the new ID.
- **Ticker.** The runtime ticks the board every 30 s to update stale flags and prune old entries.
- **Responder actions refuse to run while detached.** Acknowledge, claim and resolve return false
  and change nothing, so the board never records an action that could not be sent.
- **A send counts only when someone is in range.** A send counts as successful only when it was
  queued AND at least one BLE peer is active (`peerGatedTransport`). With nobody in range the SOS
  stays ACTIVE, shows NOT SENT, and retries every 10 s.
- **Early events are lost.** `SosInbox` does not replay, so SOS received before the first attach,
  or before a new board's collector has subscribed, are not seen by the board.

## UI (v1)

Reference implementation: `app/src/main/java/com/jasiri/sos/ui/SosScreen.kt`, with the pure display
logic in `SosUiLogic.kt`.

- **Header button.** A red "SOS" pill in the main chat header opens the full-screen SOS page.
  - It is outlined when this phone has no live SOS, and filled red with a slow pulse while the
    own SOS is `ACTIVE` or `CANCELLING`.
  - A red badge shows the number of received SOS that are `ACTIVE` and not stale, capped at "9+".
- **Sending.** The user picks a category (default General) and holds the big red button for 3 s.
  - Releasing early cancels and sends nothing. Completing the hold vibrates and starts the SOS
    exactly once.
  - The body uses default severity 3, the phone's battery level when readable, and no location
    or people count yet.
- **While active.** A status card shows how many times the SOS was sent and when it was last sent,
  or an amber "not sent" warning while every attempt is failing.
  - Changing the category sends an update of the same SOS.
  - "I'm safe — cancel SOS" asks for confirmation before cancelling.
  - After cancelling, the SOS controls are shown again immediately with a 'SOS cancelled' line;
    there is no OK step. The same applies after the SOS expires.
- **Received list.** Each nearby SOS shows its category, the sender's nickname (or the first
  8 characters of the peer ID), when it was last heard, a "not heard recently" chip when stale,
  and the acknowledge and responding counts. Closed entries are dimmed.
  - Actions: **Seen** (acknowledge), **I'm responding** (claim) and **Mark resolved** (resolve,
    after confirmation). Only the actions valid for this phone are shown.
  - If an action cannot be sent, because the mesh is not attached or because no phone is in
    range, a "Not sent — no phones in range" toast appears and nothing changes.
- **Strings.** All texts are in `res/values/jasiri_strings.xml`, English only in v1.

## Location (v1)

Reference implementation: `app/src/main/java/com/jasiri/sos/SosLocationMath.kt` (conversion to the
wire type), `location/SosLocationSource.kt` (plain `LocationManager`) and `ui/SosScreen.kt`.

- **Precise by default, per SOS.** The SOS page has a "Share my location" switch, on by default.
  The person can turn it off before firing or at any time while the SOS is active.
  - Turning it off while active sends an update with no location.
  - Turning it on while active asks for the location permission if needed, then sends a fresh fix.
  - Without FINE permission (COARSE only), fixes are sent with `approximate` set.
- **The SOS is never delayed.** It fires immediately with the best last-known fix (or none), then
  a fresh fix is requested and sent as an update of the same SOS. If the permission is missing,
  the SOS fires first and the permission dialog follows.
- **Permanently denied.** If location permission was permanently denied, the location line offers
  'open Settings' instead of a dialog that Android would no longer show.
  - It counts as permanently denied when the SOS page has asked before (remembered in
    `jasiri_prefs`, key `loc_perm_asked`) and Android no longer wants a rationale shown.
  - Coming back from Settings re-checks the permission. If it was granted while an SOS is active
    and sharing is on, a fresh fix is fetched straight away.
- **Refresh.** While the SOS is active, the location is refreshed every 2 minutes, whether or not
  the SOS page is open (see "Location while the SOS page is closed").
- **Known limit.** `fixAgeSeconds` in re-broadcasts is the fix age at the last update, not at
  re-broadcast time, so a receiver sees the fix as younger than it is.
- **Separate from location channels.** SOS location does not use the upstream geohash providers
  or their privacy gate (`LiveLocationPrivacyGate`); it is its own explicit per-SOS choice.
- **Responders.** A received SOS with a location shows its coordinates, accuracy and fix age, and
  an "Open in map" button that opens a `geo:<lat>,<lon>?q=<lat>,<lon>(SOS)` intent in any
  installed map app.
- **Privacy.** An SOS is signed but not encrypted: its location is readable by anyone in range.

## Location while the SOS page is closed (v1)

Reference implementation: `app/src/main/java/com/jasiri/sos/location/SosLocationKeeper.kt`, with
the rules in `SosLocationPolicy.kt`. Started from `BitchatApplication`.

- **Background refresh.** While your SOS is active and location sharing is on, the phone refreshes
  the location every 2 minutes in the background, through the mesh foreground service. The first
  refresh comes 2 minutes after firing; the SOS page still fetches immediately on fire and when
  sharing is turned on.
- **Never cleared by a failure.** A failed GPS attempt keeps the last location; it is never cleared.
  A fresh fix replaces it only if it is newer by more than 5 s, or the same age and more accurate.
- **Off means off.** Turning "Share my location" off stops the refreshes for that SOS.
- **No prompts.** The keeper never asks for permission. Without permission, or with location
  turned off, it simply skips.

## Alerts (v1)

Reference implementation: `app/src/main/java/com/jasiri/alerts/AlertPlanner.kt` (what to alert)
and `JasiriAlerts.kt` (notifications), started from `BitchatApplication`, so alerts work while the
mesh runs in the background with the app closed.

- **Received SOS.** Each new ACTIVE SOS from another phone posts one high-priority notification
  on the "SOS alerts" channel, with a long vibration. The title names the category and the text
  names the sender. A sosId alerts at most once.
- **Removed when closed.** The notification is removed when that SOS is cancelled or resolved,
  or drops off the board. A stale SOS keeps its notification.
- **Taps.** Tapping opens the app with the SOS page open. If the SOS has a location, an
  "Open in map" action opens it in a map app (through the system chooser when Android hides
  which map apps are installed).
- **Never for own.** This phone's own SOS never alerts.
- **Notifications off.** If notifications are off or not permitted, the phone only vibrates,
  once per new SOS.
- **Muting.** Users can mute the "SOS alerts" channel in Android settings.
- **Debug builds.** Long-pressing the SOS header pill posts one sample SOS and one sample warning
  notification, without touching the SOS board.

## Distance and direction (v1)

- **Where it shows.** A received SOS that carries a location shows one bold line above its
  coordinates, for example "≈350 m north-east of you". The SOS notification shows the same text:
  "From Amina · ≈350 m north-east of you · tap to respond".
- **Computed locally, never sent.** The line uses this phone's last-known location (no new fix is
  requested for it). This phone's location is not added to any packet.
- **Maths.** Distance uses the haversine formula (Earth radius 6,371 km). Direction is the initial
  bearing from you to them, shown as one of 8 words: north, north-east, east, south-east, south,
  south-west, west, north-west.
- **Rounding.** Under 1 km the distance is rounded to the nearest 10 m (minimum "10 m"). From 1 km
  to under 10 km it shows one decimal ("1.2 km"), and from 10 km whole kilometres ("14 km").
- **Very close.** If the distance is within the combined accuracy of both fixes, the card shows
  "Very close — within ~N m" with no direction. An unknown accuracy counts as 100 m, the combined
  figure is at least 20 m, and N is rounded up to a multiple of 10.
- **Hidden.** No line is shown (and the notification keeps its plain text) when this phone has no
  location permission or no last-known fix, or when the SOS has no location.
