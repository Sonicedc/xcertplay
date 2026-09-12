# CH341 I2C 与 MFi 接口基线

本文档记录 CH341 USB-I2C 和 MFi 认证寄存器的设计输入。

状态：**设计基线为主；阶段 2A/3 的部分代码已实现，但均尚未经过本项目硬件验证**。

## 兼容性要求

项目必须同时支持：

1. 板载 I2C 总线。
2. 通过 USB 连接的 CH341 I2C 方案。

MFi 认证层不能直接依赖 CH341 的 USB 命令。应在认证层下建立统一 I2C
transport，两个后端实现同一组读写原语。

```text
CarPlay / MFi 认证层
        |
    MFi 寄存器客户端
        |
    I2C transport 接口
       /              \
板载 I2C 后端       CH341 USB 后端
   |                    |
SoC I2C             Android USB Host
   |                    |
   +------- MFi chip ----+
```

## CH341 I2C stream 指令

CH341 stream 使用以下控制字节：

| 字节 | 含义 |
|---|---|
| `AA` | 开始 I2C stream |
| `60` | 设置 I2C 频率为 20 kHz |
| `61` | 设置 I2C 频率为 100 kHz |
| `62` | 设置 I2C 频率为 400 kHz |
| `63` | 设置 I2C 频率为 750 kHz |
| `74` | I2C START |
| `80 + n` | 写出 `n` 字节数据 |
| `C0 + n` | 读入 `n` 字节数据 |
| `75` | I2C STOP |
| `00` | stream 结束 |

说明：

- `n` 是紧随控制字节的数据长度。
- `80 + n` 和 `C0 + n` 的最终控制字节由命令码和数据长度计算。
- 一个 stream 可以包含多次 `START`、写、读和 `STOP`。
- 单个 USB 传输的最大长度、状态字节和错误恢复需要在 CH341 传输层实现时验证。

### 阶段 2A 实现边界

- `Ch341I2cStreamEncoder` 将最多 65,535 字节的每方向请求编码为 32 字节 stream 段；中间段不含
  STOP，最后一段才结束 I2C transaction。此 continuation 行为仍待实机验证。
- 读操作使用 `C0 + (length - 1)` 读取并 ACK 前面的字节，随后使用零长度 `C0` 读取并 NACK
  最后一个字节。写后读会在读地址之前生成 repeated START。
- `Ch341UsbSession` 严格要求 Android bulk transfer 返回完整字节数。Android 的 `bulkTransfer`
  返回值无法区分 CH341/I2C NAK、超时或其他 USB 错误；当前实现不会把它误报为可确认的 NAK。
  CH341 状态和 NAK 语义仍需实机验证。
- 阶段 2A 不实现 address-only 地址探测：仅成功写出地址不能证明 I2C ACK。当前发现以 `0x00`
  device-version 的独立 select/read 响应为准；CH341 状态语义与实机结果仍未验证。

## MFi 芯片地址

固定 LIVI 仅给出 7-bit `0x10`、`0x11` 候选；实际总线结果待硬件验证，不据此扩展协议事实。

对应 I2C 线上的 8-bit 写/读地址：

| 7-bit 地址 | 写地址 | 读地址 |
|---:|---:|---:|
| `0x10` | `0x20` | `0x21` |
| `0x11` | `0x22` | `0x23` |

实现要求：

- 不得把 `0x11` 硬编码为唯一地址。
- 启动时对 `0x10` 和 `0x11` 做 I2C 地址扫描。
- 根据实际芯片、复位脚状态和总线扫描结果选择地址。
- 记录所选 7-bit 地址、8-bit 写/读地址和设备响应状态。

## MFi 寄存器交互

以下寄存器交互仅记录固定 LIVI 审计所得的当前实现边界；实际总线结果待硬件验证，不据此扩展协议事实。

| 寄存器 | 操作 | 预期内容 | 备注 |
|---:|---|---|---|
| `0x30` | 读 | certificate 长度 | 2 字节，大端 |
| `0x31` | 一次读 | certificate | 紧随 `0x30` 的实际长度；客户端一次读取完整内容 |
| `0x20` | 写 | challenge 长度 | 2 字节，大端；先写长度 |
| `0x21` | 写 | challenge | 1--128 字节 |
| `0x11` | 读 | signature 长度 | 2 字节，大端；客户端绝不写入 |
| `0x12` | 一次读 | signature | 紧随 `0x11` 的动态长度 |
| `0x10` | 写 | 签名启动命令 `01` | 写入后再读取状态 |

关键约定：

- 多字节长度字段按大端解释。
- `0x10` 的签名启动后先等待 10 ms，再每 10 ms 读取状态，最长 3 秒；轮询 I/O 失败继续，超时 best-effort 读取 `0x05`。
- certificate 和 signature 的分块读写不能假设寄存器自动递增。

### 阶段 3：device-version 发现（代码完成，未实机验证）

`shared` 的 `com.shilapi.xcertplay.mfi.MfiDeviceScanner` 只依赖 `I2cTransport`。它按 LIVI
顺序在一个总计 2 秒的窗口内循环 `0x10`、`0x11`：每次先纯写 `[0x00]`（STOP），再纯读 1 字节（STOP）。
任一候选收到恰好 1 字节的 device version 即立即选择并返回；完整候选轮失败后才等待 500 µs。结果保留被选择
芯片或每个候选的最近结构化失败。

