# Global Flashback Recorder

## Minecraft Paper 全局服务器录像系统策划案

---

# 一、项目概述

**项目名称：** Global Flashback Recorder

**项目类型：** Minecraft Paper 服务端全局 Replay / 录像插件

**目标版本：** Minecraft 26.2 / Paper 26.2

**开发语言：** Java

**核心技术：**

* Paper API
* Minecraft NMS
* Flashback Replay 格式 / Encoder

**明确不使用：**

* ProtocolLib
* PacketEvents

---

# 二、项目背景

Flashback 本身非常适合 Minecraft 的：

* Replay
* 自由视角
* Cinematic Camera
* Keyframe
* Slow Motion
* 镜头制作

但传统的 Flashback 录像主要以客户端为中心。

对于大型 Minecraft Event，例如：

> 100 人 Hunger Games

如果每个玩家分别录制：

```text
Alice.flashback
Bob.flashback
Charlie.flashback
...
Player100.flashback
```

那么后期制作时想要：

```text
Alice → Bob → Charlie → 自由镜头
```

就必须不断切换 Replay。

这不适合大型赛事视频制作。

因此本项目希望实现：

> **服务器端直接生成一个包含整个 Event 的 Global Flashback Replay。**

---

# 三、核心目标

## 3.1 一个 Event 对应一个 Replay

例如：

```text
event_2026_09_23.flashback
```

其中包含：

```text
所有参赛玩家
相关 Entity
相关 Chunk
世界变化
Block Changes
Player Changes
Entity Changes
Gameplay Events
```

打开 Replay 后，可以自由观察：

```text
Alice
Bob
Charlie
Player 37
Player 82
任意 Entity
自由相机
```

无需更换 Replay。

---

# 四、最重要的设计原则

## 4.1 Recorder 记录服务器，而不是客户端

整个项目采用：

> **Server Authoritative Recording**

服务器状态是唯一权威数据源。

例如：

```text
Server State

Alice:
GameMode = SURVIVAL

Position = 100,64,100

Health = 20
```

Recorder 记录的就是这个状态。

---

# 五、不记录客户端视角

本项目**不追求还原某一个玩家实际看到的画面**。

因此不需要记录：

```text
Client Camera
Client FOV
Mouse Movement
Client GUI
Fake Entity
Fake GameMode Packet
Fake Equipment Packet
Fake Block Packet
Fake Player
```

也不需要区分：

```text
Alice看到什么
Bob看到什么
Charlie看到什么
```

---

# 六、为什么不记录 Fake Packet

例如某插件执行：

```text
sendPacket(
    Alice,
    FakeEntitySpawnPacket
)
```

虽然：

```text
Alice 客户端看到了 Fake Entity
```

但是：

```text
服务器世界中并不存在这个 Entity
```

Global Recorder 不记录它。

原因是本项目的目标不是：

> 复制 Alice 的客户端画面。

而是：

> **记录服务器实际发生的游戏状态。**

因此：

```text
Server Entity
    ↓
记录

Fake Client Entity
    ↓
不记录
```

---

# 七、同样不记录 Fake GameMode

例如服务器：

```text
Alice GameMode = SURVIVAL
```

插件向客户端发送一个伪造的：

```text
GameMode = SPECTATOR
```

Recorder 仍然记录：

```text
SURVIVAL
```

因为：

> Server State 才是 Replay 的权威状态。

---

# 八、Outgoing Packet 不作为核心数据源

因此项目不再要求：

> 拦截 / 观察所有 Clientbound NMS Packet。

也不需要：

```text
Packet Observer
Packet Interpreter
Client State Tracker
```

整个架构可以明显简化。

---

# 九、核心数据源

Recorder 只关注：

```text
Paper / NMS Server State
```

以及：

```text
服务器侧事件
```

主要来源：

```text
Player
Entity
Chunk
World
Block
Block Entity
Game Event
```

---

# 十、录制范围

项目不采用固定区域。

例如不要求：

```text
World:
-5000,-5000
5000,5000
```

也不要求：

```text
Player 周围 100 格
```

而采用：

> **动态服务器状态录制。**

---

# 十一、动态 Chunk 范围

Recorder 只记录：

> **服务器当前已经加载 / 可访问的相关 Chunk。**

例如：

```text
比赛开始

Chunk A
Chunk B
Chunk C
```

Recorder 开始记录。

比赛过程中：

```text
Chunk D 被服务器加载
```

Recorder 自动加入：

```text
Chunk D
```

