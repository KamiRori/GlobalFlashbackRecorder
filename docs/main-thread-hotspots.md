# 主线程热点追踪（多人场景）

**目的**：记录录制期 **Server Thread** 上仍可能放大 MSPT 的热点，供后续分批修复。  
**背景**：小人数 Spark 不能外推到 50/100 人；下列优先级按「随玩家数 / 实体数 / 跟踪 chunk 并集放大」排序。  
**关联**：历史审计与已落地优化见 [`performance-audit.md`](performance-audit.md)。

**状态约定**

| 标记 | 含义 |
|------|------|
| `OPEN` | 未动手 |
| `IN_PROGRESS` | 进行中 |
| `DONE` | 已合入；注明 commit / 验证方式 |
| `DEFER` | 有意推迟（正确性或阶段原因） |
| `UNKNOWN` | 需 Spark / 实测才能定是否动手 |

每修完一项：改状态、补「验证」行（Spark 链接或对比数字），不要删历史条目。

---

## 0. 基线（待补）

| 场景 | 人数 | Spark | med / p95 / max MSPT | 备注 |
|------|------|-------|----------------------|------|
| （待测）闲置录制 | | | | |
| （待测）中等活动 | | | | |
| （待测）团战 / 高实体 | | | | |

未填基线前，**不要用绝对值**宣称「已优化到 X ms」。

---

## 1. 热点清单

### H1 — 每 Tick 全玩家 light capture

| | |
|--|--|
| **状态** | `DONE`（首轮，2026-09-23） |
| **现象** | `ServerStateCapture` 对每个在线玩家做增量 `capturePlayer`（运动/体征等） |
| **缩放** | O(在线玩家) |
| **风险** | 50–100 人时即使「没动」也有固定主线程成本 |
| **已做** | ① light 字段未变时复用 immutable `previous`（跳过 hotbar/equipment/`packAll`）；② **heavy 按 `entityId` 错峰**，避免全体玩家同一 tick `packAll` |
| **残留** | 每 tick 仍需读 NMS 位姿/体征做脏判断；进一步「零读」需事件/脏位（可另开项） |
| **验证** | `compileJava` 通过；**待**同场景 Spark：`capturePlayer` 占比与 p95（尤其对比 heavy 尖峰是否摊平） |

---

### H2 — 半径内非玩家实体 capture

| | |
|--|--|
| **状态** | `DONE`（首轮，2026-09-23） |
| **现象** | 曾对玩家所在世界执行 `World#getEntities()` 全图扫描 |
| **缩放** | 旧：O(世界实体)；新：O(跟踪半径内已加载 chunk 上的实体) |
| **风险** | 旧路径在刷怪/掉落物多的维度会炸 MSPT |
| **已做** | ① 仅从**已跟踪且已加载** chunk 收集实体（`getChunkAt(x,z,false)`，不强加载）；② 玩家半径并集去重；③ heavy 按 `entityId` 错峰；④ 离开半径的实体由 Diff 发 `EntityDestroy` |
| **残留** | 静止实体降频、掉落物合并等（需 SPEC 确认）仍可做 |
| **验证** | `compileJava` 通过；**待**高实体 / 大世界 Spark：确认不再出现整图 `getEntities` 热点 |

---

### H3 — 玩家 metadata `packAll`（heavy 路径）

| | |
|--|--|
| **状态** | `DONE`（首轮，2026-09-23） |
| **现象** | 旧：任一 heavy 触发（含换快捷栏/掉血）都 `packAll` + 全量 hotbar/equipment/药水/profile |
| **缩放** | O(玩家) × 编码成本；错峰后尖峰已摊，但单次仍贵 |
| **风险** | 物品组件 / 元数据编码在人数上去后仍贵 |
| **已做** | ① 拆分 **appearance / slot / vitals / mount**：仅相关 facet 重建；掉血不再 `packAll`；② 外观变化（含恢复默认潜行等）仍 `packAll`（否则 `getNonDefaultValues` 漏默认 flags）；③ 周期 heavy 且外观未变 → `getNonDefaultValues`；④ **永不 `packDirty`** |
| **残留** | 外观变化当 tick 仍 `packAll`；皮肤 overlay 等极端边角靠周期 non-default / 下次外观变化 |
| **验证** | `compileJava` 通过；**待** Spark：对比 heavy vs 仅 vitals tick；回放确认潜行/疾跑开关正确 |

