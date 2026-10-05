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
| `rtcp` | NTP 时间、SR/RR/SDES 解析与生成、同通道解复用、SR 跟踪与非阻塞周期上报、LSR/DLSR 往返时延 |
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
4. **乱序 / 重复 / 迟到（重排窗口新语义）**：
   - **前向跳变不受重排窗口约束**。连续丢失任意数量报文（例如窗口为 64 时连丢 99
     个）后到达的新序号报文一律正常入缓冲并按时播放，网络恢复后流不会中断；
   - 重排窗口**只约束落后方向**：落后于已到达最高序号、且差距超过
     `reorder-window-packets` 的旧报文被明确丢弃并计入迟到统计；窗口之内的乱序包
     进入按扩展序号排序的有界 `TreeMap`，在播放点自动归位；
   - 相同扩展序号只保留一份，记为重复；
   - 已越过播放点的包记为迟到并丢弃计数。

   因此重排窗口衡量的是“愿意为乱序等待的向后距离”，而不是“允许的前向突发跨度”。
   恢复期间播放序列沿扩展序号严格递增，既不会重复输出也不会回退；恢复后的新报文到点
   即播放，不在缓冲中无限滞留。
5. **突发丢包恢复与统计**：序号机（RFC 3550 A.1）对前向跳变照常推进 `cycles`/
   `max_seq`，跨越 16 位回绕时同样正确。累计丢失按 A.3
   `lost = extended_max - base_seq + 1 - 去重接收数` 计算，被跳过区间的全部缺失报文
   都计入，恒为非负、不重复累计；迟到与重复不改变“丢失”定义（它们确实到达过）。
   周期 fraction lost 在每次报告**成功发送后**才推进基线，发送失败不会污染下一份报告。
6. **抖动与自适应延迟**：`JitterEstimator` 按 RFC 3550 A.8 维护
   `J += (|D| - J)/16`；`AdaptivePlayoutController` 以 `safetyMultiplier * J`
   为目标，网络恶化时按 `grow-gain` 快速增大，恢复后按 `shrink-gain` 缓慢收敛，
   始终夹在 `[min-delay, max-delay]` 内。播放时间以首包到达为锚点，沿 RTP 时间戳
   时间线推进并叠加当前延迟。
   突发恢复期间该延迟与抖动估计继续按既有规则收敛且不会越出配置边界。
7. **有界丢弃**：单 SSRC、单会话、全局三级包数上限。消费缓慢导致满时，新包按
   `BUFFER_FULL` / `GLOBAL_LIMIT` 过载丢弃并计数，绝不无界积压。
8. **RTCP（同通道 RTP/RTCP 解复用与周期上报）**：
   - `RtpSession.receive` 用负载类型区分 RTP（PT 0..127）与 RTCP（PT 200..204），
     同一传输通道上混发时分别处理；RTCP 报文绝不会被当作 RTP 计入解析拒绝统计，
     非法/截断 RTCP 计入独立的 RTCP 拒绝计数并被明确丢弃，不影响后续合法报文；
   - `RtcpReporter` 在独立守护线程上按 `rtp.rtcp-interval` 周期发送复合 RR + SDES
     （本端只收时），`setRtcpInterval(...)` 会立即重排节奏；发送异常不会终止周期任务、
     不阻塞媒体接收与播放，通道恢复后自动继续；`close()`/`stopRtcpReporting()` 停止
     周期任务并释放线程；
   - **报告块字段全部来自真实接收统计**：fraction lost（本周期）、24 位累计丢失、
     扩展最高序号来自 `ReceiveCounters`（A.3/A.1），到达抖动为 A.8 估计换算成 RTP
     时钟单位；SDES CNAME 取配置 `cname`。
   - **LSR/DLSR 与往返时延**：收到对端 SR 后，下一份 RR 的 LSR 取该 SR 的 NTP
     时间戳中间 32 位；DLSR 为收到 SR 到发出 RR 的本端墙钟间隔（1/65536 秒）。
     对端按 RFC 3550 6.4.1 计算 `RTT = A - LSR - DLSR`（A 为 RR 到达对端时的 NTP
     时间）。受控时钟实验下测量值与真实往返时延一致，量化误差不超过 2 ms。

## 统计口径

- `received` 统计所有到达本 SSRC 的报文（含重复）；
- 累计/周期丢失按 RFC 3550 A.3 使用**去重后**接收数：
  `lost = extended_max - base_seq + 1 - (received - duplicates)`；
- `fraction lost = (期望增量 - 去重接收增量) / 期望增量`；
- 突发跳过的整段缺失序号全部计入累计丢失；迟到/重复因确实到达，不计为丢失；
- 周期 fraction 的基线仅在报告成功发送后推进，通道故障期间不丢统计也不重复累计；
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
| `rtp.reorder-window-packets` | `64` | 允许的**落后方向**乱序序号跨度；前向跳变不受其约束 |
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

// 同一通道可直接喂入 RTCP（SR 等）；周期复合 RR 可绑定输出通道：
session.startRtcpReporting(transport);        // 按 rtp.rtcp-interval 周期发送
session.setRtcpInterval(Duration.ofSeconds(1)); // 运行期调整发送节奏
session.close();                               // 停止并释放 RTCP 线程
```

## 测试

```bash
mvn test
```

测试覆盖：报文解析与各类拒绝、序号/时间戳回绕、乱序归位、重复去重、迟到丢弃、
回绕丢包、**超过重排窗口的突发丢包恢复（含跨 16 位回绕）、恢复后严格递增不重复、
恢复后的迟到判定与缓冲排空、单 SSRC/会话/全局三级上限**、抖动自适应与有界性、
多 SSRC 复用与统计一致性、SSRC 冲突切换/终止、**同通道 RTP/RTCP 解用、非法 RTCP
拒绝与隔离、复合 RR 字段（fraction/累计丢失/扩展最高序号/抖动/CNAME）一致性、
LSR/DLSR 与往返时延、周期节奏配置、发送失败恢复与关闭释放线程、多会话 RTCP 隔离**、
回环 UDP 与内存管道双向连通，以及一条完整的乱序+丢包+抖动端到端链路。
