# QZD / Zjinnova 6125 Head-Unit Integration

This document describes the experimental changes on the
`vehicle/qzd-low-latency` branch. They were developed for a QZD G6 head unit
based on the Qualcomm `trinket` platform, running Android 10 at 1280 x 800 and
60 Hz. The implementation is intentionally isolated behind settings and
fallbacks so the upstream transports remain usable.

No MFi certificate, private key, device-specific authentication material, or
rooting mechanism is included in this repository. Use the code only with
hardware and devices you own or are authorized to test.

## Head-unit architecture discovered

- The board exposes its MFi authentication coprocessor through `/dev/i2c-0`.
  The direct Linux I2C transport is the authentication path used by this fork.
- `/dev/zjinnova_iap2` also exists on the tested firmware, but this integration
  does not rely on that device node.
- Zjinnova's `blink` service exposes a Bluetooth control protocol on
  `127.0.0.1:3152`. `ZjinnovaZbtDuplexStream` wraps RFCOMM data in the observed
  ZBT message `0x105` framing and provides a standalone wireless bootstrap.
- The vendor Bluetooth service is still a firmware dependency. The ZLINK UI
  application does not need to be launched, but killing vendor services may
  also remove the `blink` endpoint.
- Bluetooth is used to bootstrap wireless CarPlay/iAP2. Once AirPlay is
  established, audio, video, and touch traffic use 5 GHz Wi-Fi P2P and the
  Bluetooth bootstrap stream is closed.

Access to `/dev/i2c-0` depends on the firmware's Unix permissions and SELinux
policy. The tested unit had root access available during development. This app
does not obtain root or alter the device security policy.

## Integration changes

### Authentication and Bluetooth

- Native Linux I2C probing and diagnostics were expanded for the onboard MFi
  device.
- The Zjinnova loopback Bluetooth transport was added as a head-unit-specific
  option.
- Native Bluetooth fallback tries the public CarPlay RFCOMM UUID and then
  direct RFCOMM channel 1, with a vendor registration trigger where available.
- Self-check output makes transport and MFi failures visible without exposing
  certificate contents or secrets.

### Video and touch latency

- The receiver advertises up to 60 FPS and enables Qualcomm decoder low-latency
  controls when supported: `low-latency=1` and
  `vendor.qti-ext-dec-low-latency.enable=1`.
- Video decoding and network threads receive elevated priorities, screen TCP
  uses `TCP_NODELAY`, and its receive buffer is enlarged.
- Stable local presentation timestamps can replace irregular source timing.
- The frame-preservation option favors continuity over aggressive dropping.
- Touch moves are coalesced only when they have the same contact state;
  down/up transitions are always retained.
- A high-performance Wi-Fi lock, multicast lock, and partial CPU wake lock are
  held while the session is active.

### Audio stability

- The audio receive thread runs at urgent-audio priority and uses a larger UDP
  receive buffer.
- Feedback is derived from Android's actual `AudioTrack` playback head instead
  of blindly applying the phone-reported latency value.
- RTP discontinuities are counted and exposed in the diagnostic overlay.

## Runtime controls

Open settings with a three-finger swipe down. A three-finger tap and a long
press on the performance overlay are provided as fallbacks because some head
units filter multi-touch movement. The QZD profile defaults to:

| Setting | Default |
| --- | --- |
| Performance statistics | On |
| Video low latency | On |
| Stable video timestamps | On |
| Preserve video frames | On |
| Audio low latency | On |
| Packet/protocol trace | Off |

Use `Save & Reconnect` after changing session-level settings. Protocol tracing
is intentionally off by default because its logging overhead can affect the
system being measured.

## Diagnostics and interpretation

The overlay reports render FPS, queue depth, queue wait, decode/render timing,
and audio RTP discontinuities. Logs are written to:

```text
/sdcard/Android/data/com.shilapi.xcertplay/files/logs/xcertplay.log
```

The tested panel refreshes at 60 Hz, but the iPhone can send video at a much
lower and variable cadence when the screen is mostly static. Therefore overlay
FPS is incoming/rendered CarPlay frame rate, not the physical panel refresh
rate. A queue near 0-1 with low queue wait usually points to source cadence,
decoder time, Wi-Fi loss, or presentation timing rather than application queue
backlog.

The unit uses one Qualcomm radio for infrastructure Wi-Fi and Wi-Fi P2P.
Disconnecting it from an ordinary access point is a useful interference test,
but Wi-Fi itself must remain enabled for wireless CarPlay. This test also drops
ADB when ADB is using that same network.

Static inspection of ZLINK6 found `refreshRate=60` and `maxFPS=30`. Its “FPS
Engine” therefore appears to do more than merely advertise 60 FPS; it may use
native pacing or interpolation that has not been reproduced here.

## Build and validation

On Windows PowerShell with Android Studio's JBR selected:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:assembleDebug
```

Install `mobile/build/outputs/apk/debug/mobile-debug.apk`. Pair the iPhone,
select the native I2C MFi and Zjinnova Bluetooth options, save and reconnect,
then collect both the in-app log and a short description of the route, phone
model/iOS version, and whether infrastructure Wi-Fi was connected.

The source and unit tests have been built successfully on the development
machine. The current media-scheduling changes remain experimental and still
need extended driving, reconnect, phone-call, Siri, and navigation-audio tests.

## Research priorities

1. Capture correlated receiver timing, Android codec timing, and packet loss
   during a visible stall.
2. Separate source-frame cadence from decoder and Surface presentation delay.
3. Test infrastructure-Wi-Fi coexistence versus P2P-only operation.
4. Measure audio clock drift and discontinuities over a long session.
5. Compare ZLINK's native frame pacing with Android `MediaCodec` output timing
   without copying proprietary authentication material.