---

### H4 — Chunk 跟踪并集（radius）

| | |
|--|--|
| **状态** | `DONE`（首轮，2026-09-23） |
| **现象** | 默认 `chunkRadius = 8`；多玩家散开时已加载 chunk **并集**变大，cheap/deep / 静默差分摊销变贵 |
| **缩放** | O(∪ 各玩家半径内已加载 chunk) |
| **风险** | 「人不多但很散」也会贵；与人数非严格线性 |
| **已做** | ① **圆形半径**（`dx²+dz²≤r²`，r=8 约少 20% 角上 chunk）；② **未加载早退**（不强加载）；③ 每 tick **deep 检查预算 48**（超额推迟到下一 stagger，压 MSPT 尖峰） |
| **残留** | 活动区/维度裁剪；并集仍随散开变大；预算耗尽时静默改方块检测更晚 |
| **已有缓解** | dirty skip、cheap palette gate、section 差分、deep 40 / verify 160 |
| **验证** | `compileJava` 通过；**待**分散站位 vs 聚团 Spark：`resolveChunk` / `cheapFingerprint` |

---

### H5 — EffectOutboundTap（每玩家管道）

| | |
|--|--|
| **状态** | `DONE`（首轮，2026-09-23） |
| **现象** | 每个在线玩家 Netty 管道安装旁路；旧实现先 encode 再 content-hash 去重 → 广播时 N−1 次白编码 |
| **缩放** | O(玩家管道写次数)；广播类效果人多时最痛 |
| **风险** | 烟花/大量粒子时 Netty CPU + SideChannel 膨胀 |
| **已做** | ① **identity 抢占**（同包实例多管道只编码一次）；② **structural hash 抢占**（粒子/声音/爆炸/方块事件等，编码前去重）；③ 粒子 **每 tick 上限 128**（仅丢回放记录，不影响实时）；④ 编码后 payload hash 兜底 |
| **残留** | encode 仍在 eventLoop（Registry 争用 `UNKNOWN`）；非粒子洪峰未单独限流；可将 encode 挪到主线程队列（架构更大） |
| **验证** | `compileJava` 通过；**待**粒子压力 Spark + Netty 线程 CPU |

---

### H6 — SwingSampler 全员扫描

| | |
|--|--|
| **状态** | `DONE`（首轮，2026-09-23） |
| **现象** | 每 Tick 扫全部在线玩家 NMS `swinging`，并对每人写 HashMap |
| **缩放** | O(玩家) 固定成本 |
| **风险** | 相对 H1/H2 较小，但仍是每人每 tick 的 map 写入 |
| **已做** | ① **idle 快路径**：`!swinging && 未在挥臂跟踪` → 零 map 写、零 encode；② 挥臂结束 remove 条目；③ 每 100 tick **prune** 离线 entityId |
| **残留** | 仍遍历 `getOnlinePlayers()` 读 `swinging` 字段（无法在无事件时省略）；事件化需 NMS mixin/注入（本项目不做） |
| **验证** | `compileJava` 通过；**待** Spark：`SwingSampler.sample` 占比 |

---

### H7 — Diff + 内存中保留全部 Delta / Keyframe

| | |
|--|--|
| **状态** | `DONE`（首轮，2026-09-23） |
| **现象** | 旧：`document` + `deltaList` + `keyframeList` 三份；Keyframe 每 100 tick 整份 `GlobalSnapshot` |
| **缩放** | 堆随时长与 keyframe 次数涨 |
| **风险** | 长录制堆膨胀；停录 seek 再扫一遍 |
| **已做** | ① 去掉并行 `deltaList`/`keyframeList`（只留 `ReplayDocument.Builder`）；② **默认不保留** 全量 Keyframe 快照（FlashbackEncoder 本就不读它们）；③ `verifySeekOnStop=true` 时才写入 Keyframe；④ 去掉热路径 `ReplayBuffer` 双写 |
| **残留** | Delta 列表仍全程在内存直至 encode；流式落盘 / 脏键 Diff 未做 |
| **验证** | `compileJava` 通过；**待**长录制 Heap 对比 |