不需要管理员重新配置。

---

# 十二、不主动加载 Chunk

Recorder 不允许：

```text
为了录像
↓
强制加载 Chunk
```

也就是说：

```text
Server 正常加载
      ↓
Recorder 记录
```

而不是：

```text
Recorder
      ↓
强制加载大量 Chunk
      ↓
服务器性能下降
```

---

# 十三、录制时间模型

Replay 使用：

> **Minecraft Server Tick**

作为统一时间轴。

例如：

```text
Tick 0
Tick 1
Tick 2
...
Tick 20000
```

所有状态变化都绑定到 Tick。

这样可以支持：

* 精确跳转
* 暂停
* 慢动作
* 事件定位
* 多玩家同步
* Auto Director

---

# 十四、最终采用变化驱动录制

不采用：

> 每 Tick 保存完整世界状态。

而采用：

> **Initial Snapshot + State Delta + Periodic Keyframe**

例如：

```text
Tick 1000
Alice Position Changed

Tick 1001
无变化

Tick 1002
Alice Position Changed
Bob Rotation Changed

Tick 1003
Zombie Spawn
```

只记录发生变化的数据。

---

# 十五、Initial Snapshot

录像开始时创建：

```text
Initial Snapshot
```

包含当前 Replay 所需要的服务器状态。

例如：

```text
Players
Entities
Loaded Chunks
Relevant World State
```

这是整个 Replay 的起点。

---

# 十六、Player State

Player 是最重要的数据之一。

需要记录 Replay 所需的服务器状态，例如：

```text
UUID
Entity ID

Position
Rotation
Velocity

Dimension

Pose

OnGround
Sneaking
Sprinting
Swimming
Gliding
Sleeping

Vehicle
Passengers

GameMode

Health
Food
Experience

Selected Slot

Main Hand
Off Hand

Armor

Potion Effects

Metadata
```

具体字段根据 Flashback 格式以及 Minecraft 26.2 NMS 实际能力确定。

---

# 十七、Entity State

记录服务器中存在的实体：

```text
Player
Mob
Item
Projectile
TNT
ArmorStand
Display Entity
Vehicle
Interaction Entity
```

以及其他 Replay 所需 Entity。

记录：

```text
Entity ID
UUID
Entity Type

Spawn
Destroy

Position
Rotation
Velocity

Metadata
Equipment

Passengers
Vehicle

Dimension
```

---

# 十八、Entity 生命周期

每个 Entity 都存在：

```text
Spawn
    ↓
Updates
    ↓
Destroy
```

例如：

```text
Tick 1000
Zombie Spawn

Tick 1001
Position Changed

Tick 1002
Rotation Changed

Tick 1005
Metadata Changed

Tick 1010
Zombie Destroy
```

Replay 重建：

```text
Spawn
→ Apply Changes
→ Destroy
```

---

# 十九、Entity Change Detection

每个 Tick：

```text
Current Entity State
        ↓
Previous Entity State
        ↓
Compare
        ↓
Generate Delta
```

例如：

```text
Previous:
Position = 100,64,100
Yaw = 90

Current:
Position = 101,64,100
Yaw = 90
```

只生成：

```text
PositionChanged
```

而不是：

```text
PositionChanged
YawChanged
VelocityChanged
MetadataChanged
...
```

---

# 二十、Chunk 录制

Chunk 同样采用：

```text
Initial Chunk Snapshot
+
Chunk Changes
```

而不是每 Tick 保存整个 Chunk。

---

# 二十一、Chunk 生命周期

例如：

```text
Tick 0
Chunk A 已加载
```

记录：

```text
Chunk A Snapshot
```

之后：

```text
Block Change
Block Entity Change
```

只记录变化。

如果：

```text
Chunk A Unload
```

记录：

```text
Chunk Unload
```

如果之后：

```text
Chunk A Reload
```

则需要正确处理：

```text
Chunk A
重新进入 Replay 状态
```

具体实现根据 Flashback Replay 对 Chunk 生命周期的要求确定。

---

# 二十二、Block Changes

需要记录服务器世界中的方块变化：

```text
Block Break
Block Place
Block Update
Block State Change
```

例如：

```text
Tick 5000

(100,64,200)
STONE → AIR
```

只写：

```text
BlockChange
```

而不是重新保存整个 Chunk。

---

# 二十三、Block Entity

需要考虑：

```text
Chest
Barrel
Shulker Box
Sign
Spawner
其他 Block Entity
```

它们发生变化时：

