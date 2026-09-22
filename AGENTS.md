# AGENTS.md — Global Flashback Recorder

本文件约束所有在本仓库工作的 AI Agent。与 `SPEC.md` 冲突时，以 `SPEC.md` 为准；与本文件冲突时，先停下来向用户确认。

---

## 项目身份

- 名称：Global Flashback Recorder
- 类型：Minecraft **Paper 26.2** 服务端全局 Replay 基础设施插件
- 语言：Java
- 权威策划案：`SPEC.md`
- 优先级：**正确性 > 架构稳定性 > Flashback 格式兼容性 > 性能 > 代码数量**

---

## 硬性禁止

1. **禁止** ProtocolLib、PacketEvents，以及任何第三方网络包拦截框架。
2. **禁止** 把 Outgoing Packet / Client-visible State 设计成核心捕获数据源。
3. **禁止** 记录 Fake Entity、Fake GameMode、Fake Equipment、Fake Block 等仅客户端伪造状态。
4. **禁止** 还原玩家客户端视角（Camera / FOV / 鼠标 / GUI 不属于 Recorder）。
5. **禁止** 为录像主动加载 Chunk；禁止固定 Recording Region。
6. **禁止** 异步线程访问 Bukkit `World` / `Entity` 等非线程安全对象。
7. **禁止** 复制或衍生 `Moulberry/Flashback` 专有源码（许可：Do not redistribute）。允许 clean-room 理解格式并自行实现 Encoder。
8. **禁止** 根据 Minecraft 1.20 / 1.21 / 旧 Mojang Mapping 经验直接假设 26.2 API。
9. **禁止** 假设 Flashback Replay 格式；必须以实际源码或已核实调查笔记为准。
10. **禁止** 擅自跳过开发阶段，或在用户确认前进入下一阶段。

---

## 架构原则（必须遵守）

1. **Server Authoritative**：服务器实际状态是 Replay 唯一权威来源。
2. **Global**：一个 Event = 一个 Replay；不是每玩家一个 Replay。
3. **Dynamic**：只记录服务器当前已加载、存在且可获取的状态。
4. **Change-driven**：Initial Snapshot + Delta + Periodic Keyframe；禁止每 Tick 全量存世界。
5. **Tick timeline**：时间轴以 Minecraft Server Tick 为准。
6. **线程模型**：主线程读取 Paper/NMS 并生成不可变 Replay 数据；编码 / 压缩 / 磁盘 IO 尽量异步。
7. **Camera Independent**：Recorder 只记录世界；Camera / Auto Director 解耦，不在 Recorder 内做运镜。
8. **分层**：Capture（服务端状态）→ Tracker / Diff → 不可变 Delta/Event → Buffer → Async Writer → **FlashbackEncoder（出口为包流）**。

### 格式层澄清（阶段 0 结论）

- Flashback **文件格式**以 clientbound 包流 + `next_tick` 等 action 表达世界；Encoder **必须**产出兼容包流。
- 这不等于允许用 Netty / ProtocolLib 抓包作为核心 Capture。
- SPEC 的 Keyframe ≈ 录像分片 Snapshot + `forcePlaySnapshot`；**不是** Flashback 编辑器镜头 Keyframe。
- 每个 Replay **外层文件扩展名为 `.zip`**（Flashback 客户端只识别 `.zip`）；ZIP 内条目仍为 `metadata.json` + `cN.flashback`。
- **录制与播放的 Minecraft 协议版本必须一致**（本项目目标 Paper/MC 26.2）。用 26.2 录制的包在 26.1.x Flashback 上打开会因 `game_packet` 解码长度不匹配而崩溃。
- 每个 Replay 需要一个合成 `create_local_player`（相机 ego）；其它玩家以实体 + PlayerInfo 存在。这是格式要求，不是还原客户端视角。

---

## API / 源码调查规则

目标版本：**Paper 26.2 / Minecraft 26.2 NMS**。

若不确定某个类、字段、方法是否存在：

- **不要编造。**
- 先查：本仓库依赖、反编译 / paperweight 源码、官方 Paper javadoc（`jd.papermc.io/paper/26.2`）、或 `_investigate/` 中已核实材料。
- 对 Flashback：先查实际源码或 `FlashbackServer/docs/format/flashback-format.md` 等已核实文档。

凡出现「应该是……」「通常可以……」「Flashback 大概……」而未经核实：

- **不得**作为实现依据。
- 明确标记为 `UNKNOWN`，并说明需要调查什么。

若发现策划案与 Paper 26.2 / NMS / Flashback 格式在技术上冲突：

- **不要自行改设计或猜补丁。**
- 先明确指出问题，附源码 / API 证据，等待用户决定。

---

## 开发阶段流程

按阶段推进，**不要一次性生成整个项目**：

| 阶段 | 内容 |
|------|------|
| 0 | 技术调查 |
| 1 | 最小 Flashback POC |
| 2 | Global Replay 数据模型 |
| 3 | Server State Capture |
| 4 | Delta / Keyframe |
| 5 | Flashback Encoder |
| 6 | 压力测试 |
| 7 | Gameplay Event Timeline |
| 8 | Director API |

每个阶段开始前：说明目标、待验证问题、检查现有代码；涉及 NMS 必须以实际 API 为准。

每个阶段完成后：总结已完成内容、修改文件、测试方法、已知问题；**停止并等待用户确认**后再进入下一阶段。

---

## 实现习惯

- 先读 `SPEC.md` 与本文件，再改代码。
- 最小改动；不做无关重构、不擅自加文档/依赖。
- 模块建议见 `SPEC.md` §43；**不要**把 `packet/`、`ClientStateTracker` 做成核心系统。
- NMS 版本相关代码隔离在 `nms/v26_2/`（或等价 adapter）中。
- 调查用克隆仓库放在 `_investigate/`；不要把专有 Flashback 源码提交进主工程发布物。
- 回复用户使用**简体中文**（除非用户要求其它语言）。

---

## 额外规则（摘要）

这个项目涉及 Paper 26.2 和 Minecraft 26.2 NMS。

禁止根据 Minecraft 1.20、1.21、1.21.5、旧版 Mojang Mapping 或其他版本经验直接假设 26.2 的 API。

如果你不知道某个类、字段、方法是否存在：不要编造。先搜索当前项目依赖中的实际源码、反编译结果或官方 Paper API/NMS 定义。

同样，对于 Flashback：不要假设 Flashback 的 Replay 格式。必须先检查实际源码。

任何类似「应该是……」「通常可以……」「Flashback 大概……」都不能直接作为实现依据。

如果无法确认，明确标记为 `UNKNOWN`，并告诉用户需要调查什么。
