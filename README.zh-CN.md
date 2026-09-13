# xcertplay

[English](README.md) | 中文

`xcertplay` 是面向 Android 车机的 CarPlay 接收端项目。它通过 Android USB Host
连接 iPhone，并使用 CH341 USB-I2C 桥接芯片或板载 I2C 总线访问 MFi 认证芯片。

> 当前仍在绝赞开发中。

## 项目简介

- 面向 Android Auto 投射模式和 Android Automotive OS 的应用模块。
- 为 CH341 桥接芯片和原生 `/dev/i2c-N` 设备提供统一 I2C transport。
- MFi 芯片发现、证书读取和 challenge-response 签名。
- iPhone USB bring-up、USBMUX、Lockdown 配对、carkit TLS 和 iAP2 控制。
- USB NCM 数据链路到 Android VPN IPv6 端点的桥接。
- AirPlay 配对、加密控制与事件通道、HID 输入、NTP、媒体解密、视频/音频渲染和触摸转发。

## 当前进度

有线连接已测试，it works。

## 工程结构

| 路径 | 用途 |
| --- | --- |
| `common/` | 两个目标共用的 CarPlay 宿主界面、设置、持久化和应用资源。 |
| `mobile/` | 使用共享 CarPlay 主机界面的 Android 应用。 |
| `automotive/` | 使用共享主机界面并支持高级音频通道映射的 Android Automotive OS 应用。 |
| `shared/` | Car App Library 代码，以及 CH341、I2C、MFi、iPhone、iAP2、NCM、VPN、AirPlay 和媒体实现。 |

## 环境要求

- 启动 Gradle 需要 JDK 17 或更高版本；daemon 通过 Gradle toolchain 解析 Java 25。
- Android SDK Platform 37。
- Android NDK `28.2.13676358`。
- 仓库已配置 Gradle Wrapper `9.5.0`、Android Gradle Plugin `9.3.0` 和
  Kotlin `2.2.10`。
- 硬件验证需要支持 USB Host/OTG 的 Android 设备以及 MFi 硬件。

## 构建

在 Windows PowerShell 中：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :shared:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
```

在 macOS 或 Linux 中：

```bash
./gradlew :shared:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
```

构建未签名 release APK：

```powershell
.\gradlew.bat :mobile:assembleRelease :automotive:assembleRelease
```

预期输出：

```text
mobile/build/outputs/apk/debug/mobile-debug.apk
automotive/build/outputs/apk/debug/automotive-debug.apk
mobile/build/outputs/apk/release/mobile-release-unsigned.apk
automotive/build/outputs/apk/release/automotive-release-unsigned.apk
```

## 致谢

感谢 [LIVI](https://github.com/f-io/LIVI) 项目为本项目提供了重要参考。

## 许可证

本项目采用 [GNU General Public License v3.0](LICENSE) 许可。