该边界不把 certificate 长度或 protocol-major 当作存在判定。`I2cTransport` 没有可取消 I/O，故 2 秒是
已返回操作之间的重试窗口，而不是对单个底层 USB/I2C 调用的硬截止。该实现尚未在真实 MFi 芯片、CH341 或板载
I2C 上验证。

### 阶段 4A：MFi 证书和签名寄存器客户端（纯逻辑完成，未实机验证）

`MfiAuthenticationClient` 只依赖 `I2cTransport`，并且所有 API 都是阻塞式：调用方必须
离开 Android 主线程执行。所有寄存器读取均严格拆为一次 write-only register select（STOP）和
一次 pure read；普通 I/O 只对 `I2cTransportException` 重试，间隔 500 µs、总时间最多 2 秒。
`0x02` 的 protocol-major 直接作为原始 byte 返回。certificate 长度从 `0x30` 按大端读取（至少 1），
随后一次从 `0x31` 读取完整内容，默认防御上限是 65525 字节。

`signChallenge` 接受 1--128 字节 challenge，并统一写 `0x20` 长度、`0x21` challenge、`0x10=1`。
先等待 10 ms，再每 10 ms 读取 `0x10`，总期限 3 秒；轮询 I/O 失败继续。成功后从 `0x11/0x12`
读取动态长度 response，绝不写 `0x11`；超时时 best-effort 读取 `0x05` 并报告认证失败。
`Iap2MfiAuthenticationClient` 以固定 LIVI CSM 语义处理 certificate/challenge/response/success/failure，
但不拥有其 `Iap2CsmChannel`。

该阶段只完成 Kotlin 编排，仍未在真实 MFi 芯片上验证。`Ch341I2cTransport` 现在接受每个方向最多 65,535
字节，覆盖一次读完整 certificate 与写入寄存器加 128-byte challenge；这只解除软件长度拒绝，不能据此宣称
MFi 或 iAP2 认证已经实机完成。实现参考限定为 fixed `f-io/LIVI` 审计语义，未复制其代码。

## 预期认证流程

```text
1. 枚举 USB CH341 或板载 I2C 设备
2. 设置 I2C 频率
3. 按 `0x00` 的 select STOP + pure-read 扫描 0x10 / 0x11
4. 从 0x30 读取 certificate 长度
5. 从 0x31 一次读取完整 certificate
6. 通过 0x20 / 0x21 写入 challenge
7. 通过 0x10 写入 01 启动签名
8. 读取并判断签名状态
9. 从 0x11 / 0x12 读取 signature
```

每个阶段都应有日志、超时、重试和确定性错误码。总线和设备异常不能静默进入下一阶段。

## 双后端边界

### 板载 I2C 后端

- 使用系统 I2C 接口、Android I2C HAL 或 native `/dev/i2c-N`。
- 不参与 CH341 stream 编码。
- 只实现设备地址、寄存器地址、写 buffer、读长度和停止条件。

### CH341 USB 后端

- 使用 Android USB Host 或 libusb/JNI。
- 负责 CH341 设备枚举、接口/端点选择和批量传输。
- 将通用 I2C 操作编码为 `AA`、频率、`START`、写/读、`STOP`、`00` stream。
- 解析 USB 返回状态并处理超时、NAK 和重试。

### MFi 认证层

- 只调用通用寄存器接口。
- 不应包含 CH341 控制字节或板载 `/dev` 路径。
- 应负责地址选择、寄存器流程、长度解析和 challenge-response 编排。

## 验证顺序

先验证 transport，再验证 MFi：

1. CH341 USB 枚举。
2. I2C 地址扫描到 `0x10` 或 `0x11`，并读到 `0x00` device version。
3. 对一个已知寄存器执行读操作。
4. 读取 `0x30` 的 certificate 长度。
5. 从 `0x31` 一次读取完整 certificate。
6. 完成 challenge 写入和 signature 读取。
7. 对板载 I2C 后端重复相同 MFi 测试。

只有两个后端都能完成同一组 MFi 寄存器流程，才能认为兼容性实现完成。
### 阶段 4B 分段实现边界

`Ch341I2cStreamEncoder` 为 MFi 读写设置明确上限：单个 transaction 的 writeData 和 readLength
各最多 65,535 字节。超过一个 32 字节 stream 段时，中间段以 `AA` 开始，在有效命令后
以 `00` 结束并补零至 32 字节；它不发送 STOP，下一段重新 `AA` 后继续同一 I2C transaction。最后一段
才发送 `75` STOP 和 `00`。

写入的首段包含 START、写地址和数据，续段只含剩余数据；读操作每段最多读取 32 字节，中间读块 ACK
全部字节，最终读块用零长度 `C0` NACK 最后一个字节。写后读仍通过 repeated START 保持一个 transaction。
最大满写加满读的已编码 buffer 约为 145 KiB。实现以一次 Android `bulkTransfer` 发送/接收，不人为再拆为
多个 USB transfer；它按所选 I2C 频率计算该 transaction 的名义最低超时并加 1 秒余量，调用方配置的超时可更长。
真实 CH341 的分段 continuation、状态返回、NAK 语义与大 buffer transfer 均未验证；板载 I2C backend 的设备节点
权限与硬件行为也未验证，因此不能据此宣称硬件端到端认证可用。