```text
Block Entity Change
```

加入 Delta。

---

# 二十四、World / Dimension

支持服务器中的多个 Dimension：

```text
Overworld
Nether
End
其他自定义 Dimension
```

玩家或 Entity 发生：

```text
Dimension Change
```

时记录：

```text
DimensionChanged
```

---

# 二十五、Gameplay Event

除了底层 State Change，还需要记录重要的服务器事件。

例如：

```text
Damage
Death
Kill

Item Pickup
Item Drop

Block Break
Block Place

Projectile Launch
Projectile Hit

Explosion

Container Interaction

Player Join
Player Quit

Dimension Change
```

这些事件不一定是 Replay 恢复状态所必须的，但对于：

> **赛事剪辑和 Auto Director**

非常重要。

---

# 二十六、Event Timeline

所有重要事件记录：

```text
Tick
Event Type
Actor
Target
Position
Additional Data
```

例如：

```text
Tick 18231

PLAYER_KILL

Killer = Alice
Victim = Bob

Position = 125,68,230
```

---

# 二十七、Event Timeline 的用途

未来 Auto Director 可以：

```text
找到 Kill
    ↓
找到 Killer
    ↓
找到 Victim
    ↓
读取附近 Entity
    ↓
生成 Camera Shot
```

例如：

```text
Wide Shot
↓
Follow Killer
↓
Victim Close-up
↓
Kill Slow Motion
↓
Pull Away
```

---

# 二十八、Keyframe

虽然正常录像只记录变化，但仍然需要定期创建：

```text
Keyframe
```

例如：

```text
Tick 0
Keyframe

Tick 600
Keyframe

Tick 1200
Keyframe

Tick 1800
Keyframe
```

间隔最终通过实际测试确定。

---

# 二十九、为什么需要 Keyframe

假设用户直接跳到：

```text
Tick 15730
```

如果没有 Keyframe：

```text
Tick 0
 ↓
Apply Delta
 ↓
...
 ↓
Tick 15730
```

会非常慢。

有 Keyframe：

```text
Tick 15000 Keyframe
        ↓
Apply 15001
        ↓
...
        ↓
Tick 15730
```

可以快速恢复状态。

---

# 三十、Keyframe 内容

Keyframe 应包含能够恢复当前 Replay 状态的完整数据：

```text
Players
Entities
Relevant Chunks
World State
```

以及 Flashback 播放所需要的其他状态。

核心目标：

> **从 Keyframe 可以独立恢复该时间点的服务器 Replay 状态。**

---

# 三十一、Replay 数据结构

逻辑结构：

```text
Replay
│
├── Metadata
│
├── Initial Snapshot
│
├── Keyframes
│
├── Tick Deltas
│
├── Gameplay Events
│
└── Index
```

其中：

```text
Snapshot
```

负责建立状态。

```text
Delta
```

负责描述变化。

```text
Event
```

负责描述比赛发生的事件。

```text
Index
```

负责快速定位。

---

# 三十二、服务器状态与 Replay 状态

需要区分：

```text
Server Runtime State
```

和：

```text
Replay State
```

服务器当前：

```text
Alice = Position 100
```

Recorder 捕获后生成：

```text
PlayerPositionChange
```

Replay Writer 再将其转换成：

```text
Flashback-compatible data
```

因此 Recorder 不应该直接操作 Flashback 内部对象。

---

# 三十三、推荐的数据流水线

```text
Paper / NMS
     │
     ▼
Server State Capture
     │
     ▼
State Tracker
     │
     ▼
Change Detection
     │
     ├──────────────┐
     ▼              ▼
Delta Event     Gameplay Event
     │              │
     └───────┬──────┘
             ▼
        Replay Buffer
             │
             ▼
       Async Writer
             │
             ▼
      Flashback Encoder
             │
             ▼
      .flashback
```

---

# 三十四、线程模型

## Main Thread

负责：

```text
读取 Player
读取 Entity
读取 Chunk
读取 World
读取 NMS 状态
检测变化
生成 Replay Data
```

然后：

```text
Replay Data
     ↓
Queue
```

---

## Async Thread

负责：

```text
Delta Encoding
Keyframe Encoding
Compression
Flashback Encoding
Disk IO
```

不能在异步线程：

```text
访问 Bukkit World
访问 Entity
修改 Minecraft 状态
```

---

# 三十五、为什么不能每 Tick 写磁盘

错误方案：

```text
Tick
 ↓
读取状态
 ↓
立即写磁盘
```

