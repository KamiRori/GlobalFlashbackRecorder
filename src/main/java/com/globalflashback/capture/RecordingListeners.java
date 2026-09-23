package com.globalflashback.capture;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import com.globalflashback.delta.StateChange;
import com.globalflashback.nms.ContainerBlockEntityCapture;
import com.globalflashback.nms.EffectOutboundTap;
import com.globalflashback.nms.EffectPacketEncoder;
import com.globalflashback.state.DimensionId;
import com.globalflashback.state.MetadataBlob;
import com.globalflashback.state.ReplayMath;
import io.papermc.paper.event.block.BlockBreakBlockEvent;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Main-thread Bukkit listeners that feed mid-tick changes into {@link RecordingSideChannel}.
 *
 * <p>Covers multi-block structures (bed / tall plant / door), fluids, pistons, and other physics
 * via Paper {@link BlockDestroyEvent}, {@link BlockPhysicsEvent}, and related form/spread events.
 *
 * <p>Player-opened block containers (chest / barrel / shulker / …): snapshot full inventory on open
 * and again when the player clicks/drags while that GUI is open. Hopper and other non-player moves
 * are intentionally ignored.
 */
public final class RecordingListeners implements Listener {
    /** Block inventory types we snapshot on player open / interact (not ender chest). */
    private static final Set<InventoryType> PLAYER_CONTAINER_TYPES = EnumSet.of(
            InventoryType.CHEST,
            InventoryType.BARREL,
            InventoryType.SHULKER_BOX,
            InventoryType.HOPPER,
            InventoryType.DROPPER,
            InventoryType.DISPENSER,
            InventoryType.FURNACE,
            InventoryType.BLAST_FURNACE,
            InventoryType.SMOKER,
            InventoryType.BREWING,
            InventoryType.CHISELED_BOOKSHELF,
            InventoryType.CRAFTER,
            InventoryType.DECORATED_POT
    );

    private final Plugin plugin;
    private final RecordingSideChannel sideChannel;
    private final ChunkDirtyTracker dirtyChunks;
    private final EffectPacketEncoder effects;
    private final EffectOutboundTap effectTap;
    private boolean registered;

    /** Per-tick dedupe: packed pos → last offered block-data string. */
    private int dedupeTick = -1;
    private final Map<Long, String> offeredThisTick = new HashMap<>();

    /** Per-tick dedupe for container inventory snapshots (packed block key). */
    private final Set<Long> containerOfferedThisTick = new HashSet<>();

    /**
     * Blocks whose container GUI is currently open by at least one player.
     * Key = {@link #packKey(Block)}; value = live block handle for re-snapshot on click.
     */
    private final Map<Long, Block> openContainerBlocks = new HashMap<>();
    /** player → packed keys of containers they currently have open. */
    private final Map<UUID, Set<Long>> openContainersByPlayer = new HashMap<>();

    public RecordingListeners(
            Plugin plugin,
            RecordingSideChannel sideChannel,
            ChunkDirtyTracker dirtyChunks,
            EffectPacketEncoder effects,
            EffectOutboundTap effectTap
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.sideChannel = Objects.requireNonNull(sideChannel, "sideChannel");
        this.dirtyChunks = Objects.requireNonNull(dirtyChunks, "dirtyChunks");
        this.effects = Objects.requireNonNull(effects, "effects");
        this.effectTap = Objects.requireNonNull(effectTap, "effectTap");
    }

