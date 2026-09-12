# 项目状态

本文档记录工程的实际代码状态，不把计划中的 CarPlay 功能标记为已实现。

## 检查日期

- 文档建立日期：2026-09-11
- 检查方式：直接读取本地源码、Gradle 配置和 Android Manifest
- 构建尝试日期：2026-09-11

## 构建验证

- 本机默认 `java.exe` 来自 Oracle `javapath`，版本为 Java 21。
- 默认 Oracle `javapath` shim 会因 Gradle Wrapper 的空 `-classpath` 启动失败。
- 使用 Android Studio JBR 25 后，Gradle Wrapper 9.5.0 可正常启动：

  `C:\Program Files\Android\Android Studio\jbr`

- 已使用 Android Studio JBR 25 离线强制重跑：

  `.\gradlew.bat :shared:testDebugUnitTest :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug :mobile:assembleRelease :automotive:assembleRelease --offline --rerun-tasks`

- USBMUX 最小 TCP 阶段完成后，已再次使用 Android Studio JBR 25 离线强制重跑并通过：

  `.\gradlew.bat :shared:testDebugUnitTest :mobile:assembleDebug :automotive:assembleDebug --offline --rerun-tasks`

  `shared` 的 6 个测试全部通过（0 failures、0 errors）。

- `shared` 当前保留 6 个关键单元测试，两个模块的 debug APK 构建均已通过。
- 此结果仅验证 Kotlin 逻辑与 APK 可构建；不代表 CH341、MFi 或 CarPlay 硬件链路已验证。

