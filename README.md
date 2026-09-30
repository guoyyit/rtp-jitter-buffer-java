# RTP Jitter Buffer (Java)

接收侧 RTP / RTCP 实现：解析入站 RTP 报文、在网络抖动与丢包下按稳定节奏输出媒体、
生成与解析 RTCP SR/RR，并支持多会话并发接入与资源隔离。网络输入可使用本机回环 UDP
或进程内内存管道模拟。

## 模块结构

| 包 | 职责 |
| --- | --- |
| `codec` | RTP 报文头 / 扩展头解析、负载类型表、拒绝原因 |
| `model` | `RtpPacket`、`RtpExtension` 等值对象 |
| `rtcp` | SR / RR / BYE 的编解码、NTP 时间、周期发送调度器 |
| `stats` | 每 SSRC 序号状态、RFC 3550 抖动估计、RR 报告块、会话计数器 |
| `buffer` | 抖动缓冲、自适应播放延迟、跨会话全局缓冲预算 |
| `session` | 会话与每 SSRC 状态、SSRC 冲突处理、会话管理器 |
| `transport` | 传输抽象、本机回环 UDP、内存双工管道 |
| `clock` | 可注入时钟（系统时钟 / 测试时钟），保证缓冲计算可确定性测试 |
| `config` | `BufferProperties` 全部可调参数 |

## 缓冲模型

### 入站处理

1. `RtpParser` 按 RFC 3550 校验并解析固定头、CSRC 列表、扩展头（0xBEDE 等 profile
   的长度字段）与 padding。版本非 2、长度不足、CSRC/扩展/padding 越界、负载类型未知
   都会抛出带 `RejectReason` 的异常，由会话计入对应统计后丢弃。
2. 会话按 SSRC 多路复用，每个 SSRC 拥有独立的 `ReceptionStats`、`JitterBuffer`
   与 `AdaptiveDelayController`。
3. 16 位序号被扩展为不回绕的 long：以当前最高序号为基准做折叠（fold）判定，使序号
   回绕不影响乱序、重复、丢失与缓冲顺序。32 位 RTP 时间戳在抖动差分中按有符号 32 位
   处理，回绕点不会产生伪抖动。

### 乱序、重复与迟到

- 缓冲内部按扩展序号使用有序表存储，到期时按序号顺序输出，乱序包在有限重排窗口内
  `maxReorderWindow` 自动归位。
- 完全相同的扩展序号只保留一份，计为重复。
- 已播放过的序号再次到达，或落后最高序号超过重排窗口，明确判为迟到丢弃并计数
  （`lateDrops`）。
- 单会话深度达到 `perSessionMaxPackets` 时按 `dropPolicy` 释放：
  `DROP_NEWEST`（保延迟）或 `DROP_OLDEST`（保连续）。

### 自适应播放延迟

- 抖动按 RFC 3550 附录 A.8 计算：相邻包“到达间隔 − 发送时间戳间隔”之差的指数平滑
  （系数 1/16），单位为该负载类型的时钟 tick。
- `AdaptiveDelayController` 以 `jitter × jitterSafetyFactor` 为目标延迟：抖动增大时
  立即抬升吸收抖动；网络恢复后按 `delayRecoveryMsPerSecond` 线性收敛。延迟始终被
  限制在 `minDelayMs` 与 `maxDelayMs` 之间，有界且可配置。

### 资源隔离与背压

- 每个会话的解析异常只记本会话统计，不影响其他会话与传输接收线程。
- `GlobalBufferBudget` 约束所有会话的缓冲包总量。获取不到全局额度时按
  `globalBudgetDrops` 计数丢弃，消费再慢也不会无界积压；包释放时归还额度。

## RTCP

- 支持 SR（200）、RR（201）、BYE（203）的解析与生成；未识别类型以不透明原始块
  保留并可原样转发。复合报文做版本与边界校验，畸形报文计入 `malformedRtcp`。
- RR 报告块包含：fraction lost、累计丢失数、扩展最高序号、到达抖动、LSR / DLSR，
  与本地真实到达情况一致。累计丢失基于扩展序号 `expected − received`，重复包不计入
  received，乱序填补缺口后丢失数会相应修正。
