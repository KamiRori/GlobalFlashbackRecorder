# 回放效果注入（ReplayEffectIngress）

第三方插件可在 **不向在线玩家发送** 的前提下，把 clientbound 包写入正在进行的 Global Flashback 录制（侧信道 → `EffectPacket` → Flashback `game_packet`）。

权威类：`com.globalflashback.api.ReplayEffectIngress`

常与 [`OutboundPacketCapture`](outbound-packet-capture.md) 搭配：

| 通道 | 用途 |
|------|------|
| `connection.send` + `OutboundPacketCapture` | 直播个性化 / 视距裁剪包；标记后旁路不采 |
| `ReplayEffectIngress.offer` | 回放用全球一致 / 稠密包；只进录像 |

---

## API

```java
import com.globalflashback.api.ReplayEffectIngress;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.server.level.ServerPlayer;

if (ReplayEffectIngress.isActive()) {
    ClientboundBundlePacket bundle = ...; // 回放专用几何
    ServerPlayer codecPlayer = ...;       // 仅用于协议/注册表编码
    ReplayEffectIngress.offer(bundle, codecPlayer);
}
```

| 方法 | 说明 |
|------|------|
| `isActive()` / `isBound()` | 是否有录制 sink |
| `offer(packet, codecPlayer)` | 编码任意 clientbound `Packet` / `BundlePacket` 并写入当前 tick |
| `offerClientboundPayload(tick, bytes)` | 已编码字节入口 |

录制开始/结束时由 GFR 内部 `bind` / `unbind`；未绑定时全部 no-op。

---

## 注意

1. `codecPlayer` 必须是 `ServerPlayer`，只用于 registry 编码，不会把包发给该玩家。  
2. 协议版本须与录制目标一致（本项目 Paper/MC 26.2）。  
3. 注入的效果与权威世界状态（方块/实体）无关；过大 Bundle 会增加 stop 编码体积。
