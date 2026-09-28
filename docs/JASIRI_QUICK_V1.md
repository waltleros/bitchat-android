# JASIRI Quick Message Payload v1

## Purpose

A quick message is a keyboard-less preset message, such as "Need water / Nahitaji maji", sent
with one tap from a grid of large tiles. It travels as a small structured code (a preset id),
not as chat text. Each receiving JASIRI phone renders the label locally from its built-in
catalog, in English and Swahili, so the text is the same on every phone whatever its language.

This payload carries **no signature and no sender identity**. It will be carried inside a signed
mesh packet defined in a later ticket. This document covers only the payload bytes and the
preset catalog.

Reference implementation: `app/src/main/java/com/jasiri/quick/QuickPayload.kt`
(`QuickCodec.encode` / `QuickCodec.decode`) and `QuickCatalog.kt`.

## Encoding

All multi-byte integers are big-endian.

### Header (16 bytes, always present)

| Offset | Type | Field       | Notes |
|-------:|------|-------------|-------|
| 0      | u8   | `version`   | Always `1` |
| 1      | u8   | `kind`      | `1` = QUICK. Other values are reserved for future kinds |
| 2      | 8B   | `msgId`     | Unsigned 64-bit random id, non-zero; used for de-duplication |
| 10     | u16  | `presetId`  | 1..65535 (0 is invalid). 1..999 = built-in catalog; 1000+ = reserved for mission packs |
| 12     | u32  | `timestamp` | Unix seconds, 0..4294967295 |

### Body

| Offset | Type | Field            | Notes |
|-------:|------|------------------|-------|
| 16     | u8   | `flags`          | bit0 = hasLocation, bit1 = locationApproximate; other bits written as 0 |
| 17     | i32  | `latE7`          | Only if hasLocation. Latitude × 10^7, -900000000..900000000 |
| 21     | i32  | `lonE7`          | Only if hasLocation. Longitude × 10^7, -1800000000..1800000000 |
| 25     | u16  | `accuracyMeters` | Only if hasLocation. 0..65535; 65535 = unknown |

The total length is **17 bytes** without a location and **27 bytes** with one. Unlike SOS, the
location fields are omitted entirely (not zero-filled) when there is no location.

### Encoder rules

The encoder rejects a payload (throws `IllegalArgumentException`) when:

- `msgId` is 0;
- `presetId` is outside 1..65535;
- `timestamp` is outside 0..4294967295;
- a location has `latE7` / `lonE7` out of range, or a negative accuracy.

An accuracy above 65535 is clamped to 65535. The encoder does not check `presetId` against the
catalog.

## Decode rules

A decoder never fails with an error; it returns "no payload" (`null`) when:

- the length is not exactly 17 or 27 bytes;
- `version` is not 1;
- `kind` is not 1;
- `msgId` is 0;
- `presetId` is 0;
- the hasLocation flag does not match the length (set with 17 bytes, or clear with 27 bytes);
- hasLocation is set and `latE7` or `lonE7` is out of range.

Otherwise:

- `presetId` is **not** checked against the catalog. Unknown ids decode normally, so older
  phones can show them as unknown presets (see Forward compatibility).
- locationApproximate is read only when hasLocation is set.
- Reserved `flags` bits (2..7) are ignored.

## Golden vectors

### Without location

| Field | Value |
|-------|-------|
| msgId | `0x0102030405060708` |
| presetId | 2 (Need water) |
| timestamp | `0x6AB13B80` (1790000000) |
| location | none |

```text
0101010203040506070800026ab13b8000
```

### With location

Same header, plus:

| Field | Value |
|-------|-------|
| location | latE7 = -12863890, lonE7 = 368172230, accuracy 13 m, not approximate |

```text
0101010203040506070800026ab13b8001ff3bb66e15f1dcc6000d
```

## Preset catalog v1

**Ids are permanent: never renumber or reuse one.** 1..999 are built-in presets; 1000+ are
reserved for mission packs. Display order is the order below.

`tone` drives the tile colour in the UI: INFO grey/green, NEED amber, WARNING red.
`wantsLocation` marks presets where a location is useful to the receiver; how the UI uses it is
specified with the UI.

