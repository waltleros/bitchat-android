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