    public void register() {
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            registered = true;
        }
    }

    public void unregister() {
        if (registered) {
            HandlerList.unregisterAll(this);
            registered = false;
            offeredThisTick.clear();
            containerOfferedThisTick.clear();
            openContainerBlocks.clear();
            openContainersByPlayer.clear();
        }
    }

    // ─── Animation ───────────────────────────────────────────────────────────
    // Arm swings are sampled from NMS LivingEntity.swinging via SwingSampler each tick.
    // PlayerAnimationEvent only covers client-sent swings and misses item-use / block-place.

    // ─── Player place / break ────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        scheduleBlockAndRelated(event.getBlock(), event.getBlock().getBlockData());
        // Solo / excluded-source: vanilla playSound may not hit any tapped pipeline. Claim payload
        // so multiplayer tap copies of the same place sound are not double-recorded.
        MetadataBlob sound = effects.encodeBlockPlaceSound(event.getBlock());
        effectTap.offerClaimedEffect(Bukkit.getCurrentTick(), sound);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMultiPlace(BlockMultiPlaceEvent event) {
        for (BlockState state : event.getReplacedBlockStates()) {
            scheduleCapture(state.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        BlockData before = block.getBlockData().clone();
        // LevelEvent 2001 (destroy particles) — client also plays break sound from this event.
        // Do not synthesize a separate SoundPacket (would stack with LevelEvent on replay).
        MetadataBlob particles = effects.encodeDestroyBlockParticles(block);
        effectTap.offerClaimedEffect(Bukkit.getCurrentTick(), particles);
        // Capture clicked cell + connected half (bed / tall grass / door) after removal.
        scheduleBlockAndRelated(block, before);
    }

    /**
     * Paper: fires for every block destroyed, including secondary halves of beds/doors/plants.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDestroy(BlockDestroyEvent event) {
        scheduleCapture(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakBlock(BlockBreakBlockEvent event) {
        scheduleCapture(event.getBlock());
        scheduleCapture(event.getSource());
    }

    // ─── Physics / fluids / pistons / form ───────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        // Catches neighbor / attachment collapses (second half of bed, sand fall support, etc.).
        scheduleCapture(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFromTo(BlockFromToEvent event) {
        scheduleCapture(event.getBlock());
        scheduleCapture(event.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFluidLevel(FluidLevelChangeEvent event) {
        scheduleCapture(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        schedulePistonAffected(event.getBlock(), event.getDirection(), event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        schedulePistonAffected(event.getBlock(), event.getDirection(), event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        // Lava+water → stone/obsidian/cobble, concrete powder, etc.
        scheduleCapture(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        scheduleCapture(event.getBlock());
        scheduleCapture(event.getSource());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        scheduleCapture(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        for (BlockState state : event.getBlocks()) {
            scheduleCapture(state.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMoisture(MoistureChangeEvent event) {
        scheduleCapture(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSponge(SpongeAbsorbEvent event) {
        scheduleCapture(event.getBlock());
        for (BlockState state : event.getBlocks()) {
            scheduleCapture(state.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        scheduleCapture(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        scheduleCapture(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeaves(LeavesDecayEvent event) {
        scheduleCapture(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        scheduleCapture(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            scheduleCapture(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            scheduleCapture(block);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void schedulePistonAffected(Block piston, BlockFace direction, List<Block> moved) {
        Set<Block> positions = new HashSet<>();
        positions.add(piston);
        positions.add(piston.getRelative(direction)); // piston head / extension cell
        for (Block block : moved) {
            positions.add(block);
            positions.add(block.getRelative(direction));
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Block block : positions) {
                offerBlockUpdate(block);
            }
        });
    }

    private void scheduleBlockAndRelated(Block block, BlockData dataBeforeOrCurrent) {
        List<Block> related = relatedBlocks(block, dataBeforeOrCurrent);
        Bukkit.getScheduler().runTask(plugin, () -> {
            offerBlockUpdate(block);
            for (Block other : related) {
                offerBlockUpdate(other);
            }
        });
    }

    private void scheduleCapture(Block block) {
        Bukkit.getScheduler().runTask(plugin, () -> offerBlockUpdate(block));
    }

    /**
     * Other half of bed / bisected plant / door, based on data before or at the event.
     */
    private static List<Block> relatedBlocks(Block block, BlockData data) {
        List<Block> related = new ArrayList<>(2);
        if (data instanceof Bed bed) {
            BlockFace facing = bed.getFacing();
            if (bed.getPart() == Bed.Part.HEAD) {
                related.add(block.getRelative(facing.getOppositeFace()));
            } else {
                related.add(block.getRelative(facing));
            }
        } else if (data instanceof Bisected bisected) {
            if (bisected.getHalf() == Bisected.Half.TOP) {
                related.add(block.getRelative(BlockFace.DOWN));
            } else {
                related.add(block.getRelative(BlockFace.UP));
            }
        }
        return related;
    }

    // ─── Player container open / edit (not hopper moves) ─────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!PLAYER_CONTAINER_TYPES.contains(event.getInventory().getType())) {
            return;
        }
        List<Block> blocks = containerBlocks(event.getInventory().getHolder());
        if (blocks.isEmpty()) {
            return;
        }
        Set<Long> keys = openContainersByPlayer.computeIfAbsent(player.getUniqueId(), u -> new HashSet<>());
        for (Block block : blocks) {
            long key = packKey(block);
            keys.add(key);
            openContainerBlocks.put(key, block);
            offerContainerInventory(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Set<Long> keys = openContainersByPlayer.remove(player.getUniqueId());
        if (keys == null || keys.isEmpty()) {
            return;
        }
        // Final snapshot so the last player edit before close is recorded.
        for (Long key : keys) {
            Block block = openContainerBlocks.get(key);
            if (block != null) {
                offerContainerInventory(block);
            }
        }
        // Drop tracking only when no other player still has this container open.
        for (Long key : keys) {
            boolean stillOpen = false;
            for (Set<Long> other : openContainersByPlayer.values()) {
                if (other.contains(key)) {
                    stillOpen = true;
                    break;
                }
            }
            if (!stillOpen) {
                openContainerBlocks.remove(key);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Set<Long> keys = openContainersByPlayer.get(player.getUniqueId());
        if (keys == null || keys.isEmpty()) {
            return;
        }
        // Any click while a tracked container GUI is open may move items in/out.
        for (Long key : keys) {
            Block block = openContainerBlocks.get(key);
            if (block != null) {
                offerContainerInventory(block);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Set<Long> keys = openContainersByPlayer.get(player.getUniqueId());
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (Long key : keys) {
            Block block = openContainerBlocks.get(key);
            if (block != null) {
                offerContainerInventory(block);
            }
        }
    }

    private static List<Block> containerBlocks(InventoryHolder holder) {
        if (holder instanceof DoubleChest doubleChest) {
            List<Block> blocks = new ArrayList<>(2);
            InventoryHolder left = doubleChest.getLeftSide();
            InventoryHolder right = doubleChest.getRightSide();
            if (left instanceof BlockState leftState) {
                blocks.add(leftState.getBlock());
            }
            if (right instanceof BlockState rightState) {
                blocks.add(rightState.getBlock());
            }
            return blocks;
        }
        if (holder instanceof BlockState state) {
            return List.of(state.getBlock());
        }
        return List.of();
    }

    private void offerContainerInventory(Block block) {
        if (block == null || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
            return;
        }
        int tick = Bukkit.getCurrentTick();
        if (tick != dedupeTick) {
            offeredThisTick.clear();
            containerOfferedThisTick.clear();
            dedupeTick = tick;
        }
        long key = packKey(block);
        if (!containerOfferedThisTick.add(key)) {
            return;
        }
        ContainerBlockEntityCapture capture = effects.encodeContainerInventory(block);
        if (capture.isEmpty()) {
            return;
        }
        DimensionId dimension = DimensionId.of(block.getWorld().getKey().toString());
        ReplayMath.BlockPos pos = new ReplayMath.BlockPos(block.getX(), block.getY(), block.getZ());
        sideChannel.offer(tick, new StateChange.BlockEntityChange(
                dimension, pos, capture.typeId(), capture.packetPayload()));
    }

    private void offerBlockUpdate(Block block) {
        if (block == null || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
            return;
        }
        MetadataBlob payload = effects.encodeBlockUpdate(block);
        if (payload.isEmpty()) {
            return;
        }
        String id = block.getBlockData().getAsString(false);
        int tick = Bukkit.getCurrentTick();
        if (tick != dedupeTick) {
            offeredThisTick.clear();
            dedupeTick = tick;
        }
        long key = packKey(block);
        String previous = offeredThisTick.put(key, id);
        if (id.equals(previous)) {
            return;
        }

        DimensionId dimension = DimensionId.of(block.getWorld().getKey().toString());
        ReplayMath.BlockPos pos = new ReplayMath.BlockPos(block.getX(), block.getY(), block.getZ());
        dirtyChunks.markBlock(block.getWorld(), block.getX(), block.getZ());
        sideChannel.offer(tick, new StateChange.BlockChange(dimension, pos, "", id, payload));
    }

    private static long packKey(Block block) {
        // world hash in high bits is unnecessary within a single server tick map; include world hashCode.
        long world = block.getWorld().getUID().getLeastSignificantBits();
        return (world * 31)
                ^ ((((long) block.getX() & 0x3FFFFFFL) << 38)
                | (((long) block.getZ() & 0x3FFFFFFL) << 12)
                | ((long) block.getY() & 0xFFFL));
    }
}
