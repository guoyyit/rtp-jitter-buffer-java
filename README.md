# RTP Jitter Buffer (Java)

接收侧 RTP/RTCP 实现：在网络存在抖动、乱序、重复、丢包与回绕时，仍按稳定节奏
输出媒体；支持单会话内多 SSRC 复用、多会话并发与资源隔离，可用本机回环 UDP 或
内存管道模拟网络输入。基于 Java 17 与 Spring Boot 3，核心库本身不依赖 Spring。

## 模块结构

| 包 | 职责 |
| --- | --- |
| `rtp` | RTP 固定头/CSRC/扩展头解析与校验、16 位序号与 32 位时间戳回绕运算、PT 注册表 |
| `buffer` | 每 SSRC 有界乱序缓冲、RFC 3550 A.8 抖动估计、有界自适应播放延迟 |
| `stats` | 原子接收统计：接收/重复/迟到/过载丢弃/拒绝、累计丢失、周期丢包分数 |
| `session` | 会话解复用多 SSRC、RFC 3550 A.1 序号机、SSRC 冲突处理（切换/终止） |
| `rtcp` | NTP 时间、SR/RR/SDES 解析与生成、非阻塞周期上报、LSR/DLSR 往返时延 |
| `transport` | `PacketTransport` 抽象、回环 `UdpLoopbackTransport`、成对 `InMemoryTransport` |
| `engine` | `RtpReceiver` 会话注册表与全局缓冲总量限流 |
| `config` | `rtp.*` Spring 配置属性绑定 |

## 缓冲模型

1. **解析与拒绝**：入站报文先经 `RtpPacketParser` 校验。版本非 2、短于固定头、
   CSRC/扩展头越界、padding 非法、负载类型未注册都会抛出带 `RtpRejectReason`
   的异常，由会话捕获并按原因计数，不影响其他会话。
2. **多 SSRC 解复用**：每个 SSRC 对应独立的 `SsrcState`（序号机、时间戳、抖动缓冲、
   统计），互不影响。
3. **序号与回绕**：`SeqNum` / `TimeStamp32` 用有符号模 2^16 / 2^32 距离做乱序判定，
   `unwrap` 把 16/32 位值扩展到 64 位并选离期望值最近的回绕实例，因此回绕不破坏
   乱序、抖动与播放计算。
4. **乱序 / 重复 / 迟到**：
   - 在 `reorder-window-packets` 之内的乱序包进入按扩展序号排序的有界 `TreeMap`，
     在播放点自动归位；
   - 相同扩展序号只保留一份，记为重复；
   - 已越过播放点的包记为迟到并丢弃计数；
   - **重排窗口只约束落后方向**：落后于已到达最高序号且差距超过窗口的旧报文
     明确丢弃并计入迟到统计；前向序号跳变（突发丢包后的恢复）不受窗口约束，
     任意大小的前向跳变都会被接受，流不会因此中断。
5. **抖动与自适应延迟**：`JitterEstimator` 按 RFC 3550 A.8 维护
   `J += (|D| - J)/16`；`AdaptivePlayoutController` 以 `safetyMultiplier * J`
   为目标，网络恶化时按 `grow-gain` 快速增大，恢复后按 `shrink-gain` 缓慢收敛，
   始终夹在 `[min-delay, max-delay]` 内。播放时间以首包到达为锚点，沿 RTP 时间戳
   时间线推进并叠加当前延迟。
6. **有界丢弃**：单 SSRC、单会话、全局三级包数上限。消费缓慢导致满时，新包按
   `BUFFER_FULL` / `GLOBAL_LIMIT` 过载丢弃并计数，绝不无界积压。
7. **突发丢包恢复**：连续丢包超过重排窗口时（例如窗口 64、连丢 99 包），后续
   到达的新序号报文正常入缓冲并按时播放；恢复期间播放序列严格递增，不重复、
   不回退；被跳过区间的全部缺失报文计入累计丢失（RFC 3550 A.3），突发跨越
   16 位序号回绕时行为与统计保持一致；恢复不影响同会话其他 SSRC 与其他会话，
   三级缓冲上限在恢复过程中持续生效。
