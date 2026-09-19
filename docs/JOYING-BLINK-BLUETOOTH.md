# JOYING / Zjinnova `blink` Bluetooth Transport

This document records the Bluetooth interface observed on one QZD G6 / JOYING
head unit using Zjinnova-based Android 10 firmware. The behavior appears to be
proprietary to this vendor firmware. It is not part of Android's Bluetooth API,
and its presence or wire format must not be assumed on other JOYING models,
firmware versions, or head-unit brands.

No vendor binary, key, certificate, or proprietary source code is included in
this repository. The integration is a clean client for the small protocol
surface observed on hardware owned by the tester.

## Why the transport is needed

On the tested unit, Apple's CarPlay RFCOMM service is not consistently usable
through Android's public `BluetoothSocket` path. A preinstalled vendor service
named `blink` owns part of the Bluetooth stack and exposes a TCP service on the
Android loopback interface:

```text
127.0.0.1:3152
```

The ZLINK user interface does not have to be open for this endpoint to work,
but the underlying JOYING/Zjinnova Bluetooth service must remain running.
Removing or stopping vendor Bluetooth components can therefore make the
endpoint disappear.

## Observed ZBT framing

Each message has a 16-byte, big-endian header followed by the declared payload:

| Offset | Size | Observed meaning |
| --- | ---: | --- |
| `0x00` | 4 | Magic `0x0000ffff` |
| `0x04` | 4 | Protocol/version `0x00000101` |
| `0x08` | 4 | Message identifier |
| `0x0c` | 4 | Payload length |

The subset used by this fork is:

| Message | Purpose |
| --- | --- |
| `0x101` | Initialize the CarPlay Bluetooth transport |
| `0x105` | Carry bidirectional RFCOMM/iAP2 bytes |
| `0x106` | Disconnect the transport |

The observed initialization body is the five-byte protobuf payload
`08 81 02 10 01`, interpreted by the implementation as an initialization
request selecting the CarPlay transport. The client limits packet and queued
data sizes, validates every header, and uses `TCP_NODELAY` on the loopback
socket.

Implementation: [`ZjinnovaZbtDuplexStream.kt`](../shared/src/main/java/com/shilapi/xcertplay/transport/ZjinnovaZbtDuplexStream.kt)

## Selection and fallback

Wireless startup first probes the loopback endpoint. If it is unavailable, the
app falls back to Android Bluetooth by trying the public CarPlay RFCOMM UUID,
then direct RFCOMM channel 1 with the vendor registration trigger where the
firmware supports it. This fallback is important for non-JOYING devices and
JOYING firmware revisions without `blink`.

Bluetooth is only the bootstrap/control path. After the iPhone establishes the
wireless AirPlay tunnel, audio, video, timing, and touch traffic use Wi-Fi and
the bootstrap Bluetooth stream can close.

## Root requirements

Connecting to `127.0.0.1:3152` does not inherently require root. It works for
an ordinary application only when the installed vendor service is running and
permits the connection. Root may be needed elsewhere on the same head unit for
the onboard MFi I2C device or protected system-hotspot controls; those are
separate from the `blink` protocol.

## Known limits

- The protocol was observed on one firmware family and is not claimed to be a
  complete specification.
- Only the message subset required for the CarPlay bootstrap is implemented.
- Vendor updates may change the port, framing, permissions, or service
  lifecycle without notice.
- A successful TCP connection does not prove that Bluetooth pairing, MFi
  authentication, or the subsequent Wi-Fi handoff will succeed.
- The integration has not been validated across the JOYING product line.

