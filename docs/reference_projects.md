# 实现参考

## 唯一实现参考：LIVI

本项目的 CarPlay/iAP2/AirPlay 直连实现仅参考
[f-io/LIVI](https://github.com/f-io/LIVI) 的固定提交
[`0a3dcaa0bf30d5319506d0e47c7b0d46bc942ec3`](https://github.com/f-io/LIVI/tree/0a3dcaa0bf30d5319506d0e47c7b0d46bc942ec3)。
审计日期为 2026-09-11；该提交的根目录 `LICENSE` 是 GPL-3.0。

LIVI 是 Linux/macOS 原生实现，不是 Android SDK。本项目不复制、翻译或移植其 GPL 源码；每个
Android 模块都必须以 clean-room 方式重新设计，并单独验证 Android API 与实机行为。若未来分发的
代码成为其派生作品，必须先完成 GPL-3.0 合规评估。

### 已确认的有线 USB bring-up 事实

以下事实来自该固定提交的
`native/livi-helperd/crates/iap2-usbmux/src/{lib.rs,linux.rs}`：

- Apple vendor ID 为 `0x05AC`。
- Apple device-recipient IN vendor request 使用 `bRequest=0x52`、`wValue=0`、`wIndex=4`、
  `wLength=1`，用于要求手机暴露 CarPlay configurations。
- 重新枚举后选择 USB configuration `6`；LIVI 在 Linux 退出时恢复 configuration `4`。

Android 的 `UsbDeviceConnection.controlTransfer` 可以表达该 control request，且
`setConfiguration` 可以选择公开的 configuration 对象；但 Android 应用不能写 Linux sysfs
`bConfigurationValue`，也没有同步等待 USB 重新枚举的 API。因此本项目在请求后关闭连接、等待
`ACTION_USB_DEVICE_ATTACHED` 的新 `UsbDevice`、重新获得权限后才选择 configuration `6`。这只是
USB bring-up 边界，不代表 iAP2、配对、MFi、NCM、AirPlay 或媒体已经实现或验证。

### 已确认的 USBMUX 最小 TCP 事实

以下事实仅来自同一固定提交的
`native/livi-helperd/crates/iap2-usbmux/src/mux.rs`：USBMUX 先交换 version protocol `2`，随后发送
setup；每个 mux frame 有 16 字节大端头（protocol、总长度、magic `0xFEEDFACE`、16 位序号和保留字段）。
最小 TCP 使用 protocol `6`，以 SYN / SYN-ACK / ACK 建连，确认收到的 payload，并处理 FIN 与 RST；发送的
payload 上限为 16 KiB，lockdown 端口为 `62078`。

这些是协议事实的 clean-room 输入，不是源代码移植依据。Android 实现独立采用 `Iap2UsbSession`、线程和
资源关闭模型；没有复制 LIVI 的代码、命名、注释或结构。LIVI 的 GPL-3.0 边界仍适用于其源代码，任何未来
派生或分发判断必须另行完成合规审查。

### 明确排除

除该固定 LIVI 提交外，其他项目、设备、逆向材料和实现都不作为本项目依据，也不得用于填补未知
协议字段。
