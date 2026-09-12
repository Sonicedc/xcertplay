# xcertplay

`xcertplay` 是面向 Android 车机的 CarPlay 接收端 APK 工程基线。

## 项目目标

- APK 运行在 Android 车机上。
- Android 通过 USB Host 连接 CH341 USB-I2C 桥接芯片。
- CH341 的 I2C 侧连接 MFi 认证芯片。
- Android 端最终完成 CarPlay 显示、音频和输入交互。
- MFi 认证层同时兼容板载 I2C 和 CH341 USB-I2C。
- 日常在线调试使用以下 ADB 连接 Genymotion 虚拟机：

  `C:\Program Files\Genymobile\Genymotion\tools\adb.exe`

## 当前状态

| 范围 | 状态 | 说明 |
|---|---|---|
| Android Studio 多模块工程 | 已验证 | `mobile`、`automotive`、`shared` 三个模块可加载，两个 debug APK 已构建 |
| CH341 USB 通信 | 代码已实现（实机未验证） | 已有 USB Host 枚举、授权、bulk 端点发现与阻塞传输；CH341 实际协议语义尚未在实机验证 |
| CH341 I2C 访问 | 代码已实现（实机未验证） | 已有 I2C transaction 抽象、CH341 stream 编码和 MFi 寄存器客户端；尚未实机验证 |
| 板载 I2C 访问 | 代码已实现（设备权限/实机未验证） | `LinuxI2cTransport` 通过 JNI 对 `/dev/i2c-N` 执行 Linux `I2C_RDWR` 纯读、纯写和 write + repeated-start read；不会绕过 Android SELinux 或设备节点权限 |
| CH341/MFi 接口基线 | 已记录 | stream 指令、地址和寄存器流程已写入文档 |
| MFi 认证 | 部分实现（实机未验证） | 已有 certificate 读取和 digest 签名寄存器编排；尚未接入或验证真实 CH341、MFi 芯片与板载 I2C |
| iPhone USBMUX / Lockdown / carkit stream | 代码已实现（实机未验证） | 已有至 `62078` 的 USBMUX TCP、GetValue/Pair、TLS StartSession、`com.apple.carkit.service` StartService 及服务字节流；不包含 iAP2 或媒体会话 |
| Lockdown 配对材料 | 代码已实现（仅内存） | `LockdownPairingClient` 取得 DevicePublicKey 与 WiFiAddress，生成 PairRecord 并处理 Pair；成功的 record/可选 EscrowBag 不持久化 |
| CarPlay 协议栈 | 部分实现（实机未验证） | 已有纯 sans-I/O iAP2 链路、CSM MFi 与 wired 控制消息顺序，以及按 LIVI 最小实现的 NTB16/以太网帧层；尚无 Android 网络接口、AirPlay、视频、音频或触控链路 |
| 真车机硬件联调 | 未实现 | 需要支持 USB Host 的物理 Android 设备 |

mobile 入口提供板载 I2C 诊断，不是 CarPlay 实现：它只对手动指定的 `/dev/i2c-N` 执行 MFi 自检。
项目仍未实现 CarPlay 协议栈，因此当前应用不能运行 CarPlay。

USBMUX 代码可在 iPhone port `62078` 的未验证 TCP 字节流上读写四字节大端长度前缀的 UTF-8 XML
plist，并提供阻塞式明文 Pair、TLS StartSession、carkit StartService 与返回的服务字节流（调用方必须放在 worker thread）。这些 Lockdown 组件本身不解析 iAP2，也不实现 NCM、AirPlay 或任何媒体/输入会话，且未在 iPhone/车机实测。

`Iap2LinkEngine` 是紧接 carkit 服务字节流之后的纯状态机：它处理 marker、链路帧、同步、累计 ACK、EAK、重传与 session 10 的原始控制字节。`Iap2LinkChannel.open()` 在 caller-owned carkit stream 上启动唯一 worker，提供有界的 ready/send/receive/close facade；它不解析 CSM 控制消息、MFi 认证、NCM、AirPlay 或媒体，且尚未实机验证。

`Iap2CsmFramer` 是紧接 session 10 原始控制字节的纯 CSM 编解码器：它只处理 `0x4040`、大端长度、message id、参数长度和按 iAP2 payload 上限拆帧。`Iap2CsmChannel` 则拥有 link，在协商完成后以 peer `maxLength - 10` 拆帧、重组跨包帧并保持有界队列；两者都不解析 Identification 或其他业务消息。

详细清单见 [docs/status.md](docs/status.md)。

## 工程结构

| 模块 | 用途 |
|---|---|
| `mobile` | Android Auto projected app，使用 Jetpack Compose |
| `automotive` | Android Automotive OS app |
| `shared` | Car App Library 的 `Service`、`Session`、`Screen` |
| `gradle` | Gradle Wrapper、版本目录和 JVM 配置 |

当前关键文件：

```text
shared/src/main/java/com/shilapi/xcertplay/shared/MyCarAppService.kt
shared/src/main/java/com/shilapi/xcertplay/shared/MyCarAppSession.kt
shared/src/main/java/com/shilapi/xcertplay/shared/MyCarAppScreen.kt
mobile/src/main/java/com/shilapi/xcertplay/MainActivity.kt
mobile/src/main/AndroidManifest.xml
automotive/src/main/AndroidManifest.xml
```

## 构建环境

工程当前生成配置包含：