| id | en | sw | tone | wantsLocation |
|---:|---|---|---|---|
| 1 | I'm OK | Niko salama | INFO | false |
| 2 | Need water | Nahitaji maji | NEED | true |
| 3 | Need medical help | Nahitaji msaada wa matibabu | NEED | true |
| 4 | Injured person here | Kuna majeruhi hapa | WARNING | true |
| 5 | Need food | Nahitaji chakula | NEED | true |
| 6 | Tear gas here | Kuna gesi ya kutoa machozi hapa | WARNING | true |
| 7 | Danger — avoid this area | Hatari — epuka eneo hili | WARNING | true |
| 8 | Road blocked | Barabara imefungwa | WARNING | true |
| 9 | Safe place here | Hapa ni mahali salama | INFO | true |
| 10 | I'm lost — need directions | Nimepotea — nahitaji mwelekeo | NEED | true |
| 11 | Phone battery low | Betri ya simu iko chini | INFO | false |
| 12 | On my way | Niko njiani | INFO | false |

Labels are data in code, not Android string resources, so they match on every phone regardless
of its language setting.

## Languages

- **Keys.** Each preset's labels are keyed by lowercase ISO 639-1 code (`"en"`, `"sw"`). English
  is mandatory for every preset and is the global default.
- **Lookup.** `QuickCatalog.label(id, lang)` returns the label for that exact code, or null. It
  never silently falls back to another language.
- **Complete languages.** `QuickCatalog.completeLanguages` lists the languages that have a label
  for every built-in preset. In v1 that is `en` and `sw`.
- **Display rule.** Always show the English label. Also show the label in the phone's language
  underneath it when that language is not English and is in `completeLanguages`. Otherwise show
  English only. A partially translated language is never shown, so a grid never mixes languages.
- **Adding a language** means adding label entries to the catalog. The wire format does not
  change, because payloads carry only the preset id.
- **Review.** These are safety messages. A new language must be written, or checked, by a native
  speaker before it ships. Raw machine translation is not acceptable.

## Forward compatibility

A receiver that does not know a `presetId` (a newer built-in preset or a mission pack it doesn't
have) still decodes the payload and shows "Unknown preset #N — update JASIRI", where N is the id.

## Transport (v1)

Reference implementation: `JasiriQuickPackets.kt` and the `// JASIRI` lines in
`BluetoothMeshService.kt` (send and receive), `PacketProcessor.kt` and `SecurityManager.kt`;
the flood limit is in `app/src/main/java/com/jasiri/quick/QuickInbox.kt`.

### Packet

A quick message travels in a mesh packet of type `0x41` (`JASIRI_QUICK`), broadcast to all
peers with the default mesh TTL. The packet payload is the quick payload, unchanged. The sender
refuses locally to broadcast a payload that does not decode as a valid v1 payload.

### Signing

Every `JASIRI_QUICK` packet must carry an Ed25519 signature from the sender's signing key, as
learned from a verified announcement. Unsigned packets, packets signed by a different key, and
packets from peers with no known signing key are dropped.

### Relay

Quick messages follow bitchat's normal relay policy. Unlike SOS (`0x40`), there is **no forced
relay**, so a flood cannot be amplified across the whole mesh. A packet is relayed only if the
receiver accepted it: invalid, duplicate or rate-limited messages are dropped and not relayed.

### Flood limit

Each receiver applies, in this order:

1. A payload that does not decode is rejected (invalid).
2. A message with the same sender and `msgId` as one seen in the last **10 minutes** is rejected
   (duplicate). The first-seen time is not refreshed by repeats.
3. A sender that already has **6** accepted messages in the last **60 seconds** (sliding window)
   is rejected (rate limited).
4. Otherwise the message is accepted and counts toward the sender's rate.

Duplicates and rate-limited messages do not count toward the rate. Tracking is bounded
(1,000 senders, 5,000 message ids; least recently used entries are evicted first).

### Stock bitchat

Stock bitchat phones relay packet types they do not understand, but do not display quick messages.

### Not yet supported

- Quick messages are not yet carried over Wi-Fi Aware (MeshCore).
- Storage and the UI are covered by later tickets.

## Sender and receiver behaviour (v1)

Reference implementation: `app/src/main/java/com/jasiri/quick/QuickRuntime.kt` and
`JasiriQuick.kt`.

- **Undo, then one send.** A tapped preset is queued as PENDING for 5 seconds and can be undone
  during that time. After that it is sent once, with a fresh `msgId` and the send time as its
  timestamp. There is no automatic re-broadcast. Several messages can be pending at once, each
  with its own timer.
