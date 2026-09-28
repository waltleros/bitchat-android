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

## Not yet specified

- Transport, signing, relay, rate limiting and the UI are covered by later tickets.
- Stock bitchat phones will not display quick messages.
- Transport will follow the SOS approach (a new packet type), so phones that don't understand
  the type still relay it.