本机推荐构建方式：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :mobile:assembleDebug
.\gradlew.bat :automotive:assembleDebug
```

## 已生成内容

### 文档基线

- CH341 I2C stream 指令：`AA`、频率命令、`START`、写/读、`STOP`、`00`。
- MFi 7-bit 地址候选：`0x10`、`0x11`。
- MFi 寄存器流程：certificate 长度、certificate、challenge、signature 启动、
  signature。
- 板载 I2C 与 CH341 双 transport 兼容要求。

详细接口见
[ch341_mfi_interface.md](ch341_mfi_interface.md)。

### Gradle 工程

- 根工程名：`xcertplay`
- 子模块：`mobile`、`automotive`、`shared`
- Gradle Wrapper：9.5.0
- Android Gradle Plugin：9.3.0
- Kotlin：2.2.10
- `compileSdk` / `targetSdk`：37
- `minSdk`：29
- Java/Kotlin 编译兼容版本：Java 11
- Compose BOM：2026.02.01

### `mobile` 模块

- 应用 ID：`com.shilapi.xcertplay`
- 使用 Jetpack Compose
- 依赖 AndroidX Car App `app-projected`
- `MainActivity` 提供可编辑 `/dev/i2c-N` 的板载 I2C MFi 自检诊断入口；它不是 CarPlay 实现。
- Manifest 声明 Android Auto 模板 host

### `automotive` 模块

- 应用 ID：`com.shilapi.xcertplay`
- 依赖 AndroidX Car App `app-automotive`
- 使用 `CarAppActivity` 作为入口
- Manifest 要求 `android.software.car.templates_host`
- Manifest 要求 `android.hardware.type.automotive`

### `shared` 模块

- `MyCarAppService` 继承 `CarAppService`
- `MyCarAppSession` 继承 `Session`
- `MyCarAppScreen` 继承 `Screen`
- 当前模板为 `MessageTemplate`，准确声明硬件 transport 尚未配置，且不在驾驶场景提供输入。
- Host validator 当前为 `ALLOW_ALL_HOSTS_VALIDATOR`

## 尚未实现

| 能力 | 本地证据 |
|---|---|
| USB Host 权限申请和运行时授权 | 已实现：CH341 与 iPhone 的可配置精确 VID/PID matcher、`UsbManager` 枚举、权限请求与结果解析；尚未在实机验证 |
| CH341 设备枚举 | 已实现：仅匹配部署配置的 VID/PID，未内置或宣称唯一 CH341 PID；尚未在实机验证 |
| CH341 vendor protocol | Kotlin stream 编码和 Android bulk 传输已实现；CH341 状态、NAK 与恢复语义尚未在实机验证 |
| CH341 I2C stream 编解码 | 已实现：每方向最多 65,535 字节的 32-byte stream 分段编码；尚未在实机验证 |
| I2C 抽象和总线扫描 | `I2cTransport` transaction 接口与基于 `0x00` device-version 的 `0x10/0x11` first-success MFi 扫描逻辑已实现；CH341/板载后端和任何实际扫描结果均未在实机验证 |
| 板载 I2C transport | 已实现 `LinuxI2cTransport`：JNI 使用 Linux `I2C_RDWR` 对 `/dev/i2c-N` 执行纯读、纯写和 write + repeated-start read；Android SELinux 与 Unix 节点权限不会被绕过，未实机验证 |
| MFi 芯片型号和 I2C 地址枚举 | 有 `0x10/0x11` device-version first-success 扫描；型号识别未实现，任何扫描结果未在实机验证 |
| MFi 证书读取 | 纯 Kotlin 寄存器逻辑及两种 I2C 后端均已实现；未实机验证，设备节点权限和硬件协议验证仍阻断端到端读取 |
| MFi challenge-response | 纯 Kotlin digest 寄存器编排及两种 I2C 后端均已实现；未实机验证，设备节点权限和硬件协议验证仍阻断端到端读取 |
| iPhone CarPlay USB bring-up | 已实现：固定 LIVI commit 证实的 request `0x52`、重新枚举边界、再次授权、configuration 6 选择及严格 USBMUX pipe（interface 1、bulk OUT `0x04`、bulk IN `0x85`）；未实机验证 |
| USBMUX 最小 TCP / Lockdown plist / Pair | 已实现：v2 version/setup、16 字节流式 framing、payload ACK、FIN/RST、至 `62078` 的阻塞字节流、安全的 u32 大端长度前缀 XML plist 收发，以及明文 GetValue/Pair；未实机验证 |
| Lockdown 配对材料生成 | 已实现（仅内存）：`LockdownPairingClient` 取得 PKCS#1 DevicePublicKey 与 WiFiAddress，使用 `LockdownPairRecordGenerator` 生成并发送 PairRecord，返回本地 record 与可选 EscrowBag；无持久化、StartSession、TLS、store 或 UI |
| Lockdown TLS 字节流 | 已实现（实机未验证）：`TlsDuplexChannel` 仅启用 TLSv1.2/1.3，完成阻塞客户端握手并提供连续 send/recv；API 29 未验证 |
| Lockdown carkit 服务打开 | 已实现（实机未验证）：StartSession 强制 TLS、StartService `com.apple.carkit.service`、新端口连接及可选服务 TLS；返回的仅为 owned 字节流 |
| CarPlay/iAP2 链路 | 代码已实现（实机未验证）：`Iap2LinkEngine` 与 `Iap2LinkChannel` 完成 marker/帧/SYN/ACK/EAK/重传及 caller-owned stream worker；`Iap2CsmFramer` 完成 session 10 CSM framing；`Iap2WiredControlClient` 完成 Identification、MFi、供电、五类订阅和 `4300`→`4301` 的控制顺序；不含 NCM、AirPlay 或媒体 |
| CarPlay 视频、音频、触摸、按键 | 无相关代码 |
| 真车机 VHAL/多屏适配 | 无相关代码 |
| NDK/externalNativeBuild | 已配置 ndk-build；使用 NDK `28.2.13676358` 构建 `xcertplay_i2c` JNI 库 |
| libusb/其他 USB 原生库 | 未引入 |

## 阶段 6：iPhone 有线 CarPlay USB bring-up（代码完成，实机未验证）

- 实现依据仅为 LIVI 固定提交
  [`0a3dcaa0bf30d5319506d0e47c7b0d46bc942ec3`](https://github.com/f-io/LIVI/tree/0a3dcaa0bf30d5319506d0e47c7b0d46bc942ec3)
  中的有线 USB bring-up 事实；该提交许可证为 GPL-3.0。本项目没有复制或移植 LIVI 源码。
- `IphoneUsbHost` 只接受部署者提供的精确 Apple VID/PID 列表。LIVI 证实 VID 为 `0x05AC`，但未提供
  可安全硬编码为本项目默认值的 PID 列表。
- 在后台打开已获授权设备，发送 Apple device-recipient IN vendor request：`0x52`、`value=0`、
  `index=4`、`length=1`。它立即关闭连接并返回“等待重枚举”状态。
- Android 应用无法使用 LIVI 的 Linux sysfs `bConfigurationValue` 路径，也没有同步等待重新枚举的 API。
  因此必须由 `ACTION_USB_DEVICE_ATTACHED` 接收新设备、重新申请权限，然后在后台选择 configuration `6`；
  每次 open 的连接都会关闭。
- 重枚举后的连接会再次选择 configuration `6`，严格查找 USBMUX interface `1` 的 bulk OUT `0x04` 和
  bulk IN `0x85`，声明接口后返回可关闭的 `Iap2UsbSession`。写必须完整；读通过 `UsbRequest` 以 65536
  字节 chunk 排队，`requestWait(timeout)` 超时只返回“无数据”，不会伪报设备错误。
- 此阶段没有新增测试，`shared` 仍保持 6 个关键纯逻辑测试。它不包含 iAP2 帧、Lockdown、carkit TLS、
  MFi 会话绑定、NCM、AirPlay、视频、音频或输入实现，也未在 iPhone/车机上验证。

## 阶段 7：USBMUX v2 最小 TCP（代码完成，实机未验证）

- `Iap2UsbMuxHost.open(Iap2UsbSession)` 只在既有、已声明的 USBMUX bulk pipe 上工作：先发送 version
  protocol `2`，尝试读取一次响应，再发送 setup；该次读超时/无数据不会阻止 setup，真实 USB I/O 错误仍会
  终止打开。它将任意 Android bulk read 重组为带 16 字节大端 mux header 的连续帧，并拒绝无效 magic、
  长度或 TCP 头。
- `connect()` 只支持主动连接；默认目标为 lockdown port `62078`。它完成 SYN / SYN-ACK / ACK，返回具有
  阻塞 `send` / `recv` 的 `Iap2UsbMuxTcpConnection`。发送按最多 16 KiB 分块；收到 payload 会 ACK，FIN
  和 RST 会使字节流确定性结束。连接等待默认 5 秒，超时会发送 RST 并移除本地连接；peer RST 会立即以
  明确 transport 错误结束建连或接收，不会被误报为等待超时。
- `Iap2UsbMuxTcpConnection.close()` 在连接仍可用时发送 FIN。host close 会停止 reader、唤醒阻塞接收并
  直接关闭底层 `UsbDeviceConnection` 以解除 pending read；它不等待 read/write 锁。host 关闭或 pipe 失败
  同样会终止全部本地连接。所有 API 均必须从 Android 主线程外调用。
- 该阶段的 USBMUX TCP 基础本身不实现或验证 Lockdown Pair（该项见阶段 7B）、StartSession、TLS、StartService、carkit、iAP2、MFi 会话绑定、NCM、
  AirPlay、视频、音频或输入。没有 iPhone/车机实测证据，因而不能据此声称 Lockdown 或 CarPlay 可用。
- 协议事实审计来源仅为 LIVI 固定 commit
  [`0a3dcaa0bf30d5319506d0e47c7b0d46bc942ec3`](https://github.com/f-io/LIVI/tree/0a3dcaa0bf30d5319506d0e47c7b0d46bc942ec3)
  的 `native/livi-helperd/crates/iap2-usbmux/src/mux.rs`。该仓库为 GPL-3.0；Kotlin 代码为独立 clean-room
  实现，未复制、翻译或移植 LIVI 源代码、注释或结构。

## 阶段 7A：Lockdown plist framing（代码完成，实机未验证）

- `LockdownPlistChannel` 建立在已有的 `Iap2UsbMuxTcpConnection` 连续字节流上：每条消息是四字节
  无符号大端长度，随后是 UTF-8 XML plist。读取会跨任意 `recv` 分片和粘包精确读取一帧；超时、流内 EOF
  与无效/过大长度分别作为明确错误报告。默认防御上限为 1 MiB，且绝不接受超过 4 MiB 的调用方上限。
- 最小值模型仅支持 Lockdown 此阶段所需的根/嵌套 `dict`、`string`、`integer`、`true`/`false` 与 base64
  `data`。Android PullParser 跳过标准 plist `DOCTYPE`，不处理 DTD 或实体；不支持的类型、重复 key、
  无效整数或 data 会被拒绝。写出端使用 UTF-8、标准 plist `DOCTYPE`、XML 转义和 base64 编码。
- 通道提供同步 request-response 和 `Closeable`，关闭时关闭其拥有的 TCP 字节流。它不实现 Lockdown Pair、
  StartSession、TLS、StartService 或 carkit，也不建立 iAP2 或 CarPlay 会话；没有 iPhone/车机实测证据。
- 协议实现参考仍仅为 LIVI 固定 commit 的 `Cargo.lock` 所锁定的 `idevice` `0.1.65` 与该提交本身；本项目
  的 Kotlin 代码为独立 clean-room 实现，未复制、翻译或移植其源代码、注释或结构。

## 阶段 7B：Lockdown 配对材料（代码完成，仅内存）

- `LockdownPairRecordGenerator.generate()` 不连接 transport；`LockdownPairingClient` 先在新建的 `62078` 连接上以 GetValue 取得 iPhone 的 PKCS#1 PEM
  `DevicePublicKey`、`WiFiAddress`、`HostID` 与 `SystemBUID`。它通过平台 JCA 生成 RSA-2048 host key，
  用最小 DER 与 `SHA256withRSA` 生成空 DN 的 host/root 自签证书及 `CN=Device` 的 device 证书。
- 两张证书均带有 public-key bits 的 SHA-1 SKI、critical `BasicConstraints CA=true` 和 critical
  `KeyUsage keyCertSign,cRLSign`；`HostPrivateKey` 与 `RootPrivateKey`、`HostCertificate` 与
  `RootCertificate` 各自是同一份 PKCS#8/PEM 数据。device 证书依照锁定实现使用 `CN=Device` 作为 issuer 与
  subject，但仍由 host key 签名。证书时间在 2049 年及以前编码为 UTCTime，之后编码为 GeneralizedTime，
  有效期长度保持锁定实现原值。生成器直接对完整 TBSCertificate 使用 host public key
  进行 `SHA256withRSA` 验签，并在构造路径检查有效期、公钥、issuer/subject 与扩展；模型与 plist 输出对
  byte array 防御复制，不记录密钥或证书内容。JBR 25 的 `CertificateFactory` 拒绝空 issuer DN，故它不作为
  生成成功条件；Android TLS 的证书装载兼容性仍待阶段 9C 实机/兼容层验证。
- `toPairRequestDictionary()` 只提供含临时 `DevicePublicKey`、WiFi MAC、`RootPrivateKey` 与证书字段的嵌套
  Pair 请求字典；`HostPrivateKey` 只留在本地对象，供未来 TLS/成功配对后的持久化使用，不会上 wire。
- `LockdownPairingClient` 用固定字段发送明文 Pair，PairingDialogResponsePending 时每秒重发同一请求，支持最多五分钟的总超时和简单取消回调；取消会在阻塞 wire 操作之间及 pending 重试等待期间检查，单次 connect/plist read 最多阻塞五秒。UserDeniedPairing、PasswordProtected 与其他远端错误均为不同失败类型。成功时的本地 record 和可选 EscrowBag 均防御复制且不持久化。每次结果都会关闭它创建的 channel/TCP 连接。
- 此阶段不实现 StartSession、TLS、持久化或 UI。
- `LockdownTlsEngineFactory` 可从内存 PairRecord 构造尚未开始握手的客户端 `SSLEngine`；它不执行网络握手、StartSession 或任何 duplex/TLS framing。
- `TlsDuplexChannel.open()` 以调用方预算限制 TLSv1.2/1.3 握手的接收与引擎推进，发送另受 USBMUX 自身的有界写超时约束；它保留跨任意底层分片/粘包的密文与明文，并提供阻塞双工流。握手失败关闭底层，正常关闭尽力发送 close_notify。它不实现 StartSession、StartService、持久化或 UI，API 29 尚未验证。
- `LockdownCarKitClient` 新建 `62078` 连接发送明文 StartSession，要求 `EnableSessionSSL=true`，在同一流上启用 TLS 后仅以 Request/Service 字段发送 `com.apple.carkit.service` StartService；Lockdown TLS 保持到新服务连接及可选服务 TLS 握手成功后再关闭。`EnableServiceSSL` 缺失或非布尔均按 false；connect/receive/engine 使用五秒预算，写入沿用底层有界超时。仅覆盖现代 TLSv1.2/1.3，不额外请求 ProductVersion 或兼容 legacy；失败关闭当前流，host 与成功返回流分别仍由调用方持有。其返回流可交给 `Iap2LinkEngine`，再由独立 `Iap2CsmFramer` 处理基础 CSM framing；两者均不包含 MFi 或媒体协议。

## 阶段 8：iAP2 链路状态机（代码完成，实机未验证）

- `Iap2LinkEngine` 仅接受调用方提供的单调毫秒时钟与任意分片字节；它输出待写字节和 session 10 控制事件，不拥有或阻塞 `BlockingDuplexByteStream`。
- `Iap2LinkChannel.open()` 以固定 wired 参数在 caller-owned `BlockingDuplexByteStream` 上启动唯一 worker；外部只有有界的 ready/send/receive 队列。close 直接关闭 owned stream 打断 recv，并在有界 join 后重新抛出底层或 worker 失败。
- 已实现 marker、9 字节 header 与双 checksum、wired initiator SYN/LSP/ACK、u8 序号和累计 ACK、peer LSP、EAK、队列、重传、RST/EOF 结束。单个入站 wire frame 的总长度硬限制为 `65535`；待发和乱序队列默认各限 64 项、pending output 限 1 MiB、pending event 限 256 项，超限会清理待处理数据并保留单一 `Dead` 事件。peer LSP 的 `maxLength` 按总 frame 长度执行，control payload 必须不超过 `maxLength - 10`。
- 除后续阶段 8A 的最小 Identification 外，不含 CSM worker pump、其他 CSM 业务消息、EA/file-transfer 业务、MFi 会话绑定、NCM、AirPlay、音视频、输入、持久化或 UI。没有 iPhone/车机实测证据。
- 已用 Android Studio JBR 25 离线强制重跑 `:shared:testDebugUnitTest`、`:mobile:assembleDebug`、`:automotive:assembleDebug`、`:mobile:lintDebug` 和 `:automotive:lintDebug`；均通过，`shared` 仍为 6 个测试。该结果不构成 iPhone、MFi 或车机实机验证。

## 阶段 8A：最小有线 Identification（代码完成，实机未验证）

- `Iap2IdentificationClient` 在既有 `Iap2CsmChannel` 已 ready 后仅处理 `1D00`、`1D02` 与 `1D03`：收到 StartIdentification 发送一次 `1D01`，Accepted 成功，Rejected 严格解析被标记参数并失败；首版没有 20--22 等可降级组件，因此绝不伪造重发。
- `1D01` 编码身份、诚实的消息广告、最小 EA 协议、语言和一个 USB Host transport group。它广告实际发送的 `AE03`、`5000`、`5200`、`AE00`、`4157`、`4154`、`4301`，以及实际接收/转交的 `5001`、`5201`、`5202`、`AE01`、`4158`、`4155`、`4300`；不广告 stop 或 AA 消息。部署必须显式提供 CarPlay USB interface number（0--255），所有 NUL 结尾配置字符串均拒绝内嵌 U+0000。
- 总预算默认十秒、最大五分钟；该阶段未新增永久测试，临时编码向量已运行后删除，`shared` 保持 6 个关键测试。没有 iPhone/车机实测证据。

## 阶段 4A：MFi 证书与 digest 签名寄存器编排

### 代码完成（纯逻辑）

- `MfiAuthenticationClient` 仅依赖 `I2cTransport`，所有 API 均为阻塞式，必须在 Android 主线程之外调用。
- 寄存器读取严格拆为 write-only register select（STOP）再 pure read；每次普通 I2C 操作只重试
  `I2cTransportException`，间隔 500 µs、总计最多 2 秒。`0x30` 为大端长度，随后一次从 `0x31`
  读取完整 certificate（默认防御上限 65525）；协议 major `0x02` 仅作为原始诊断值。
- challenge 为 1--128 字节，统一写 `0x20`、`0x21`、`0x10=1`，初等 10 ms 后每 10 ms 轮询，最多 3 秒；
  成功后从 `0x11/0x12` 读取动态长度响应，绝不写 `0x11`。超时报告 best-effort `0x05` 错误码。
- `Iap2MfiAuthenticationClient` 已按固定 LIVI CSM 流程处理 `AA00/AA01`、`AA02/AA03`、`AA04` 与 `AA05`，
  默认总期限 30 秒、最大 5 分钟，不拥有或关闭 CSM channel。
- 当前保留的 fake-transport 单测锁定分离 select/read 与 challenge 寄存器序列；未新增永久测试。

## 阶段 8C：wired iAP2 control loop（代码完成，实机未验证）

- `Iap2WiredControlClient` 不拥有/关闭已经 ready 的 `Iap2CsmChannel`，在一个 1--5 分钟的总 deadline 内按 LIVI
  顺序运行 Identification、MFi、`AE03`，并发送精确的 `5000`、`5200`、`AE00`、`4157`、`4154` 订阅。
- `4300` 会无条件触发 wired `4301`；没有 endpoint 的 run API。endpoint 必须显式给出至少一个 IPv6 文本地址、
  1--65535 AirPlay 端口、非空 public key 和 source version，device identifier 可省略。其他消息由调用方回调接收，
  在该期间该客户端是唯一的 CSM receiver。
- 返回结果只描述控制循环的终止原因和到达阶段，绝不表示 USB NCM、AirPlay、视频、音频或输入已经可用。一次性
  LIVI 精确帧向量验证已通过并删除；永久 `@Test` 数仍为 6。iPhone/车机实机验证仍缺失。

### 构建与单元测试

2026-09-11 已使用 Android Studio JBR 25 强制离线重跑并通过：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :shared:testDebugUnitTest :mobile:assembleDebug :automotive:assembleDebug --offline --rerun-tasks
```

