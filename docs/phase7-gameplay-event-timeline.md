# Phase 7 — Gameplay Event Timeline

> **状态：完成（待用户确认）**  
> SPEC：§25–§26 Event Timeline；对接 Auto Director `gfr/events.json`

## 目标

录制期间把**服务器可确认的事实**写入 Replay，编码进 Flashback ZIP：

```text
gfr/events.json   (format=gfr_events, version=1)
```

## 权威边界（硬性）

| 可记录 | 不可记录为权威 |
|--------|----------------|
| `ITEM_DROP` / `ITEM_PICKUP`（含 `itemEntityUuid`） | **玩家把东西「给」另一个玩家**（无法服务端证明） |
| `DAMAGE` / `DEATH` / `PLAYER_KILL` | — |
| `PLAYER_JOIN` / `QUIT` / `DIMENSION_CHANGE` | — |
| `PROJECTILE_LAUNCH` / `PROJECTILE_HIT`（玩家射击→玩家） | — |

**禁止**在 Plugin 侧写出 `ITEM_TRANSFER`。分享/赠予仅由 Auto Director 根据 DROP→PICKUP **猜测**（`data.source=inferred_drop_pickup`）。

## 事件字段

```text
tick, type, actorUuid, targetUuid, dimension, x,y,z, data{}
```

`tick` = **录制相对 tick**（`Bukkit.getCurrentTick() - recordingStartTick`），与 Flashback `total_ticks` / motion 轴一致（0 = 开录）。

## 包

```text
com.globalflashback.event
  GameplayEvent / GameplayEventType
  GameplayEventListeners
  GameplayEventSink
  GameplayEventJson
  GfrEventsFormat          — ZIP_ENTRY = gfr/events.json
```

接线：`GlobalReplayRecorder` 开录时 `bind(document::addEvent)`；`FlashbackEncoder` 非空时写入 ZIP。

## 验收

1. 录一段含：丢物品→他人捡起、近战伤害、击杀  
2. 打开 ZIP，应有 `gfr/events.json`，且**无** `ITEM_TRANSFER`  
3. Auto Director `/gfrdirector analyze` → `events>0`；分享仅以推断 `ITEM_TRANSFER` 出现在分析结果（`source=inferred_drop_pickup`）
