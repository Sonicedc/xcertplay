# xcertplay

English | [中文](README.zh-CN.md)

`xcertplay` is an Android head-unit CarPlay receiver. It connects to an iPhone
over Android USB Host and uses a CH341 USB-I2C bridge chip or the board's I2C
bus to access the MFi authentication coprocessor.

> Still under active development.

## Overview

- App modules for projected Android Auto and Android Automotive OS.
- A unified I2C transport for the CH341 bridge chip and native `/dev/i2c-N`
  devices.
- MFi chip discovery, certificate reads, and challenge-response signing.
- iPhone USB bring-up, USBMUX, Lockdown pairing, carkit TLS, and iAP2 control.
- USB NCM data transport bridged to an Android VPN IPv6 endpoint.
- AirPlay pairing, encrypted control and event channels, HID input, NTP, media decryption, video/audio rendering, and touch forwarding.

## Current progress

The wired connection has been tested; it works.

## Project structure

| Path | Purpose |
| --- | --- |
| `common/` | Shared CarPlay host activity, settings UI, persistence, and app resources used by both targets. |
| `mobile/` | Standard Android target using the shared CarPlay host UI. |
| `automotive/` | Android Automotive OS target with the shared host UI and advanced audio channel mapping. |
| `shared/` | Car App Library code plus the CH341, I2C, MFi, iPhone, iAP2, NCM, VPN, AirPlay, and media implementations. |

## Requirements

- JDK 17 or newer to launch Gradle. The daemon resolves Java 25 through the
  Gradle toolchain.
- Android SDK Platform 37.
- Android 9 (API 28) or newer.
  On Android 9, Wi-Fi P2P 5 GHz mode is unavailable and LocalOnlyHotspot is used instead.
- Android NDK `28.2.13676358`.
- Gradle Wrapper `9.5.0`, Android Gradle Plugin `9.3.0`, and Kotlin `2.2.10`
  are already configured in the repository.
- A physical USB Host/OTG Android device and MFi hardware are required for
  hardware validation.

## Build

On Windows PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :shared:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
```

On macOS or Linux:

```bash
./gradlew :shared:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
```

Unsigned release APKs:

```powershell
.\gradlew.bat :mobile:assembleRelease :automotive:assembleRelease
```

Expected outputs:

```text
mobile/build/outputs/apk/debug/mobile-debug.apk
automotive/build/outputs/apk/debug/automotive-debug.apk
mobile/build/outputs/apk/release/mobile-release-unsigned.apk
automotive/build/outputs/apk/release/automotive-release-unsigned.apk
```

## Acknowledgements

Thanks to [LIVI](https://github.com/f-io/LIVI), providing reference for carplay handshaking process.

## License

Licensed under the [GNU General Public License v3.0](LICENSE).