- LSR 取所收 SR 的 NTP 时间戳中间 32 位；DLSR 为收到 SR 到生成 RR 的等待时长；
  往返时延 RTT = 本地收 SR 时刻 − LSR。
- `RtcpScheduler` 使用独立守护线程按 `rtcpIntervalMs` 周期发送，构造/发送失败被吞掉，
  绝不阻塞 RTP 媒体处理。

## SSRC 冲突处理

- 收到携带本会话自身 SSRC 的报文：按 RFC 3550 视为冲突，记录原因并拒绝。
- 同一 SSRC 的传输源地址发生变化：记录冲突事件（含原因与时间）。
  - `TERMINATE`（默认）：立即终止该 SSRC 并释放其缓冲。
  - `SWITCH`：在 `collisionLimit` 次以内切换到新源地址，超限后终止。

## 配置项（`BufferProperties`）

| 配置 | 默认 | 含义 |
| --- | --- | --- |
| `maxReorderWindow` | 64 | 乱序归位窗口（扩展序号差），超出判迟到 |
| `minDelayMs` | 20 | 自适应播放延迟下限（毫秒） |
| `maxDelayMs` | 500 | 自适应播放延迟上限（毫秒） |
| `initialDelayMs` | 80 | 启动初始播放延迟（毫秒） |
| `jitterSafetyFactor` | 1.5 | 目标延迟 = 估计抖动 × 该系数 |
| `delayRecoveryMsPerSecond` | 100 | 网络恢复后每秒向下收敛的毫秒数 |
| `perSessionMaxPackets` | 256 | 单会话缓冲包数上限 |
| `globalMaxPackets` | 4096 | 所有会话合计缓冲包数上限（-1 为不限） |
| `dropPolicy` | DROP_NEWEST | 缓冲满时丢新包或丢最旧包 |
| `rtcpIntervalMs` | 5000 | RTCP 周期发送间隔（毫秒） |
| `collisionPolicy` | TERMINATE | SSRC 冲突时终止或切换 |
| `collisionLimit` | 1 | SWITCH 策略下允许的冲突次数 |
| `knownPayloadTypes` | 空 | 非空时仅接受白名单负载类型；空则用 RFC 3551 静态表 + 动态 96–127 |
| `dynamicPayloadClockRate` | 90000 | 动态负载类型缺省时钟率 |

动态负载类型还可通过 `PayloadTypeRegistry.registerDynamicPayloadType(pt, rate)`
显式注册真实时钟率，避免动态音频 PT 被误按 90000 计算抖动。

## 快速使用

```java
BufferProperties props = BufferProperties.defaults();
GlobalBufferBudget budget = new GlobalBufferBudget(props.getGlobalMaxPackets());
RtpSessionManager manager = new RtpSessionManager(
        props, PayloadTypeRegistry.rfc3551(), budget, SystemClock.INSTANCE);

RtpSession session = manager.createSession("call-1", /*localSsrc*/ 0x12345678);
UdpLoopbackTransport transport = new UdpLoopbackTransport(/*rtp*/ 5004, /*rtcp*/ 5005);
manager.bindTransport("call-1", transport);
session.startRtcpSender(transport, new InetSocketAddress("127.0.0.1", peerRtcpPort));

// 消费线程按稳定节奏取出可播放的包
List<BufferedPacket> playable = session.pollPlayable(/*maxCount*/ 0);
```

不使用真实网络时可改用 `InMemoryTransport.pair(...)` 连接两条内存管道，便于确定性测试。

## 测试

```bash
mvn test
```

覆盖：报文校验与各类拒绝原因、乱序 / 重复 / 迟到、序号与时间戳回绕、多 SSRC 复用、
SSRC 冲突（终止 / 切换）、抖动自适应、缓冲深度与全局背压、RTCP SR/RR/BYE 编解码、
统计一致性（丢失率 / 累计丢失 / 抖动 / LSR-DLSR-RTT）、周期非阻塞发送、多会话隔离、
内存管道与本机回环 UDP 端到端。
