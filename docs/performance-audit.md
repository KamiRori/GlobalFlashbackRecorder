# Performance Audit — Global Flashback Recorder

**日期**：2026-09-23  
**范围**：`src/main/java/com/globalflashback/**`（阶段 0–5 当前实现）  
**证据**：源码静态分析 + Spark 采样 [I0TWmsO7aH](https://spark.lucko.me/I0TWmsO7aH)（Paper 26.2，2 玩家，MSPT med≈17.9 / p95≈35.7 / max≈358）  
**本阶段产物**：仅审计，**不修改核心实现**。

---

## 0. 执行摘要

当前 Recorder 是：

```text
几乎全部工作在 Paper Server Thread 上同步完成
Async 管道基本未落地（ReplayBuffer 名义上支持 drain，但无人异步消费）
Flashback 编码在 /gfr record stop 时主线程同步执行
```

与目标模型（Main Thread Minimal Capture + Async Pipeline）差距很大。

在 **2 玩家、实体极少** 的 Spark 中，MSPT 已可冲到 **358ms**，说明瓶颈不在「实体数量」，而在 **每 Tick 全量 / 半全量状态采集与序列化**。

近期已做一版「双层 fingerprint」缓解（cheap O(sections) + 偶发 full CRC），但主线程仍承担：全玩家/全实体重建、`packAll` 玩家元数据、物品组件编码、Diff、内存累积、stop 时同步 Encode。

---

## 1. 当前线程模型

| 路径 | 线程 | 说明 |
|------|------|------|
| `runTaskTimer` → `Session.onTick` | **Server Thread** | 每 Tick 一次完整录制循环 |
| `ServerStateCapture.capture` | **Server Thread** | 读 Bukkit/NMS，建 `GlobalSnapshot` |
| `SnapshotDiffer.diffFrame` | **Server Thread** | 全量 equals 比较 |
| `RecordingListeners` / `SwingSampler` | **Server Thread** | 事件 + 采样 → SideChannel |
| `EffectOutboundTap26_2` | **Netty EventLoop**（写出口）+ offer 到无界队列 | 粒子/声音包旁路；解码/编码仍可能触碰 Registry |
| `FlashbackEncoder.encode` | **Server Thread**（stop 时） | 整段 Replay → `.zip` |
| `ReplayBuffer.drain` | **无人调用** | 「Async Writer」仅为接口预留 |

**结论**：没有 Diff Worker / Encoder Worker / IO Worker。`ReplayBuffer` 与 `ReplayDocument` 在主线程双写；录制期间内存中保留 **全部 Delta + Keyframe 列表**。

---

## 2. 完整数据流（当前实现）

```text
/gfr record start  (main)
  └─ capture(initial, encodeChunkPayloads=true)
       ├─ 每玩家: capturePlayer + toEntityState(player)
       ├─ 半径内已加载 chunk: captureChunkIfLoaded (LevelChunkWithLight 编码)
       ├─ seed ChunkBlockCache (可能 packBlockIds 全高度)
       └─ world.getEntities(): captureEntity
  └─ register listeners + effect tap + swing sampler
  └─ runTaskTimer(1)

每 Tick (main) Session.onTick
  ├─ SwingSampler.sample()          // 扫所有在线玩家 NMS swinging
  ├─ capture(tick, encodeChunkPayloads=keyframeDue)
  │    ├─ 全玩家重建 PlayerState（含 hotbar/equipment/metadata packAll）
  │    ├─ 半径内 chunk: cheapFingerprint [+/- deep full CRC]
  │    │    └─ 不匹配 → diffChunkBlocks (packBlockIds 差分) → silent BlockChange
  │    └─ 全世界非玩家实体重建 EntityState
  ├─ SnapshotDiffer.diff(previous, current)   // 全量 Map equals
  ├─ sideChannel.drainUpTo + merge
  ├─ buffer.appendDelta + document.addDelta + deltaList.add  // 三份保留
  └─ 若 keyframeDue: Keyframe(current) 再入 buffer/document/list

/gfr record stop  (main)
  ├─ verifySeek(SnapshotApplier)  // 主线程重放全部 delta/keyframe
  └─ FlashbackEncoder.encode      // 主线程同步编码写盘
```

**出口格式**：逻辑 `ReplayDocument` → `StateActionEncoder26_2` → `game_packet` / `move_entities` / `next_tick` → ZIP（`metadata.json` + `cN.flashback`）。

---

## 3. Profiling 证据（Spark I0TWmsO7aH）

| 指标 | 值 |
|------|-----|
| TPS 1m | ≈19.98 |
| MSPT med / p95 / max | ≈17.9 / 35.7 / **358** |
| 玩家 / 实体 | 2 / ≈18 |
| CPU process 1m | ≈25% |
| Heap | ≈2.5GB / 8GB |

采样栈中反复出现（字符串提取）：

- `NmsAdapter26_2.fingerprintChunk` / `packBlockIds` / `chunkContentFingerprint`
- `captureChunkIfLoaded` → `ClientboundLevelChunkWithLightPacket`
- `ServerStateCapture.capture` / `resolveChunk`
- `captureEntity` / `captureEntityMetadata` / `encodeGamePacket`
- `GlobalReplayRecorder$Session`（tick 路径）
- stop 路径：`FlashbackEncoder` / `StateActionEncoder` / `ItemStackCodec`

**解读**：在几乎空图下仍有 Recorder 明显主线程成本；max MSPT 说明存在 **Tick Spike**（Keyframe 全量重编码 chunk、静默差分、或 stop/seek 不在此采样窗口内时的瞬时负载）。

---

## 4. 问题清单（按严重度）

---

### [CRITICAL] #1 — 整条 Pipeline 绑定主线程

**Current implementation**  
Capture → Diff → 内存累积 →（stop）Seek Verify → Encode → Disk IO 均在 Server Thread。

**Problem**  
MSPT 与录制时长、Delta 规模、Keyframe 频率强绑定；stop 时会卡死主线程。

**Evidence**  
`GlobalReplayRecorder.stop` / `Session.onTick` / `FlashbackEncoder.encode` 无异步移交。

**Proposed optimization**  
主线程只产出 **不可变** `GlobalSnapshot` 切片 / dirty 事件；有界队列交给 Diff/Encode/IO Worker。禁止在 async 线程碰 Bukkit World/Entity。

**Expected benefit**  
录制中 MSPT 与编码解耦；stop 变为「封队列 + 等待 worker」。

**Risk**  
线程边界错误会导致脏读/崩溃；需严格「主线程拷贝完成后再入队」。

---

### [CRITICAL] #2 — 每 Tick 全量重建 Player / Entity 状态

**Current implementation**  
`capturePlayer`：位置、装备、热键栏（`ItemStack.OPTIONAL_STREAM_CODEC`）、`packAll` 元数据、药水等，**每个在线玩家每 Tick**。  
`world.getEntities()`：**每个相关世界每 Tick** 全实体 `captureEntity`。  
玩家还通过 `toEntityState` **双份**进入 `players` + `entities`。

**Problem**  
无 Dirty Tracking；玩家微动也会触发完整 `PlayerState` 分配 + 序列化 + Diff equals。

**Evidence**  
`ServerStateCapture.capture` 循环；Spark 中 `capturePlayer` / `captureEntityMetadata` / `ItemStackCodec` 出现。

**Proposed optimization**  
事件 / 采样标记 dirty；不变字段跳过；Player 与 Entity 去重（录制模型只保留一份权威表示）。

**Expected benefit**  
玩家数线性成本大幅下降；分配与 GC 显著降低。

**Risk**  
漏标 dirty 会丢旋转/装备/姿势；需对照正确性测试。

---

### [CRITICAL] #3 — Chunk 静默检测与 `packBlockIds` 内存/CPU

**Current implementation**  
- 跟踪半径默认 **8** → 每玩家最多约 **17×17≈289** 已加载 chunk。  
- 已优化：每 Tick **cheapFingerprint**（section 序列化长度）；cheap 变或每 20 Tick deep 才 full CRC。  
- `ChunkBlockCache` 仍可能为每个 chunk 持有 `int[16*16*height]`（height≈384 → **约 384KB/chunk**，289 chunk → **约 100MB+** 量级）。  
- `diffChunkBlocks` 在变化时仍 **全高度 pack 两份数组再逐格比较**。

**Problem**  
cheap 层已降主路径成本，但 **deep scan、首次 seed、WE paste 差分、Keyframe 全量 `captureChunkIfLoaded`** 仍是 Spike 与堆压力来源。

**Evidence**  
Spark 中 `packBlockIds` / `fingerprintChunk` / `LevelChunkWithLightPacket`；`CaptureOptions.DEFAULT.radius=8`。

**Proposed optimization**  
- 按 **section** 存 fingerprint / 差分，禁止整 chunk `int[]`。  
- Keyframe **不要**主线程同步重编所有 `LevelChunkWithLight`；或降频 + async 编码已捕获的 payload。  
- 半径可配置并默认收紧（例如 4–6），或按 view-distance。  
- WE paste：优先钩 FAWE/批改 API 或区块级 dirty，而不是全图轮询。

**Expected benefit**  
MSPT p95/max 下降；堆常驻显著下降。

**Risk**  
section 差分漏改会导致回放方块错误；需静默改方块回归用例。

---

### [HIGH] #4 — Diff 是全量 Snapshot equals，无字段级 Dirty Mask

**Current implementation**  
`SnapshotDiffer` 对 worlds/chunks/players/entities 做 `equals`；`PlayerState`/`EntityState` 含 metadata blob、equipment、hotbar。

**Problem**  
即使只有坐标变化，也要比较整个 record（含 `byte[]` metadata）；并常产出完整 `PlayerUpsert`。

**Evidence**  
`SnapshotDiffer.diffPlayers` / `diffEntities`；`onTick` 每 Tick 调用。

**Proposed optimization**  
Dirty mask（POSITION/ROTATION/VELOCITY/EQUIPMENT/…）；Diff 可部分异步（仅比较已不可变快照）。

**Expected benefit**  
Delta 更小、分配更少、编码更轻。

**Risk**  
Mask 错误会导致回放缺装备/姿势。

---

### [HIGH] #5 — Keyframe = 主线程全量重编码 Chunk Payload

**Current implementation**  
`keyframeDue` → `encodeChunkPayloads=true` → 半径内每个已加载 chunk 新建 `ClientboundLevelChunkWithLightPacket` 并 `encodeGamePacket`。

**Problem**  
周期性 Tick Spike（默认 interval 100 Tick ≈ 5s）。与 Spark max MSPT 形态一致。

**Evidence**  
`Session.onTick` + `captureChunkIfLoaded`；Spark 栈含 `ClientboundLevelChunkWithLightPacket`。

**Proposed optimization**  
主线程只标记 keyframe 边界 + 引用已缓存 payload；编码挪到 async。或 Keyframe 仅逻辑快照、ZIP 仍单 c0（已如此）则 **不必每 keyframe 重编码 light 包**。

**Expected benefit**  
消除周期性 Spike。

**Risk**  
Seek verify 依赖内存 Keyframe 内容；需重新定义 verify 与编码语义。

---

### [HIGH] #6 — 录制期无界内存增长（三份缓冲）

**Current implementation**  
每个非空 Tick：`ReplayBuffer` + `ReplayDocument.deltas` + `Session.deltaList`；Keyframe 同样三份。

**Problem**  
长时间录制 → 堆线性增长；无 max duration / max MB / backpressure。

**Evidence**  
`Session.onTick` 三处 `add`；`ReplayBuffer` 无 capacity。

**Proposed optimization**  
单一权威缓冲；有界队列；滚动落盘；超限策略（拒录 / 降采样 motion，**禁止丢** kill/block 等关键事件）。

**Expected benefit**  
长时间录制可预测内存。

**Risk**  
落盘分段需与 Flashback ZIP 滚动语义对齐。

---

### [HIGH] #7 — stop 路径：Seek Verify + 全量 Encode 堵主线程

**Current implementation**  
`verifySeek` 重放全部 delta/keyframe；随后 `FlashbackEncoder.encode` 遍历所有 Tick 建 stream。

**Problem**  
长录制 stop 时 TPS 归零级卡顿。

**Evidence**  
`GlobalReplayRecorder.stop`；Spark stop 相关栈（同会话可能含 encode）。

**Proposed optimization**  
Verify/Encode 异步；可选抽样 verify；编码 worker + 进度回调。

**Expected benefit**  
运维可接受的 stop 延迟。

**Risk**  
异步 encode 期间需冻结新录制或双缓冲会话。

---

### [MEDIUM] #8 — SideChannel / EffectTap 分配与无界队列

**Current implementation**  
`ConcurrentLinkedQueue` 无容量；`drainUpTo` 整表 poll 再 put-back deferred；EffectTap 每效果 `encodeGamePacket` + hash 去重。

**Problem**  
爆炸/大量粒子时队列膨胀；Netty 线程做编码增加延迟抖动。

**Evidence**  
`RecordingSideChannel`；`EffectOutboundTap26_2`。

**Proposed optimization**  
有界 MPSC；效果采样/限流；主线程只收「已拷贝的 byte[]」。

**Expected benefit**  
异常场景下可控。

**Risk**  
限流会丢装饰性效果（可接受）；不可丢方块/伤害类（方块已走另一路径）。

---

### [MEDIUM] #9 — `RecordingListeners` 大量 `runTask` 推迟

**Current implementation**  
几乎每个方块事件 `Bukkit.getScheduler().runTask` 再读最终方块。

**Problem**  
高物理 TPS 时调度器任务风暴；与同 Tick 捕获时序纠缠。

**Evidence**  
`RecordingListeners.scheduleCapture` 等。

**Proposed optimization**  
同 Tick 批处理 dirty 坐标集合；MONITOR 末尾一次性 flush。

**Expected benefit**  
降低调度与重复 offer。

**Risk**  
时序需与破坏粒子/音效顺序对齐。

---

### [MEDIUM] #10 — 对象分配热点（主线程）

**Current implementation**  
每 Tick：`PlayerState`/`EntityState`/`ReplayItemStack`×9×玩家、`MetadataBlob`、`ArrayList` merge、`List.copyOf`、Diff 结果等。

**Problem**  
GC 与分配带宽随玩家/实体上升。

**Evidence**  
源码结构；Spark 伴随 MSPT 升高。

**Proposed optimization**  
primitive/紧凑结构；复用缓冲；仅 dirty 分配。

**Expected benefit**  
GC pause / alloc 下降。

**Risk**  
过度复用可变对象破坏不可变边界。

---

### [LOW] #11 — 锁

**Current implementation**  
`GlobalReplayRecorder` 方法 `synchronized`；tick 与 command 同锁。  
高频路径几乎无细粒度锁竞争（单录制会话）。

**Problem**  
目前不是主瓶颈；引入多 worker 后锁设计会变关键。

**Proposed optimization**  
会话状态机 + 无锁/单写者队列；避免在 tick 持锁做 capture。

**Expected benefit**  
为异步管道铺路。

**Risk**  
状态机错误导致重复 start/stop。

---

### [LOW] #12 — Client Motion 120Hz（SPEC 有，代码无）

**Current implementation**  
未实现独立 Client Motion Capture 管道。

**Problem**  
未来 100 玩家 × 120Hz = 12k samples/s，若按当前「每 sample 多层对象」会直接打爆。

**Proposed optimization**  
紧凑 binary sample 批处理；独立有界队列；可降采样。**现在不要提前过度设计**，但审计必须登记风险。

**Expected benefit**  
N/A（未上线）。

**Risk**  
过早抽象。

---

## 5. 分类汇总

### 5.1 Main Thread 热点

1. 每 Tick 全量 `capturePlayer` / `getEntities`+`captureEntity`  
2. Chunk cheap/deep fingerprint + 偶发 `packBlockIds` / Keyframe `LevelChunkWithLight`  
3. 全量 `SnapshotDiffer`  
4. SideChannel merge + 三份缓冲写入  
5. stop：`verifySeek` + `FlashbackEncoder`

### 5.2 Async 热点

**基本不存在。** Netty 上仅 EffectTap 旁路编码。

### 5.3 最大 CPU

主线程状态采集与（Keyframe 时）chunk 包编码；stop 时整段 encode。

### 5.4 最大内存

`ChunkBlockCache` 全高度 `int[]`；录制期全部 `DeltaFrame`/`Keyframe` 三份保留；chunk payload `byte[]`。

### 5.5 最大 GC 来源

每 Tick 新建 Player/Entity/Item/Metadata/List；Keyframe 大批量 `byte[]` packet。

### 5.6 最大 IO

stop 时同步写 ZIP（Buffered，但仍在主线程）。录制中几乎无增量落盘。

### 5.7 最大对象分配

见 #2/#10；次要为 Diff/`List.copyOf`。

### 5.8 最大锁竞争

当前低；异步化后需重新评估。

### 5.9 最大重复计算

- 玩家同时存在于 players 与 entities  
- 未变实体仍完整 capture + equals  
- Keyframe 重编码已存在的 chunk 视觉包（逻辑 seek 用）  
- SideChannel drain 的 poll/requeue

### 5.10 Queue / Buffer 风险

| 结构 | 有界？ | 风险 |
|------|--------|------|
| `RecordingSideChannel` | 否 | 效果/方块事件风暴 OOM |
| `ReplayBuffer` | 否 | 长录制 OOM |
| `Session.deltaList` / document | 否 | 同上 |
| `ChunkBlockCache` | 否（随跟踪 chunk） | 大半径常驻堆 |

---

## 6. 最值得优化的 5 个位置（优先级）

| # | 位置 | 级别 | 一句话 |
|---|------|------|--------|
| 1 | `Session.onTick` 主线程全管道 | CRITICAL | 拆出 async Diff/Encode/IO |
| 2 | `ServerStateCapture` 全量玩家/实体 | CRITICAL | Dirty Tracking |
| 3 | Chunk Keyframe 全量 `LevelChunkWithLight` | HIGH | 去掉周期性 Spike |
| 4 | `ChunkBlockCache` / `packBlockIds` | HIGH | section 级差分与内存 |
| 5 | 无界三份缓冲 + stop 同步 encode | HIGH | 有界 + 异步落盘 |

---

## 7. 与目标架构的差距

目标：

```text
Main Minimal Capture → Immutable Data → Bounded Queue → Diff/Keyframe/Compress/Encode/IO
```

现状：

```text
Main: Capture+Diff+Buffer+Document
Stop(Main): Seek+Encode+IO
Async: 几乎空
```

`ReplayBuffer` 注释写「async Encoder」，**实现未接线**。

---

## 8. 正确性约束（优化时不可破）

审计要求后续每一阶段优化后仍保证：

- Tick 顺序、`next_tick` 轴正确  
- 方块（含静默插件改方块）不丢  
- 实体生成/销毁、骑乘、鱼钩 spawnData  
- 玩家运动（当前为 move_entities）、挥臂、装备/热键栏  
- Dimension / 生死等关键事件不因 backpressure 丢弃  
- 禁止异步线程直接读 Bukkit `World`/`Entity`

---

## 9. 建议的阶段计划（与用户 Phase 对齐）

| Phase | 内容 | 本审计对应 |
|-------|------|------------|
| 1 | Profiling | 本文件 + 可重复 Spark/压力脚本（待建） |
| 2 | Main Thread | Dirty capture、减全量遍历、Keyframe 去 Spike |
| 3 | Alloc/GC | 紧凑状态、少 `List.copyOf`、缓冲复用 |
| 4 | Async pipeline | 真正接线 ReplayBuffer → workers + backpressure |
| 5 | Delta/Dirty mask | SnapshotDiffer 字段级 |
| 6 | Motion 120Hz | 未实现；上线前专项 |
| 7 | Keyframe | 与 #5 合并设计 |
| 8 | Encoder/IO | stop 异步化、批量写 |
| 9 | Stress Test | 10/50/100 玩家矩阵 + p95/p99 |

**每阶段门禁**：编译 → 正确性（含 Flashback 回放抽测）→ 同负载 Spark 对比 → 才宣称提升。

---

## 10. 可重复压力测试草案（尚未实现）

建议后续加入 `docs/performance-benchmark-plan.md` 与自动化/手测清单：

| 场景 | 要点 |
|------|------|
| Idle + record | 基线 Recorder MSPT |
| 10/50/100 假人 | 玩家规模 |
| 大量实体 | `getEntities` 成本 |
| 大量方块/WE paste | fingerprint + packBlockIds |
| Keyframe 密集 | interval=20 |
| 长录制 10–30min | 内存曲线 |
| stop encode | 主线程阻塞时长 |

记录：Avg/P95/P99 MSPT、Recorder 分段计时、Heap、GC、Queue depth、Replay MB/min。

---

## 11. UNKNOWN / 待实测

- 生产环境 50/100 玩家下各热点占比（需新 Spark，不能外推 2 玩家结论到绝对值）。  
- FAWE 批改是否有可订阅的 Paper/插件事件（需查实际 FAWE 26.2 API，标记 **UNKNOWN** 直至核实）。  
- `EffectOutboundTap` 在 Netty 线程调用 `encodeGamePacket` 是否偶发争用 Registry（需 JFR/线程态确认）。

---

## 12. 审计结论

1. **不要重写插件**；优先把「主线程全做」改为「主线程最小采集 + 有界异步」。  
2. **最大胜负手**：Dirty Tracking（玩家/实体）+ 去掉 Keyframe 主线程全 chunk 重编码 + 有界内存/异步 encode。  
3. Chunk 双层 fingerprint **方向正确**，但 **section 级缓存/差分** 与 **半径策略** 仍必须做。  
4. **未实现的 120Hz Motion** 是未来炸弹，当前阶段只登记，不提前堆复杂结构。  
5. 下一步应进入 **Phase 2：Main Thread optimization**，并先落地可重复 Spark 对比基线——**仍建议先确认本审计后再改代码**。

---

## 13. Phase 2 落地记录（2026-09-23）

已在**不重写插件**前提下完成 Main Thread 首轮优化（`compileJava` 通过）：

| 改动 | 对应审计项 | 说明 |
|------|------------|------|
| Keyframe 不再 `encodeChunkPayloads=true` | CRITICAL #5 / HIGH #5 | 每 Tick 固定 `false`；Keyframe 仅作内存 seek 锚点 |
| 玩家/实体增量 capture + 20 Tick heavy 刷新 | CRITICAL #2 | 运动/体征每 Tick；hotbar/equipment/`packAll` 按间隔或外观脏字段 |
| Diff 跳过 `minecraft:player` 实体行 | HIGH #4 附带 | 避免 PlayerUpsert + EntityUpdate 双写 |
| onTick 去掉 ReplayBuffer 双写 | HIGH #6 部分 | encode/seek 只保留 document + list |

**尚未做（留给后续 Phase）**：真正 async 管道、有界队列、section 级 block cache、stop 异步 encode、120Hz Motion。

**正确性注意**：装备/药水在非 heavy Tick 可能最多延迟 ~1s 才进入 Delta；运动/姿态/生命等 light 字段仍每 Tick。请用 Spark 同场景对比 MSPT p95/max 验证收益。

---

## 14. Phase 3 落地记录（2026-09-23）

对象分配 / GC 首轮（`compileJava` 通过）：

| 改动 | 说明 |
|------|------|
| `GlobalSnapshot.Builder` 所有权转移 | `unmodifiableMap` 替代每 Tick `Map.copyOf` 整表拷贝 |
| `PlayerState` hotbar | 复用 `EMPTY_HOTBAR` / 已不可变 List；避免每 Tick 重建 9 槽 |
| `Vec3d`/`Rotation`/`passengers` | 分量未变时复用 previous 引用 |
| `DeltaFrame` / Diff / onTick | 去掉双重 `List.copyOf`；空 Tick 零 merge；Diff 引用相等短路 |
| SideChannel / scratch sets | 空队列 `List.of()`；capture/session 复用 HashSet/ArrayList |
| `encodeGamePacket` | 主线程复用 `encodeScratch` ByteBuf |

**尚未做**：真正 async 管道、有界内存、section 级 block cache。

---

## 15. Phase 3.5 落地记录（2026-09-23）

针对 Spark [bdFftw90kZ](https://spark.lucko.me/bdFftw90kZ)（med 7.62 / p95 11.8 / max 151；`resolveChunk` ~10.9%）：

| 改动 | 说明 |
|------|------|
| Dirty skip | Bukkit 已发 `BlockChange` 的 chunk 本 Tick **完全跳过** fingerprint |
| Deep only | 干净 chunk 仅错峰 deep CRC；间隔 20→**40** Tick |
| Cheap 算法 | 去掉 `getSerializedSize`；改用 palette identity / bits / size |
| 默认 radius | **8→5**（~17²→~11² 跟踪圆） |

取舍：无事件的静默改方块检测延迟约 **2s**（原 ~1s）。事件路径方块仍即时入侧信道。

请用同场景 Spark 对比 `resolveChunk` / `cheapFingerprint` 占比与 MSPT max。

---

## 16. Phase 3.5b — Section 级静默差分（2026-09-23）

针对纯录制 Spark [AbzJcahRmT](https://spark.lucko.me/AbzJcahRmT)（max 88；`packBlockIds` ~0.9%）：

| 改动 | 说明 |
|------|------|
| `ChunkBlockCache` | 改为 per-section `int[4096]` + section fingerprint |
| `diffChunkBlocks` | 只 pack/差分 **指纹变化的 section**，不再整高度扫 |
| deep 路径 | 有 cache 时直接 section diff，避免「先全 chunk CRC 再全量 pack」 |

预期：FAWE/静默 paste 只改少量 section 时，尖峰从整 chunk pack 降为局部 pack。

---

## 17. Phase 3.5c — Fingerprint / 物品编码（2026-09-23）

| 改动 | 说明 |
|------|------|
| `sectionFingerprint` | 去掉 `section.write` + CRC；直接 mix 本地 palette 条目 + `BitStorage.getRaw()`（+ biomes）；GlobalPalette 不迭代 |
| `packSection` | bits==0 单值 `Arrays.fill`；否则线性 `states.get(i)` |
| `ItemStackCodec` | `hashItemAndComponents` 命中复用上一 `ReplayItemStack`；主线程 encode scratch |
| `ReplayItemStack` | 增加 `contentHash`（不参与 equals）；去掉热路径双 clone |

`compileJava`：26.2 + 1.21.11。待 Spark 对比 `sectionFingerprint` / `ItemStackCodec`。

---

## 18. Phase 4 — Async Flashback encode（2026-09-23）

SPEC §34：主线程只采集与冻结；编码 / 压缩 / IO 异步。

| 改动 | 说明 |
|------|------|
| `StateActionEncoder.openSession` | 主线程冻结 bootstrap + RegistryAccess |
| `FlashbackEncoder` | 消费 `Session`，不再持有 live `Player` |
| `NmsPlatform.prepareEncodeJob` | 每 stop 新 Encoder 实例 |
| `GlobalReplayRecorder` | `gfr-flashback-encode` 单线程 worker；完成后主线程私聊；`shutdown()` |

默认 `deferEncodeOnStop=true` 现为 **真正 off-main encode**（不再是下一主线程 tick）。

---

## 19. Phase 4b — Delta spill / 有界缓冲（2026-09-23）

| 改动 | 说明 |
|------|------|
| `AsyncDeltaPipeline` | 有界队列 256 + `gfr-delta-spill` worker；满则阻塞不丢帧 |
| `DeltaSpillFile` | 追加序列化非空 `DeltaFrame` |
| `RecordingOptions.spillDeltas` | 默认 true；verifySeek 时仍可内存双写 |
| `FlashbackEncoder` | 有 spill 时顺序流式读，避免整表 HashMap |

---

*End of Performance Audit.*