后续已将测试集精简为当前的 6 个关键用例。该结果只验证 Kotlin 逻辑和 APK 可构建，不代表任何
USB、I2C 或 MFi 实机链路已验证。

### 明确边界

这仅证明 Kotlin 寄存器逻辑可运行。`Ch341I2cTransport` 的每方向软件上限为 65,535 字节，足以表达完整
certificate、signature 和寄存器加 128-byte challenge；它按速度与数据量推导单个 transaction 的最低超时，
但分段 continuation、设备状态、NAK 语义和大 transfer 都没有实机验证。板载 I2C backend 虽已随 APK 打包，
仍需要真实设备节点权限与硬件验证。因此不能声称 MFi 认证已端到端验证。实现语义仅参考 fixed `f-io/LIVI`，
未复制其代码。

## 测试状态

Android Studio 生成且与项目无关的示例测试已删除。`shared` 当前严格保留 6 个关键单元测试：CH341
matcher、两条 CH341 stream、Linux I2C combined transaction、MFi self-check 与 MFi challenge 序列；USB
实机、I2C、MFi 和 CarPlay 仍需在相应功能落地后按层补充验证。

## 下一步

1. 在真车机上确认 CH341 的 VID/PID、接口、bulk 端点和 Android USB Host 授权流程。
2. 在真实 CH341 上验证 I2C stream、bulk 返回状态和 `0x10/0x11` 地址扫描结果。
3. 建立板载 I2C transport，并在真实 MFi 芯片上验证现有寄存器客户端。
4. 确认实际芯片地址、型号、协议版本、certificate、challenge 和 signature。
5. 将已验证的认证后端接入后续会话流程。
6. 再进入 CarPlay 传输、媒体和输入链路开发。
7. 在真车机上验证；Genymotion 仅用于软件调试。

