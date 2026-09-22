# Global Flashback Recorder

Paper **26.2** 服务端全局 Replay 插件：在服务器上录制一场 Event 的世界状态，导出为可被 [Flashback](https://github.com/Moulberry/Flashback) 打开的 `.zip` 回放。

一个 Event → 一个 Replay。打开后可自由切换观察任意玩家 / 实体 / 镜头，无需每人各录一份客户端录像。

权威策划与约束见 [`SPEC.md`](SPEC.md)、[`AGENTS.md`](AGENTS.md)。

---

## 核心原则

| 原则 | 含义 |
|------|------|
| **Server Authoritative** | 以服务器实际状态为准，不以客户端视角 / 伪造状态为准 |
| **Global** | 全局一份录像，不是每玩家一份 |
| **Dynamic** | 只记录当前已加载、可获取的状态；不为录像强行加载 Chunk |
| **Change-driven** | Initial Snapshot + Delta（+ 内存 Keyframe）；不做每 Tick 全量存世界 |
| **Tick timeline** | 时间轴以 Minecraft Server Tick 为准 |
| **Camera Independent** | Recorder 只记世界；运镜 / Director 不在本模块内 |

**明确不使用：** ProtocolLib、PacketEvents，以及把「出站包拦截」当作核心世界状态来源。

---

## 功能概览（当前实现）

### 会记入回放

- 玩家位置 / 姿态 / 生命等实体表现；**快捷栏**（相机 ego HUD）与**装备位**（第三人称可见）
- 非玩家实体的生成、移动、销毁、装备等
- 已加载 Chunk 的初始区块包；方块放置 / 破坏 / 活塞 / 流体等导致的 **BlockState** 变化
- 世界时间、天气、**WorldBorder**（含平滑 lerp）
- 粒子 / 声音 / 爆炸等短暂效果（出站旁路，按 tick 去重）
- 箱子 / 潜影盒等 **盖动画**（`BlockEvent`）、挖掘裂纹、木牌等客户端同步 BE
- **玩家打开方块容器**时的物品快照，以及开着 GUI 时玩家对容器的操作（不记漏斗 / 红石搬动）

### 不会记 / 刻意不做

- 其他玩家的**主背包**（Flashback 回放也无法查看；客户端本来就没有这份数据）
- 无人开箱时，漏斗等对容器内容的持续同步
- Fake Entity / Fake GameMode / 仅客户端伪造状态
- 玩家客户端视角（Camera FOV、鼠标、原版 GUI 操作流不属于 Recorder）
- 主动加载 Chunk、固定 Recording Region

### 第三方插件 API

录制期间可跳过某些出站包的捕获（玩家仍正常收到）：

- [`OutboundPacketCapture`](src/main/java/com/globalflashback/api/OutboundPacketCapture.java) — 说明见 [`docs/outbound-packet-capture.md`](docs/outbound-packet-capture.md)
- [`ReplayEffectIngress`](src/main/java/com/globalflashback/api/ReplayEffectIngress.java) — 只写入回放、不发给玩家：[`docs/replay-effect-ingress.md`](docs/replay-effect-ingress.md)

---

## 环境要求

| 项目 | 要求 |
|------|------|
| 服务端 | **Paper 26.2**（与录制协议版本一致） |
| JDK | **25**（构建与运行） |
| 播放端 | 同版本 Minecraft + Flashback；**26.2 录制的包不能用旧版 Flashback / 旧协议打开** |

产物为 Flashback 识别的 **`.zip`**（内含 `metadata.json` + `cN.flashback`）。

---

## 构建

```bash
./gradlew build
```

插件 jar 输出在 `build/libs/`（版本见 `build.gradle.kts`）。

本地试跑（需同意 EULA 等）：

```bash
./gradlew runServer
```

---

## 安装与命令

1. 将 jar 放入 Paper `plugins/`，启动服务器  
2. 权限：`globalflashback.admin`（默认 op）

| 命令 | 说明 |
|------|------|
| `/gfr record start [name] [keyframeIntervalTicks] [chunkRadius]` | 开始录制 |
| `/gfr record status` | 当前状态 |
| `/gfr record stop` | 停止并编码（**须由游戏内玩家执行**，该玩家作为相机 ego） |
| `/gfr poc` / `/gfr capture` | 开发 / 调试用子命令 |

默认：`keyframeIntervalTicks = 100`，`chunkRadius = 8`（仅跟踪玩家附近**已加载** Chunk）。

停止后会在插件数据目录写出 Flashback `.zip`，控制台 / 消息中会打印路径。

---

## 架构简述

```text
Capture（主线程读 Paper/NMS）
  → Diff / SideChannel（方块事件、开箱、效果旁路等）
  → 不可变 Delta / Keyframe（内存）
  → stop 时 FlashbackEncoder → .zip 包流
```

NMS 版本相关代码隔离在 `nms/v26_2/`。更细的性能结论见 [`docs/performance-audit.md`](docs/performance-audit.md)。

---

## 开发阶段

按 `AGENTS.md` 分阶段推进（调查 → POC → 数据模型 → Capture → Delta → Encoder → 压测 → Gameplay Event → Director API）。  
当前仓库为可录制并导出 Flashback 的工作中实现；Gameplay Event Timeline、Director 等后续阶段以 `SPEC.md` 为准，尚未作为成品功能承诺。

---

## 许可与合规

- 本仓库实现为 clean-room Encoder / 服务端捕获，**不得**复制或衍生 Moulberry/Flashback 专有源码（其许可禁止再分发）。
- 调查用材料请放在 `_investigate/`，不要打进发布物。

---

## 文档索引

| 文档 | 内容 |
|------|------|
| [`SPEC.md`](SPEC.md) | 完整策划案 |
| [`AGENTS.md`](AGENTS.md) | AI / 贡献者硬性约束 |
| [`docs/outbound-packet-capture.md`](docs/outbound-packet-capture.md) | 出站捕获跳过 API |
| [`docs/replay-effect-ingress.md`](docs/replay-effect-ingress.md) | 仅回放写入的效果入口 |
| [`docs/performance-audit.md`](docs/performance-audit.md) | 性能审计笔记 |