8. **RTCP**：
   - **分流**：同一通道上的 RTP 与 RTCP 报文按 RFC 5761 的方式区分（RTCP
     类型 200..204），RTCP 不会被当作 RTP 计入解析拒绝统计；非法或截断的
     RTCP 报文被明确拒绝并单独计数，不影响本会话后续报文与其他会话。
   - **周期发送**：`RtpSession.startRtcpReporting(sink)` 后，`RtcpReporter`
     在独立守护线程上按 `rtp.rtcp-interval` 周期发送复合 RR + SDES，调整该
     配置即改变发送节奏；发送异常不会终止周期任务也不阻塞媒体路径，通道恢复
     后自动继续；`RtpSession.close()` 停止周期任务并释放线程。
   - **报告字段来源**：fraction lost 为上一报告周期内 `(期望增量 - 去重接收
     增量) / 期望增量` 的 8 位定点值；累计丢失为 RFC 3550 A.3 的 24 位值；
     扩展最高序号来自 A.1 序号机；到达抖动以 RTP 时间戳单位上报（内部纳秒
     估计 × 时钟频率换算）；SDES CNAME 取自 `RtpSessionConfig.cname()`。
   - **往返时延**：收到对端 SR 后记录其 NTP 时间戳中间 32 位与本地到达时刻，
     下一份 RR 的 LSR/DLSR 据此生成；对端按 `RTT = A - LSR - DLSR`
     （`NtpTime.roundTripTimeNanos`）计算往返时延。验证方法见
     `RtcpClosedLoopTest`：受控时钟下注入已知单向时延，计算值与真实往返
     时延误差不超过 2 ms。

## 统计口径

- `received` 统计所有到达本 SSRC 的报文（含重复）；
- 累计/周期丢失按 RFC 3550 A.3 使用**去重后**接收数：
  `lost = extended_max - base_seq + 1 - (received - duplicates)`；
- 突发丢包时被跳过区间的每个缺失序号都计入累计丢失，不会出现负值或重复
  累计；恢复后继续到达的报文只更新期望数，不再改变已确认的丢失数；
- `fraction lost = (期望增量 - 去重接收增量) / 期望增量`；
- SSRC 级统计与真实报文到达一致，会话级统计为各 SSRC 与解析拒绝的聚合。

## 配置项

在 `application.properties` 用 `rtp.*` 配置；编程方式可直接使用
`JitterBufferConfig.builder()` 与 `RtpSessionConfig.builder()`。

| 属性 | 默认 | 含义 |
| --- | --- | --- |
| `rtp.clock-rate` | `8000` | RTP 时钟频率，RTP 时间戳 ↔ 纳秒换算 |
| `rtp.payload-type` | `0` | 会话接受的静态负载类型；动态 PT 需显式注册 |
| `rtp.global-max-buffered-packets` | `16384` | 所有会话合计缓冲包数硬上限 |
| `rtp.max-ssrcs` | `32` | 单会话最多跟踪的 SSRC 数 |
| `rtp.max-packets-per-session` | `4096` | 单会话缓冲包数上限 |
| `rtp.max-packets-per-ssrc` | `512` | 单 SSRC 缓冲包数上限 |
| `rtp.reorder-window-packets` | `64` | 允许的落后方向乱序序号跨度；落后于最高序号超出的旧包丢弃，前向跳变不受限 |
| `rtp.min-delay` | `10ms` | 自适应播放延迟下限 |
| `rtp.initial-delay` | `60ms` | 起始播放延迟 |
| `rtp.max-delay` | `400ms` | 自适应播放延迟上限 |
| `rtp.rtcp-interval` | `5s` | 复合 RTCP 发送周期 |

抖动控制还有 `grow-gain`（默认 0.5，恶化时增长速度）、`shrink-gain`（默认 0.05，
恢复时收敛速度）、`safety-multiplier`（默认 1.5，目标延迟相对抖动的倍数），
通过 `JitterBufferConfig.Builder` 设置。

## 快速使用

```java
RtpReceiver receiver = new RtpReceiver(16_384);
RtpSession session = receiver.createSession("call",
        RtpSessionConfig.builder().localSsrc(1234).clockRate(8000).build());

session.receive(rawUdpBytes, length, System.nanoTime());   // 入站
List<RtpPacket> ready = session.pollReady(System.nanoTime()); // 稳定节奏输出

// 周期 RTCP 上报（RR + SDES），发送目标通常是同一个传输通道
session.startRtcpReporting(data -> transport.send(data, data.length));
...
session.close(); // 停止上报并释放缓冲与线程
```

> 兼容性：已有配置项无需迁移即可继续使用；仅 `rtp.reorder-window-packets`
> 的语义从"前向跨度限制"更新为"仅约束落后方向"，前向跳变始终被接受。

## 测试

```bash
mvn test
```

测试覆盖：报文解析与各类拒绝、序号/时间戳回绕、乱序归位、重复去重、迟到丢弃、
回绕丢包、抖动自适应与有界性、多 SSRC 复用与统计一致性、SSRC 冲突切换/终止、
突发丢包恢复（含回绕、恢复后迟到判定、统计口径、缓冲上限与多路隔离）、
RTCP SR/RR 生成解析、RTP/RTCP 同通道分流、报告字段与真实统计一致性、
LSR/DLSR 往返时延受控验证、周期发送节奏/故障恢复/关闭释放、多会话并发隔离
与全局限流、回环 UDP 与内存管道连通，以及一条完整的乱序+丢包+抖动端到端链路。