## 阶段 1：USB Host / CH341 transport 发现

### 代码完成

- `shared` 已声明非强制 `android.hardware.usb.host` feature；该 library manifest 会合并到 `mobile` 和 `automotive` APK。
- `I2cTransport` 已定义为 MFi 层可依赖的统一边界，并定义参数、设备不可用、权限、超时、NAK 和协议等预期错误类型。MFi 层未依赖 CH341。
- `Ch341UsbHost` 已实现受配置 matcher 约束的 `UsbManager.deviceList` 枚举、权限请求、广播结果解析、bulk IN/OUT 接口发现，以及后台 open/claim 和可关闭会话资源释放。
- 后续阶段已补充 raw bulk 传输和 CH341 I2C stream 编解码；其硬件语义仍未验证。
- 当前保留 matcher 的精确匹配单元测试。

### 构建与单元测试

2026-09-11 已使用 Android Studio JBR 25 离线执行并通过：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :shared:testDebugUnitTest :mobile:assembleDebug :automotive:assembleDebug --offline
```

### 实机未验证的假设

- 目标车机支持 USB Host/OTG，并允许应用请求和接收 USB 权限。
- 实际 CH341 变体的 VID/PID、可用 USB interface 和 bulk IN/OUT endpoint 尚未确认，必须部署时传入 matcher 配置并在实机枚举。
- `openDevice`、`claimInterface` 和关闭资源的行为仅已通过编译验证；未接入真实 CH341。
- CH341 I2C stream 编码和 Android bulk 传输代码已实现但尚未接入真实硬件；vendor 状态、NAK 语义、
  MFi 芯片地址和任何 MFi 数据读写仍未验证。

## 阶段 2A：CH341 I2C stream（代码完成，硬件未验证）

### 代码完成

- `Ch341I2cStreamEncoder` 已实现 20/100/400/750 kHz 配置、7-bit 地址、纯写、纯读和
  write + repeated-start read；读的最后一个字节使用零长度 `C0` 命令表示 NACK。
- 当前实现会将单次读/写各最多 65,535 字节的 transaction 分为 32 字节 CH341 stream 段；中间段不发
  STOP，最后一段才以最终读 NACK/STOP 结束。
- `Ch341UsbSession` 已提供内部阻塞 bulk 写/读，检查关闭状态、正超时值和精确传输字节数，并映射为
  统一的 `I2cTransportException`。
- `Ch341I2cTransport` 已串行化每次 configure + stream + read 序列；它不提供 address-only
  probe。阶段 3 的通用 MFi 扫描器通过真实 `0x00` device-version select STOP + pure-read 调用
  该 transaction 边界，但实际 CH341 扫描结果仍未验证。
- 当前保留 CH341 编码单测，覆盖小型 transaction 的 repeated-start/最终读 NACK，以及 65,535-byte
  pure-read 的分段、最终 NACK/STOP 和 129-byte write 可编码性。

### 硬件未验证的假设

- 尚未在真实 CH341、Android USB Host 和 I2C/MFi 芯片上运行；bulk 端点、stream 状态字节、实际
  超时和恢复策略仍未验证。
- Android `UsbDeviceConnection.bulkTransfer` 的返回值不能用于区分 CH341/I2C NAK、USB 错误或
  超时。阶段 2A 将其统一映射为设备不可用，且不实现 address-only `probe`；阶段 3 已通过真实
  `0x00` device-version select/read 实现 `0x10/0x11` 扫描逻辑，但 CH341 状态语义和实机结果仍未验证。
- MFi 芯片最终地址（`0x10` 或 `0x11`）及其复位脚关联仍需由阶段 3 扫描器在实机中确认。

## 阶段 3：MFi device-version 发现（代码完成，硬件未验证）

### 代码完成

- 新增独立 `com.shilapi.xcertplay.mfi` 包；MFi 发现层只引用 `I2cTransport`，不引用
  `Ch341` 类或 USB 细节。
- `MfiDeviceScanner` 在一个全局 2 秒窗口内按 `0x10`、`0x11` 的 LIVI 顺序循环；每个候选先纯写
  `[0x00]`（STOP），再纯读 1 byte（STOP）。任一恰好 1-byte 响应立即选择，不继续扫描。
- transport 错误与响应长度错误作为最近一次候选的结构化失败保留；完整候选轮失败后等待 500 µs。
  中断是全局结果状态，不伪造候选地址。
- 当前 MFi self-check 单测覆盖 `0x10` 失败、`0x11` 分离 select/read 成功及其 raw protocol-major。

### 构建与单元测试

2026-09-11 已使用 Android Studio JBR 25 离线强制重跑并通过：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :shared:testDebugUnitTest :mobile:assembleDebug :automotive:assembleDebug --offline --rerun-tasks
```