会导致：

```text
大量 IO
主线程卡顿
TPS/MSPT 波动
```

正确：

```text
Main Thread
     ↓
Memory Buffer
     ↓
Async Writer
     ↓
Disk
```

---

# 三十六、性能优化

重点优化：

```text
只记录变化
Primitive Data
对象复用
批量写入
异步压缩
异步 IO
Keyframe 控制
```

避免：

```text
每 Tick 全量复制世界
每 Tick 序列化所有 Entity
强制加载 Chunk
```

---

# 三十七、录制范围与玩家无关

这是 Global Replay 和传统 Player Replay 的重要区别。

不是：

```text
Alice周围的世界
```

也不是：

```text
100个玩家各自周围的世界
```

而是：

> **服务器当前实际存在、加载、可被 Recorder 获取的世界状态。**

因此：

```text
Player A
Player B
Player C
```

只是服务器状态中的对象。

不是三个独立 Recorder。

---

# 三十八、100 人 Event 示例

比赛：

```text
100 Players
```

服务器：

```text
Overworld
Nether
大量动态 Chunk
```

Recorder：

```text
GlobalReplay
```

最终：

```text
event.flashback
```

其中：

```text
Player 1
Player 2
...
Player 100
```

全部属于同一个 Replay。

---

# 三十九、后期镜头

打开 Replay 后：

```text
Camera
 ├── Player 1
 ├── Player 2
 ├── Player 50
 ├── Player 100
 ├── Zombie
 └── Free Camera
```

不需要：

```text
切换 Replay
```

因此特别适合：

> 100 人 Hunger Games / MCC 风格 Event / 大型 Minecraft 活动。

---

# 四十、慢动作

由于所有变化都绑定：

```text
Server Tick
```

所以 Replay 播放器可以直接调整：

```text
Timeline Playback Speed
```

例如：

```text
1.0x
0.5x
0.25x
0.1x
```

录像本身无需针对慢动作特殊处理。

---

# 四十一、Auto Director

Global Recorder 本身不负责自动运镜。

它只负责提供：

```text
Replay Data
+
Event Timeline
```

之后由：

```text
Flashback Auto Director
```

负责：

```text
选择镜头
跟拍
切换目标
慢动作
特写
环绕
拉远
```

---

# 四十二、整体系统关系

最终整个系统分成四层：

```text
┌─────────────────────────────┐
│       Minecraft Server      │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│   Global Flashback Recorder │
│                             │
│   记录服务器实际状态        │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│      Global Flashback       │
│                             │
│   一个 Event = 一个 Replay  │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│       Auto Director         │
│                             │
│   事件 → 镜头 → Keyframe     │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│     Premiere / DaVinci      │
│                             │
│       最终视频制作           │
└─────────────────────────────┘
```

---

# 四十三、代码模块

建议：

```text
globalflashback/
│
├── GlobalFlashbackPlugin
│
├── recorder/
│   ├── GlobalReplayRecorder
│   ├── ReplaySession
│   ├── TickRecorder
│   ├── PlayerRecorder
│   ├── EntityRecorder
│   ├── ChunkRecorder
│   ├── WorldRecorder
│   └── EventRecorder
│
├── state/
│   ├── PlayerState
│   ├── EntityState
│   ├── ChunkState
│   └── WorldState
│
├── delta/
│   ├── StateDiff
│   ├── DeltaFrame
│   └── Keyframe
│
├── replay/
│   ├── ReplayBuffer
│   ├── ReplayWriter
│   ├── FlashbackEncoder
│   └── ReplayIndex
│
├── nms/
│   └── v26_2/
│
└── director/
    └── DirectorAPI
```

注意：

> 不再需要 `packet/`、`ClientStateTracker` 等模块作为核心系统的一部分。

---

# 四十四、NMS Adapter

由于 Minecraft NMS 经常变化，需要隔离版本相关代码。

例如：

```text
NmsAdapter
```

负责：

```text
读取 Entity
读取 Player
读取 Metadata
读取 Chunk
读取 World
读取服务器内部状态
```

26.2：

```text
NmsAdapter_26_2
```

未来可以：

```text
NmsAdapter_26_3
NmsAdapter_27_x
```

---

# 四十五、MVP

第一阶段不要直接做 100 人。

先实现：

```text
2 Players
1 World
少量 Entity
```

要求：

```text
一个 Replay
```

能够：

```text
Alice存在
Bob存在
Alice移动
Bob移动
Entity Spawn
Entity Destroy
Block Change
```