---

### H8 — stop 时主线程同步 encode + seek verify

| | |
|--|--|
| **状态** | `DONE`（首轮，2026-09-23） |
| **现象** | 旧：`/gfr record stop` 同步 seek + encode，长录像卡服 |
| **缩放** | O(录制时长 × 变化量) |
| **风险** | 停录卡服 |
| **已做** | ① **默认跳过 seek verify**（`verifySeekOnStop=false`）；② **默认 defer encode**：stop 只封文档，下一 main tick 再 `FlashbackEncoder.encode` 并私聊路径；③ 每次 stop 使用新 Encoder 实例 |
| **残留** | ~~encode 仍在主线程~~ → 见 **H10** |
| **验证** | `compileJava` 通过；**待**对比 stop 命令返回延迟与后续 tick MSPT |

---

## 2. 建议修复顺序

在补齐 §0 基线之前，默认按下列顺序（可随 Spark 结果调整）：

1. ~~**H1 + H2** — 玩家/实体脏跟踪与扫描范围~~ → **首轮已做**（错峰 heavy + chunk 范围实体）  
2. ~~**H5** — 效果旁路限流 / 去重位置~~ → **首轮已做**（encode 前抢占 + 粒子 cap）  
3. ~~**H3** — 收紧 `packAll`~~ → **首轮已做**（facet 重建 + 条件 packAll）  
4. ~~**H4** — 跟踪并集策略~~ → **首轮已做**（圆形半径 + deep 预算）  
5. ~~**H7 + H8** — 有界内存 + 延后 stop encode~~ → **首轮已做**（去双列表/默认无 seek keyframe；defer encode）  
6. ~~**H6** — SwingSampler~~ → **首轮已做**（idle 快路径 + prune）  

**首轮热点清单 H1–H8 均已落地。** 下一步以多人 Spark 填 §0 基线，按火焰图决定第二轮。

### H9 — Chunk deep fingerprint / pack + 物品 StreamCodec（第二轮）

| | |
|--|--|
| **状态** | `DONE`（首轮，2026-09-23） |
| **现象** | deep `sectionFingerprint` 走 `section.write` + CRC；heavy tick 对未变装备/热键栏仍 `OPTIONAL_STREAM_CODEC` |
| **缩放** | O(deep 预算 × sections)；O(heavy 玩家 × 15 槽) |
| **已做** | ① fingerprint 改为 palette 条目 + `BitStorage.getRaw()` 直接 mix（含 biomes；跳过 GlobalPalette 全表迭代）；② `packSection` 单值 section `Arrays.fill` + 线性 `states.get(i)`；③ `ItemStack.hashItemAndComponents` 命中则复用上一 tick `ReplayItemStack`；④ 编码缓冲主线程复用 |
| **残留** | deep 路径 `getUpdateTag` 仍贵；hash 碰撞极罕见会跳过一次重编码（可接受） |
| **验证** | `compileJava` 26.2 + 1.21.11；**待** Spark：`sectionFingerprint` / `ItemStackCodec` 占比 |

---

### H10 — stop 异步 Flashback encode（SPEC §34）

| | |
|--|--|
| **状态** | `DONE`（首轮，2026-09-23） |
| **现象** | 即使 defer 到下一 tick，`FlashbackEncoder.encode` 仍占主线程（包 StreamCodec + ZIP） |
| **缩放** | O(录制时长 × Delta) |
| **已做** | ① 主线程 `prepareEncodeJob`：冻结 bootstrap（login/create_local_player）+ `RegistryAccess`；② `StateActionEncoder.Session` 无 live Player；③ 单线程 `gfr-flashback-encode` worker 跑 encode+ZIP；④ 完成后主线程私聊路径；⑤ 每 job 新 Encoder 实例；⑥ plugin disable `shutdown` |
| **残留** | 录制期 Diff 仍主线程；~~流式边录边写盘未做~~ → 见 **H11**；worker 与主线程共享 `RegistryAccess` 只读假设 |
| **验证** | `compileJava` 26.2 + 1.21.11；**待**长录像 stop：命令返回延迟 vs worker 写盘时间 |

---

### H11 — 录制期 Delta 有界队列 + 异步 spill（SPEC §34 Buffer→Async Writer）

