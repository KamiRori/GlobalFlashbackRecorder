# 出站包捕获跳过（OutboundPacketCapture）

面向第三方插件的公开 API：在 **Global Flashback Recorder 正在录制** 时，把某些 **发往玩家的 clientbound 包** 从回放捕获中排除，同时 **不影响玩家实时收到这些包**。

权威类：`com.globalflashback.api.OutboundPacketCapture`

---

## 解决什么问题

GFR 会在玩家 Netty 管道上安装出站旁路（当前实现为 `EffectOutboundTap26_2`），把粒子 / 声音 / 爆炸，以及箱子盖等 `BlockEvent`、木牌等 `BlockEntityData`、挖掘裂纹 `BlockDestruction` 记入回放。

部分插件会 **按玩家定制** 或 **仅用于直播 HUD** 的包（例如个性化边界粒子、`BundlePacket` 批量特效）。这些包若被旁路采下，会导致：

- 多玩家在场时重复 / 并集过密  
- 回放出现不应存在的客户端装饰  

本 API 用 **包实例标记** 声明「此对象写入管道时不要捕获」。

---

## 适用范围

| 项目 | 说明 |
|------|------|
| 包类型 | **任意** 出站对象：单个 `Packet`、`BundlePacket`、实体包、自定义包等 |
| 识别方式 | **实例 identity**（`==` / identity set），不是类型或 payload 哈希 |
| 生效条件 | 仅当 `isCaptureActive() == true`（录制中且出站 tap 已安装） |
| 未录制时 | `suppress` / `sendSuppressed` 为空操作或直发，热路径几乎无额外成本 |
| 实时表现 | 玩家仍正常收到包；只跳过 GFR 捕获 |

旁路处理顺序：

1. 若整包 / 整 Bundle 被标记 → **直接转发，不做任何捕获**  
2. 否则若是 `BundlePacket` → 遍历子包；子包被标记则跳过该子包的捕获  
3. 否则按类型决定是否记入效果流（粒子、声音等）

因此「跳过」能力与「当前会不会记录该类型」解耦：**任何类型都可标记**；即使将来旁路扩展会记录更多类型，已标记实例仍会被跳过。

---

## API

```java
import com.globalflashback.api.OutboundPacketCapture;
import io.netty.channel.Channel;
import net.minecraft.server.level.ServerPlayer;

// 推荐：发送并自动清理标记（cleanup 用 channel.eventLoop）
ServerPlayer sp = ...;
Object packet = /* Packet 或 BundlePacket */;
Channel channel = sp.connection.connection.channel;

OutboundPacketCapture.sendSuppressed(
    packet,
    () -> sp.connection.send((net.minecraft.network.protocol.Packet<?>) packet),
    channel.eventLoop()
);
```

手动标记：

```java
if (OutboundPacketCapture.isCaptureActive()) {
    OutboundPacketCapture.suppress(packet);
    try {
        sp.connection.send(packet);
    } finally {
        // 建议在 eventLoop 上延迟 unsuppress，避免 write 尚未执行就清掉标记
        channel.eventLoop().execute(() ->
            channel.eventLoop().execute(() -> OutboundPacketCapture.unsuppress(packet)));
    }
} else {
    sp.connection.send(packet);
}
```

| 方法 | 含义 |
|------|------|
| `isCaptureActive()` | 出站 tap 是否在录制中 |
| `suppress(packet)` | 标记实例；未激活时 no-op |
| `unsuppress(packet)` | 取消标记 |
| `isSuppressed(packet)` | 查询 |
| `sendSuppressed(packet, send, cleanupExecutor)` | 标记 → 发送 → 在 executor 上双重调度清理 |

`setCaptureActive` 仅供 GFR 录制生命周期调用，插件勿用。

---

## 依赖与加载

- **编译**：`compileOnly` 依赖 GFR 产物（含 `OutboundPacketCapture`）  
- **运行**：服务器安装 `GlobalFlashbackRecorder`；Paper 建议 soft dependency + `join-classpath: true`  
- **未安装 GFR**：不要在总会加载的类里写死对 `OutboundPacketCapture` 的静态链接；用「先检测插件再进入独立 Filtered 类」等方式，避免 `NoClassDefFoundError`

插件名：`GlobalFlashbackRecorder`（与 `plugin.yml` 的 `name` 一致）。

---

## 注意

1. **必须标记真正写入管道的那个实例**（含 `new ClientboundBundlePacket(...)` 的 Bundle 本身）。  
2. 不要对「同一逻辑内容、不同 `new` 出来的对象」期望自动跳过——只认 identity。  
3. 本 API **不替代** 权威状态捕获（方块 / 实体 / 世界边境等）；只影响出站旁路捕获。  
4. 跳过 ≠ 从回放删除已写入的历史；只影响标记之后仍经过 tap 的写出。

---

## 版本

随 Global Flashback Recorder 0.1.x 引入；文档路径：`docs/outbound-packet-capture.md`。