后续已将测试集精简为当前的 6 个关键用例。该结果只验证 Kotlin 逻辑和 APK 可构建，不代表任何
USB、I2C 或 MFi 实机链路已验证。

### 硬件未验证的假设

- `0x00` device-version 的 select STOP + pure-read 用于目标芯片发现；这来自 fixed LIVI 实现，仍需
  使用真实 MFi 芯片验证。
- 默认候选地址 `0x10`、`0x11`、CH341 的 I2C 传输和板载 I2C 后端均未在实机上验证。
- 后续阶段已补充 certificate 主体读取和 challenge-response 的纯 Kotlin 寄存器编排；芯片型号与所有
  硬件行为仍未验证。

## Genymotion APK 启动 Smoke（2026-09-11）

- 设备：`192.168.56.101:5555`（Genymotion `Pixel_6`）。
- 先使用 Android Studio JBR 25 离线执行：`./gradlew.bat :shared:testDebugUnitTest :mobile:assembleDebug :automotive:assembleDebug --offline`，构建成功，所有任务均为最新状态。
- 已通过 README 指定的 Genymotion ADB 对 `mobile/build/outputs/apk/debug/mobile-debug.apk` 执行 `adb install -r`，并启动 `com.shilapi.xcertplay/.MainActivity`；启动耗时 738 ms。
- 等待后 `dumpsys activity` 显示该 Activity 为前台、`RESUMED` 且窗口聚焦；`pidof com.shilapi.xcertplay` 返回 PID `3100`。清理后的 logcat 中 `AndroidRuntime`、`FATAL EXCEPTION` 及本包错误匹配均为 0。
- 本次仅验证 APK 生命周期/UI 启动链路；未测试、也不据此声称 USB、CH341、I2C 或 MFi 硬件功能已验证。无需修改应用代码。
## 阶段 4B：CH341 MFi 分段 stream（代码完成，硬件未验证）