| | |
|--|--|
| **状态** | `DONE`（首轮，2026-09-23） |
| **现象** | 非空 Delta 全程堆在 `ReplayDocument.Builder`，长录制堆膨胀 |
| **缩放** | O(录制时长 × 变化量) 堆常驻 |
| **已做** | ① `AsyncDeltaPipeline`：有界队列(256) + `gfr-delta-spill` worker；② `DeltaSpillFile` 追加序列化；③ 默认 `spillDeltas=true` 时主线程不保留 Delta 列表；④ encode 顺序流式读 spill；⑤ encode 完成后删除 spill；⑥ `verifySeek` 仍可读 spill / 或内存双写 |
| **残留** | 队列满时主线程短暂阻塞（不丢帧）；ObjectOutputStream 非最紧凑；空 tick 不落盘（靠 totalTicks） |
| **验证** | `compileJava` 26.2 + 1.21.11；**待**长录制 Heap 对比 |

---

每步：**最小改动 → `compileJava` → 同场景 Spark 对比 → 更新本表状态**。

---

## 3. 明确不做 / 低优先级（本阶段）

| 项 | 原因 |
|----|------|
| 120Hz Motion 复杂结构 | SPEC/审计登记为未来项，勿提前堆 |
| 无人开箱时全图容器同步 | 已排除；仅玩家开箱路径 |
| 其他玩家主背包 | Flashback 不可见；不记录 |
| 重写整插件 / 改用 ProtocolLib | 违反 `AGENTS.md` |

---

## 4. 修复日志

| 日期 | 热点 | 变更摘要 | 验证 |
|------|------|----------|------|
| 2026-09-23 | H1 | heavy 按 entityId 错峰；确认 light 未变复用 previous | compileJava |
| 2026-09-23 | H2 | 废除 `world.getEntities()`；仅扫描跟踪半径内已加载 chunk 实体 + heavy 错峰 | compileJava |
| 2026-09-23 | H5 | EffectOutboundTap：identity/structural 编码前去重；粒子每 tick cap 128 | compileJava |
| 2026-09-23 | H3 | 玩家 blob 按 facet 重建；外观变化才 packAll，否则 non-default；禁 packDirty | compileJava |
| 2026-09-23 | H4 | 圆形 chunk 半径；未加载早退；每 tick deep fingerprint 预算 48 | compileJava |
| 2026-09-23 | H7 | 去 deltaList/keyframeList；默认不存全量 Keyframe 快照；去掉 ReplayBuffer 热路径 | compileJava |
| 2026-09-23 | H8 | 默认跳过 seek；stop 延后到下一 tick encode；完成后私聊路径 | compileJava |
| 2026-09-23 | H6 | SwingSampler idle 快路径（无 map 写）；离线 id 定期 prune | compileJava |
| 2026-09-23 | H9 | sectionFingerprint 去 write/CRC；pack 单值快路径；物品 hash 复用 + encode scratch | compileJava 26.2+1.21.11 |
| 2026-09-23 | H10 | stop：主线程 prepareEncodeJob；异步 worker encode+ZIP；主线程回告 | compileJava 26.2+1.21.11 |
| 2026-09-23 | H11 | Delta 有界队列+异步 spill；encode 流式读盘；默认不堆全量 deltas | compileJava 26.2+1.21.11 |

---

## 5. 相关代码入口

| 热点 | 主要入口 |
|------|----------|
| H1–H4 | `capture/ServerStateCapture.java`、`nms/v26_2/NmsAdapter26_2.java` |
| H3 | `NmsAdapter26_2.captureEntityMetadata` |
| H5 | `nms/v26_2/EffectOutboundTap26_2.java` |
| H6 | `capture/SwingSampler.java` |
| H7–H8 | `recorder/GlobalReplayRecorder.java`、`replay/FlashbackEncoder.java` |
| H9 | `NmsAdapter*` fingerprint/pack；`ItemStackCodec*`；`ReplayItemStack` |
| H10 | `NmsPlatform.prepareEncodeJob`；`StateActionEncoder.Session`；`GlobalReplayRecorder` encodeExecutor |
| H11 | `AsyncDeltaPipeline`；`DeltaSpillFile`；`FlashbackEncoder` spill 路径 |