并成功：

```text
生成 .flashback
↓
Flashback 打开
↓
自由切换 Alice / Bob
```

---

# 四十六、第二阶段

加入：

```text
10 Players
动态 Chunk
Entity 生命周期
Block Changes
Equipment
Metadata
Dimension
```

验证：

```text
Replay 是否正确恢复
```

---

# 四十七、第三阶段

加入：

```text
Delta Recording
Keyframe
Random Seek
```

验证：

```text
2小时 Replay
```

能够：

```text
快速跳转到任意时间
```

---

# 四十八、第四阶段

进行压力测试：

```text
50 Players
100 Players
```

重点测试：

```text
TPS
MSPT
CPU
RAM
Disk IO
Replay Size
Writer Queue
Keyframe Size
```

---

# 四十九、第五阶段

加入：

```text
Gameplay Event Timeline
```

实现：

```text
Kill
Death
Damage
Projectile
Explosion
Objective
```

等赛事事件。

---

# 五十、第六阶段

建立：

```text
DirectorAPI
```

供未来 Auto Director 使用。

例如：

```text
getPlayerState(player, tick)

getEntityState(entity, tick)

getEvents(startTick, endTick)

findKills(startTick, endTick)

getNearbyEntities(position, radius, tick)
```

---

# 五十一、最重要的技术风险

## 风险 1：Flashback Global Replay 格式

首先需要验证 Flashback Replay 格式是否能够：

```text
多个 Player
多个 Entity
多个 Chunk
完整世界状态
```

存在于同一个 Replay 中。

如果可以：

> 直接复用 Flashback Encoder。

如果不能：

> 需要研究 Flashback Replay 内部结构并确定扩展方案。

---

# 五十二、风险 2：服务器状态如何捕获

不能简单认为：

```text
Bukkit Event
=
完整服务器状态
```

因为很多状态变化并没有一个方便的 Bukkit Event。

因此需要结合：

```text
Paper API
+
NMS State
+
必要的 Tick State Comparison
```

实现完整变化检测。

---

# 五十三、风险 3：Entity 移动量

Entity 位置是最频繁变化的数据之一。

100 人 Event 中：

```text
100 Players
+
大量 Mob
+
Projectile
+
Item
```

可能产生大量 Position Delta。

需要针对：

```text
Position
Rotation
Velocity
```

进行高效编码。

---

# 五十四、风险 4：Chunk 数据量

Chunk Initial Snapshot 可能非常大。

需要：

```text
只在首次需要时记录
压缩
异步处理
避免重复 Snapshot
```

---

# 五十五、最终架构原则

整个项目最终遵循以下原则：

### 1. Server Authoritative

```text
服务器状态 = Replay 真相
```

### 2. Global

```text
一个 Event = 一个 Global Replay
```

### 3. Dynamic

```text
不固定 Region
动态跟随服务器实际加载状态
```

### 4. Change-driven

```text
记录变化
而不是每 Tick 全量记录
```

### 5. Keyframe

```text
定期建立完整状态
保证快速随机跳转
```

### 6. No Client View

```text
不还原玩家客户端视角
```

### 7. No Fake Packet

```text
不记录仅发送给客户端的 Fake Entity / Fake GameMode 等状态
```

### 8. NMS First

```text
Paper API + NMS
```

### 9. Async Writing

```text
主线程采集
异步编码 / 压缩 / IO
```

### 10. Camera Independent

```text
Replay记录世界
Camera决定怎么看
```

---

# 五十六、最终目标

最终使用体验：

比赛开始：

```text
/globalreplay start
```

服务器正常运行。

Recorder 自动记录：

```text
Players
Entities
Chunks
Blocks
World Changes
Gameplay Events
```

并通过：

```text
Snapshot
+
Delta
+
Keyframe
```

生成：

```text
event_2026_09_23.flashback
```

比赛结束：

```text
/globalreplay stop
```

打开 Replay：

```text
             Alice
               │
               ├── Bob
               │
Camera ────────┼── Player 37
               │
               ├── Player 82
               │
               └── Free Camera
```

所有内容都来自：

> **同一个服务器全局 Replay。**

然后：

```text
Global Replay
      ↓
Gameplay Event Timeline
      ↓
Auto Director
      ↓
Camera Keyframes
      ↓
Flashback
      ↓
DaVinci / Premiere
```

最终形成完整的：

> **Minecraft Event 服务端全局录像 + 自动导演 + Cinematic 后期制作系统。**