- `Ch341I2cStreamEncoder` 现支持单次 transaction 的写入和读取各最多 65,535 字节。超过 32
  字节时，编码器使用 32 字节 CH341 stream 段：中间段以 `AA` 开始、以 `00` 结束并补零，且不发送
  STOP；续段不会重复前一段的 START 或地址（write + read 所需的 repeated START 除外）。
- 写入首段包含 START、写地址和首块数据，续段只含后续数据；读取按最多 32 字节的 ACK 块分段，最后
  一个字节由零长度 `C0` 读取并 NACK。write + repeated-start read 仍是一个 I2C transaction。
- Android bulk transfer 直接提交完整的已编码 buffer（最大满写加满读约 145 KiB），并严格拒绝短传输；
  不引入额外 USB 分块。每个 transaction 的最低超时由 I2C 频率、读写长度和 1 秒余量推导，配置值可更长。
- 该实现尚未在真实 CH341 上验证：分段 continuation、设备状态、NAK 语义及大 buffer transfer 均未知。
  MFi 寄存器逻辑不再受软件长度限制，但硬件验证和板载 I2C backend 仍然阻断端到端 MFi 认证。

## 阶段 5：transport-agnostic MFi self-check 与诊断 UI（代码完成，硬件未验证）

- `MfiSelfCheck` 只依赖 `I2cTransport`：先按 `0x00` device-version 选择首个有效的 `0x10`/`0x11` 候选，
  再读取其 protocol-major 作为 raw diagnostic，并以结构化结果保留扫描与读取失败。
- mobile APK 提供简洁板载 I2C 诊断页，可编辑 `/dev/i2c-N`；自检在单线程后台 executor 中执行，Activity
  销毁时关闭 executor。Linux native backend 已随 APK 打包；仍需实际 `/dev/i2c-N` 节点与 OS/SELinux 权限。
- automotive 页面只声明硬件尚未配置，不在驾驶场景添加输入。CH341 仍只接受部署时提供的 VID/PID 配置，未硬编码。

## TLS runtime probe (2026-09-11)

- The specified Genymotion device `192.168.56.101:5555` is Android 13/API 33, not API 29: with a temporary fake PKCS#1 RSA device key, the `LockdownPairRecordGenerator` root certificate (empty issuer/subject DN) and `CN=Device` device certificate both parsed in `AndroidOpenSSL` CertificateFactory; root self-verification and device verification with the root public key succeeded, and the PKCS#8 host key plus root certificate initialized `HarmonyJSSE` PKIX KeyManager, `AndroidOpenSSL` SSLContext, and `Java8EngineWrapper` SSLEngine. The custom X509Certificate/KeyManager fallback was therefore not run; this remains no API 29 evidence and predates validation of the production TLS channel.

## Stage 8D: LIVI-minimal USB NCM transport (code complete, hardware unverified)

- `Ntb16Codec` implements only the NTB16 layout used by LIVI `iap2-usbmux/src/ntb.rs`: one NTH16
  header, one NDP16 table with one datagram, little-endian u16 fields, and a zero pad byte when a
  block would end exactly on a 512-byte USB packet boundary. There is no NTB32, alignment, CRC, or
  NCM control-plane request handling.
- `NcmFunctionDiscovery` finds the NCM control interface (class `0x02`, subclass `0x0D`) and the
  data-class interface (class `0x0A`) with one bulk IN and one bulk OUT, preferring data alternate
  setting 1 as LIVI selects.
- `NcmUsbBridge` claims the control/data interfaces on a caller-opened connection, activates the
  data alternate setting, and exposes blocking Ethernet-frame `send`/`recv` that frame and
  reassemble NTB16 blocks. It owns and closes that connection.
- `EthernetIpv6Codec` strips or restores an untagged Ethernet II header around IPv6 payloads as the
  seam for a future Android VPN-backed IPv6 endpoint.

This is the NCM data-path seam only. It does not create an Android network interface or VPN tunnel,
does not run an AirPlay receiver or media session, and has not been verified against an iPhone or
vehicle head unit. The wired control client still advertises an endpoint only when the caller can
supply a real IPv6 endpoint and port.

### Build and unit tests