- **Retry.** A NOT_SENT message can be retried by hand. The retry sends the same bytes, so the
  same `msgId`, and receivers that already have it treat it as a duplicate.
- **Own limit.** A phone queues at most 5 messages per 60 seconds (sliding window), which stays
  under the receivers' flood limit of 6. An undone message frees its slot; a retry uses one.
- **NOT_SENT** means no BLE peer was in range when the send was attempted (`peerGatedTransport`),
  or the mesh was not attached.
- **Feed.** Received and own messages are kept together, newest first, for 6 hours and at most
  200 entries. Pending messages are never pruned. Received messages from this phone's own peer ID
  are ignored, and each (sender, `msgId`) appears once. The unread count covers received messages
  added since the feed was last marked read.
- **Identity change.** After a panic wipe the mesh comes back with a new peer ID. On that attach,
  pending sends are cancelled, the feed is cleared and the unread count is reset. Detaching (the
  mesh going away) only drops the transport.
- **Single entry point.** Upstream `MeshServiceHolder` calls `com.jasiri.JasiriHooks`, which
  attaches and detaches every JASIRI runtime (SOS and quick messages).

## UI (v1)

Reference implementation: `app/src/main/java/com/jasiri/quick/ui/QuickGrid.kt` and
`QuickUiLogic.kt`.

- **Where.** The grid button sits beside the chat input, only in the public chat: not in
  private chats, not in named channels, and not while a voice note is recording. It is the
  single upstream edit (`InputComponents.kt`, marked `// JASIRI:`). Quick messages always go
  over the BLE mesh, whatever the chat view.
- **Hidden in location (geohash) channels,** since those go over the internet rather than the
  Bluetooth mesh. Tapping a warning alert while in one switches back to the mesh chat and opens
  the sheet.
- **One tap, then undo.** Tapping a tile queues the message and shows an amber bar for 5 seconds
  ("Sending "…" in N s" with UNDO). The sheet stays open so the bar stays visible. If the phone
  is over its own limit, a toast says so and nothing is queued.
- **Tiles.** Twelve tiles in catalog order, coloured by tone: INFO neutral, NEED amber with black
  text, WARNING red with white text. Each shows the English label in bold, with a second
  language underneath: the phone language if the catalog is complete in it, otherwise Swahili
  (Kenya-first default). Screen readers hear both labels.
- **Location.** "Include my location" is on by default and applies only to presets that want a
  location. It uses the last-known fix only: no fresh GPS request and no permission prompt from
  this sheet. The fix is marked approximate when only coarse permission is granted. Without a
  fix or permission, the message goes without a location.
- **Feed.** Own and received messages, newest first, each with a tone colour bar, the labels,
  "You" or the sender's nickname, and how long ago it was heard. A location shows as
  coordinates with accuracy and an "Open in map" link. Own messages show "Sending in N s" with
  Undo, "Sent", or "Not sent — no phones in range" with Retry. Unknown preset ids show
  "Unknown preset #N — update JASIRI".
- **Unread badge.** A red badge on the button counts unread received messages ("9+" above 9).
  It is cleared when the sheet opens, and messages arriving while it is open are marked read.

## Alerts (v1)

Reference implementation: `app/src/main/java/com/jasiri/alerts/AlertPlanner.kt` and
`JasiriAlerts.kt`, started from `BitchatApplication`, so alerts work with the app closed.

- **WARNING only.** Received messages whose preset tone is WARNING (ids 4, 6, 7, 8) alert. Own
  messages, NEED and INFO messages, and unknown preset ids never alert. Each message alerts at
  most once.
- **One grouped notification.** The "Danger warnings" channel shows one notification listing the
  latest 5 warnings, newest first. A single warning shows the English label as the title and
  "Swahili label · sender" below; several show a count and one "English label · sender" line each.
- **Vibration.** It vibrates at most once every 20 seconds; updates in between are silent.
- **Cleared on open.** Opening the quick sheet (unread count back to 0) clears the notification
  and its list.
- **Taps.** Tapping opens the app with the quick sheet open (once the public chat, where the
  grid button lives, is showing).
- **Notifications off.** If notifications are off or not permitted, the phone only vibrates,
  following the same 20-second limit.
- **Muting.** Users can mute the "Danger warnings" channel in Android settings.