- Gradle Wrapper：9.5.0
- Android Gradle Plugin：9.3.0
- Kotlin：2.2.10
- `compileSdk` / `targetSdk`：37
- `minSdk`：29
- Gradle JVM 工具链：Java 25
- SDK 路径：`C:\Users\shila\AppData\Local\Android\Sdk`

本机默认 `java.exe` 指向 Oracle `javapath`。该 shim 在 Gradle Wrapper
传入空 `-classpath` 时会报错，因此建议构建前明确使用 Android Studio JBR：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

## 构建命令

在项目根目录运行：

```powershell
.\gradlew.bat :mobile:assembleDebug
.\gradlew.bat :automotive:assembleDebug
```

预期 APK 路径：

```text
mobile/build/outputs/apk/debug/mobile-debug.apk
automotive/build/outputs/apk/debug/automotive-debug.apk
```

2026-09-11 已使用 Android Studio JBR 25 离线强制重跑单元测试、两个模块的 debug lint，
以及两个模块的 debug/release APK 构建，均成功。APK 位于上述路径；这只验证 Kotlin 逻辑和工程
可构建，不代表 CH341、MFi 或 CarPlay 已在实机验证。

## Genymotion 在线调试

```powershell
$adb = "C:\Program Files\Genymobile\Genymotion\tools\adb.exe"

& $adb devices
& $adb install -r ".\mobile\build\outputs\apk\debug\mobile-debug.apk"
& $adb shell am start -n com.shilapi.xcertplay/.MainActivity
```

Genymotion 适合调试 APK 生命周期、UI、Car App Library 模板和非 USB 代码。

它不能替代物理硬件测试：CH341 是真实 USB 设备，必须最终在支持 USB Host/OTG 的 Android 车机或开发板上验证设备枚举、I2C 读写和 MFi 认证。虚拟机 USB 直通能力取决于 Genymotion 版本和宿主机配置，不能作为硬件链路已经通过的证据。

## 文档

- [项目状态](docs/status.md)
- [目标架构和阶段](docs/architecture.md)
- [CH341 I2C 与 MFi 接口](docs/ch341_mfi_interface.md)
- [参考项目](docs/reference_projects.md)

## Board I2C diagnostic

The mobile app provides a manual `/dev/i2c-N` diagnostic page. It opens the selected Linux I2C
node off the main thread, probes documented MFi candidates `0x10` then `0x11` through separate
`0x00` device-version select/read operations, and reports the selected address, device version,
and raw protocol-major. Access still requires a deployed native backend bundled in the APK plus
Android SELinux and device permissions; a displayed result is not hardware validation.

CH341 is intentionally not configurable in the UI: deployment must supply its VID/PID identity.

## Wired iAP2 control status

`Iap2WiredControlClient` now performs the fixed LIVI wired control order on one already-ready CSM
channel: Identification, MFi authentication, `AE03`, then `5000`/`5200`/`AE00`/`4157`/`4154`.
It forwards received control updates and answers every `4300` with a wired `4301` only when the
caller has supplied a real IPv6 literal, AirPlay port, public key, and source version. This is
control-plane code only: it does not create USB NCM or an AirPlay receiver, and it has not been
verified with an iPhone or vehicle hardware.

## USB NCM transport status

`Ntb16Codec` is a LIVI-minimal NTB16 codec: one NTH16 header, one NDP16 table with one datagram,
and the USB 512-byte short-packet pad. `NcmFunctionDiscovery` reads the NCM control interface
(class `0x02`, subclass `0x0D`) and data interface (class `0x0A`, alternate setting 1) descriptors;
`NcmUsbBridge` claims both interfaces and moves Ethernet frames over the bulk endpoints.
`EthernetIpv6Codec` strips or restores an untagged Ethernet II header around IPv6 payloads.
`CarPlayVpnService` and `Ipv6NcmBridge` bind that Ethernet seam to an Android VPN tun on a
link-local IPv6 address, so the AirPlay control/media sockets have a routable endpoint.

The NCM/VPN path has not been verified against an iPhone or a vehicle head unit.

## AirPlay pairing core status

`AirPlayCrypto` adds BouncyCastle-backed X25519, Ed25519, HKDF-SHA512, SHA-512, and
ChaCha20-Poly1305 primitives plus the AirPlay nonce helpers. `Tlv8Codec` handles the pairing TLV8
wire format, `Srp6a` runs the fixed "Pair-Setup"/"3939" SRP-6a server, and `PairSetup`,
`PairVerify`, `ControlCipher`, and `RtspMessage` implement the accessory-side pairing, encrypted
control framing, and request parsing/response building. `MfiSapAuthSetup` builds the /auth-setup
response from the existing MFi coprocessor client. `AirPlayIdentity` and `PairingStore` are
in-memory models awaiting the session layer for persistence.

These are pure protocol state machines with no sockets. The session layer below drives them over
TCP :7000; they have not been verified against an iPhone or vehicle head unit.

## AirPlay transport/session status

`BplistCodec`, `AirPlayConfig`, `AirPlayInfoPlist`, and `AirPlayHid` implement the binary plist
wire format, the `/info` capability declaration, and the touch/knob/media/telephony HID
descriptors and reports. `AirPlaySession` owns one TCP control connection, routes pairing,
`/auth-setup`, `/info`, SETUP, RECORD, TEARDOWN, `/command`, and `/feedback`, and opens the
encrypted event channel used to push HID input to the phone. Screen and audio stream setup is
routed through `AirPlayMediaHandler`; media decode and NTP timing are still deferred.

This has not been verified against an iPhone or vehicle head unit.