2026-09-12 已使用 Android Studio JBR 25 离线强制重跑并通过：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :shared:testDebugUnitTest :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug --offline --rerun-tasks
```

`shared` 保持 6 个关键永久单测（0 failures / 0 errors）。该结果只验证 Kotlin 逻辑与 APK 可构建，
不构成任何 NCM、USB、iPhone 或 CarPlay 实机验证。

## Stage 8E: AirPlay pairing core (code complete, hardware unverified)

- `AirPlayCrypto` wraps BouncyCastle `bcprov-jdk18on:1.79` for the CarPlay pairing handshake:
  X25519, Ed25519, HKDF-SHA512, SHA-512, and ChaCha20-Poly1305 (IETF 96-bit nonce), plus the
  AirPlay nonce helpers (`nonce64`, `nonceLabel`).
- `Tlv8Codec` implements the HomeKit/AirPlay TLV8 wire format with 255-byte fragmentation and the
  same-type separator.
- `Srp6a` implements the SRP-6a server for the fixed "Pair-Setup"/"3939" PIN over the RFC 5054
  3072-bit group and SHA-512.
- `PairSetup` performs unauthenticated pair-setup (M1-M6): SRP-6a proof exchange followed by
  encrypted long-term Ed25519 key exchange, persisting the controller LTPK in a `PairingStore`.
- `PairVerify` performs pair-verify (M1-M4): ephemeral X25519 plus Ed25519 signature against the
  stored controller LTPK, then derives the per-direction control-channel keys.
- `ControlCipher` frames the post-pair-verify control channel as 2-byte little-endian length,
  ciphertext, and 16-byte tag, with the length header as AEAD associated data.
- `RtspMessage` incrementally parses RTSP/HTTP-style requests and builds responses for the future
  TCP :7000 session.
- `MfiSapAuthSetup` implements the LIVI /auth-setup MFiSAP responder: ephemeral X25519, AES-128-CTR
  encryption under SHA-1("AES-KEY"/"AES-IV", shared), the MFi certificate, and the coprocessor
  signature (SHA-1 for protocol major 2, SHA-256 otherwise). It calls the blocking
  `MfiAuthenticationClient` and must run off the main thread.
- `AirPlayIdentity` and `PairingStore` are plain in-memory Kotlin models; persistence wiring is
  deferred to the session layer.

This is the pairing/control protocol core. It does not own a TCP :7000 listener, `/info`,
SETUP/RECORD stream handling, or media/input transport, and it has not been verified against an
iPhone or vehicle head unit. JCA/Android runtime behavior on the project's minSdk 29 has not been
device-verified; BouncyCastle is bundled for the X25519/Ed25519 primitives.

### Build and unit tests

2026-09-12 verified with Android Studio JBR 25 offline:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :shared:testDebugUnitTest :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug --offline --rerun-tasks
```

`shared` keeps 6 permanent unit tests (0 failures / 0 errors). A temporary JVM test exercising the
SRP client/server handshake, the full pair-setup/pair-verify round trip, the control-cipher round
trip, and the RFC 8439 ChaCha20-Poly1305 and RFC 7748 X25519 vectors passed and was then removed
to preserve the curated test count.

## Stage 8F: AirPlay transport and session (code complete, hardware unverified)

- `BplistCodec` implements the minimal `bplist00` encode/decode used on the CarPlay control
  channel: dictionaries, arrays, ASCII/UTF-16 strings, data, non-negative integers, 32/64-bit
  reals, and booleans. Negative integers are serialized as reals, matching the reference.
- `AirPlayConfig` models the accessory/display/audio identity used by `/info`.
- `AirPlayInfoPlist` builds the complete `/info` declaration: displays, view/safe areas, HID
  devices, audio formats/latencies, resource modes, and feature bitfields.
- `AirPlayHid` encodes the touch/knob/media/telephony descriptors and their input reports.
- `AirPlaySession` owns one TCP control connection: plaintext pairing, then encrypted RTSP after
  pair-verify, and routes `/pair-setup`, `/pair-verify`, `/auth-setup`, `/info`, SETUP, RECORD,
  TEARDOWN, POST `/command`, and POST `/feedback`. Session SETUP opens the encrypted event channel
  (Events-Salt keys) and a timing port; stream SETUP is routed through `AirPlayMediaHandler`.
  Touch/knob/media/telephony/Siri/night-mode commands are sent over the event channel.
- `CarPlayVpnService` owns the Android VPN tun, bridges it to the iPhone NCM Ethernet link through
  `Ipv6NcmBridge`, and binds the AirPlay listener to the link-local IPv6 address on `config.port`.
  CarPlay sockets intentionally remain subject to the VPN route and it reuses `AirPlaySession` for
  each accepted socket.

Media rendering remains a `MediaSink` seam, but the stream transport, decryption, NTP clock,
`/feedback` media-clock response, and keep-alive port are implemented in Stage 8G below. The wired
iAP2 control path, the VPN/NCM bridge, and this AirPlay session have not been verified against an
iPhone or vehicle head unit.

### Build and unit tests

2026-09-12 verified with Android Studio JBR 25 offline:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:OS = "Windows_NT"
.\gradlew.bat :shared:testDebugUnitTest :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug --offline --rerun-tasks
```

`shared` keeps 6 permanent unit tests (0 failures / 0 errors). A temporary bplist/info round-trip
test passed and was removed to preserve the curated test count.

## Stage 8G: AirPlay media transport and NTP (code complete, hardware unverified)

- `NtpClock` drives the CarPlay timing-port exchange over UDP: it sends PT_REQUEST (210), answers
  the phone's PT_REQUEST, and steers a local monotonic clock from PT_RESPONSE (211) samples using
  the LIVI step/slew, lowest-RTT pick, and delay-window logic. `AirPlaySession` starts it against
  the phone's SETUP `timingPort` and exposes `syncedNtp()` to the media layer.
- `ScreenStream` receives one TCP video stream, frames the 128-byte AirPlayScreenHeader, decrypts
  VideoFrame with the DataStream output key and per-frame nonce, and extracts avcC/hvcC codec
  config from VideoConfig. `CarPlayMediaEngine` binds the screen/audio/data ports and owns their
  lifetime.
- `AudioStream` binds the RTP data and RTCP control UDP ports and decrypts LIVI's RTP layout:
  12-byte header, ciphertext, 16-byte tag, and 8-byte little-endian nonce, with the header's last
  eight bytes as AAD. `AudioStreamCodec` maps the negotiated audioFormat bits to AAC-LC, Opus, or
  LPCM.
- `IapTunnel` receives the iAP2-over-CarPlay DataStream (type 130): NetSocketChaCha20Poly1305
  stream framing followed by APTransportPackage records, emitting `comm` iAP2 bodies.
- `AirPlaySession` answers `POST /feedback` from the active audio streams using the synced NTP64
  clock and each stream's first-sample anchor, and opens the optional `keepAlivePort` when the
  phone requests low-power keep-alive.

Decoded media is delivered to the `MediaSink` callback interface. Android MediaCodec/AudioTrack
rendering, the SurfaceView touch path, and USB bring-up orchestration remain outside this stage and
have not been verified on hardware.

### Build and unit tests

2026-09-12 verified with Android Studio JBR 25 offline:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:OS = "Windows_NT"
.\gradlew.bat :shared:testDebugUnitTest :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug --offline --rerun-tasks
```

`shared` keeps 6 permanent unit tests (0 failures / 0 errors). A temporary JVM test exercising the
LIVI audio RTP layout, screen frame header AAD, avcC/hvcC config detection, audio-format mapping,
and NTP clock initialization passed and was then removed to preserve the curated test count.

## Stage 8H: Android media rendering (code complete, hardware unverified)

- `AndroidMediaSink` implements the `MediaSink` seam with a MediaCodec H.264/H.265 decoder bound
  to a caller-supplied `Surface`, plus one MediaCodec/AudioTrack audio renderer per stream type.
- `MediaCodecSupport` converts CarPlay's length-prefixed screen NALs to Annex B, passes raw SPS/PPS
  (avcC) or the hvcC record as Android CSD, extracts RFC 3640 AAC access units and wraps them in
  ADTS, and byte-swaps the wired big-endian LPCM samples for AudioTrack. Opus packets are skipped:
  Android MediaCodec has no built-in Opus decoder, so a native decoder remains a gap.
- `CarPlayTouchMapper` normalizes Android MotionEvents into up to two `AirPlayContact`s for
  `AirPlaySession.sendTouch`.

This fills the rendering seam only. It does not create the full-screen SurfaceView host or the
USB/NCM/iAP2 orchestration that constructs `CarPlayMediaEngine`, and it has not been verified
against an iPhone or vehicle head unit.

### Build and unit tests

2026-09-12 verified with Android Studio JBR 25 offline:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:OS = "Windows_NT"
.\gradlew.bat :shared:testDebugUnitTest :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug --offline --rerun-tasks
```

`shared` keeps 6 permanent unit tests (0 failures / 0 errors). A temporary JVM test for avcC/hvcC
config splitting, Annex B conversion, ADTS framing, and OpusHead construction passed and was then
removed to preserve the curated test count.

## Stage 8I: Wired integration (code complete, hardware unverified)

- `CarPlayRuntimeConfig` is the deployment-owned identity boundary: measured Apple and CH341
  VID/PID pairs, the NCM host MAC, the VPN link-local IPv6 literal, and the iAP2 identification
  profile. There are still no built-in device IDs.
- `MfiRuntime` runs the documented `0x10`/`0x11` probe and wraps the first responding coprocessor
  as a `MfiAuthenticationClient`. `MfiSession` owns the backing I2C transport for shutdown.
- `CarPlayController` drives the complete wired sequence on one worker executor: CH341 or board
  I2C MFi discovery, iPhone USB permission and vendor-request re-enumeration, configuration 6,
  USBMUX, Lockdown Pair, carkit TLS, iAP2 CSM, NCM data-path open, VPN attach, and the AirPlay
  `7000` listener. It advertises `config.linkLocal` and `airPlayConfig.port` in the wired
  CarPlayStartSession and forwards touch to the active `AirPlaySession`.
- `CarPlayHostActivity` is the full-screen launcher host: a `SurfaceView` plus `AndroidMediaSink`
  and `CarPlayMediaEngine`, SurfaceView touch mapping through `CarPlayTouchMapper`, and VPN consent
  through `CarPlayVpnService.prepare`. It shows a deployment-configuration status until real
  VID/PIDs are supplied.
- `AirPlayPersistence` stores the accessory Ed25519 identity and paired-controller long-term keys
  in SharedPreferences, and `PairingStore` reports saves back to that store.
- The Lockdown USB PairRecord (host/root/device PEM material, HostID, SystemBUID, and WiFi MAC) is
  also persisted by `AirPlayPersistence` and restored through `LockdownPairRecord.restore`, so the
  carkit TLS path reconnects without re-requesting trust.

This closes the previously missing host/orchestration seam. It does not prove that two Android
USB connections can claim the USBMUX and NCM interfaces simultaneously, that the VPN link-local
route matches the iPhone NCM neighbor discovery, or that any of the stages operate against real
hardware. Opus decoding remains deferred: the wired reference path negotiates LPCM or AAC-LC, while
Opus is the wireless low-latency format, so this gap does not block the wired direct-connect scope.

### Build and unit tests

2026-09-12 verified with Android Studio JBR 25 offline:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:OS = "Windows_NT"
.\gradlew.bat :shared:testDebugUnitTest :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug --offline --rerun-tasks
```

`shared` keeps 6 permanent unit tests (0 failures / 0 errors).

2026-09-12 Genymotion smoke: `mobile-debug.apk` installed on the documented emulator and
`CarPlayHostActivity` launched as the launcher. The activity reached top resumed/focused state
(PID 8842, displayed +376 ms) with no `FATAL EXCEPTION` or `AndroidRuntime` entries in logcat. This
verifies only the host Activity/SurfaceView lifecycle on an emulator, not any USB, VPN, NCM,
CarPlay, or MFi hardware behavior.
