package com.globalflashback.event;

import com.globalflashback.state.DimensionId;
import com.globalflashback.state.ReplayMath;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.ProjectileSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Records server-authoritative gameplay facts for Auto Director (SPEC §25–§26).
 *
 * <p>Item gift/share is <b>not</b> recorded as a transfer: only {@link GameplayEventType#ITEM_DROP}
 * and {@link GameplayEventType#ITEM_PICKUP} with matching {@code itemEntityUuid} when available.
 */
public final class GameplayEventListeners implements Listener {
    private final Plugin plugin;
    private final AtomicReference<GameplayEventSink> sink = new AtomicReference<>();
    private final BooleanSupplier recordingActive;
    /** {@link Bukkit#getCurrentTick()} when the current recording started; ticks in events are relative. */
    private volatile int recordingStartTick;
    private boolean registered;

    public GameplayEventListeners(Plugin plugin, BooleanSupplier recordingActive) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.recordingActive = Objects.requireNonNull(recordingActive, "recordingActive");
    }

    /**
     * @param recordingStartTick world tick at {@code GlobalReplayRecorder.start}; event ticks are
     *     {@code Bukkit.getCurrentTick() - recordingStartTick} (same axis as Flashback / motion).
     */
    public void bind(GameplayEventSink eventSink, int recordingStartTick) {
        if (recordingStartTick < 0) {
            throw new IllegalArgumentException("recordingStartTick must be >= 0");
        }
        this.recordingStartTick = recordingStartTick;
        sink.set(Objects.requireNonNull(eventSink, "eventSink"));
    }

    public void unbind() {
        sink.set(null);
        recordingStartTick = 0;
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
        }
        unbind();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        emit(GameplayEventType.PLAYER_JOIN, p.getUniqueId(), null, p.getLocation(), Map.of(
                "name", safeName(p)
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        emit(GameplayEventType.PLAYER_QUIT, p.getUniqueId(), null, p.getLocation(), Map.of(
                "name", safeName(p)
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player p = event.getPlayer();
        Map<String, String> data = new LinkedHashMap<>();
        data.put("name", safeName(p));
        data.put("from", event.getFrom().getKey().toString());
        emit(GameplayEventType.DIMENSION_CHANGE, p.getUniqueId(), null, p.getLocation(), data);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = resolvePlayerAttacker(event.getDamager());
        if (attacker == null) {
            return;
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("damage", Double.toString(event.getFinalDamage()));
        data.put("cause", event.getCause().name());
        emit(GameplayEventType.DAMAGE, attacker.getUniqueId(), victim.getUniqueId(), victim.getLocation(), data);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Location loc = victim.getLocation();
        emit(GameplayEventType.DEATH, victim.getUniqueId(), null, loc, Map.of(
                "name", safeName(victim)
        ));
        Player killer = victim.getKiller();
        if (killer != null) {
            emit(GameplayEventType.PLAYER_KILL, killer.getUniqueId(), victim.getUniqueId(), loc, Map.of(
                    "killerName", safeName(killer),
                    "victimName", safeName(victim)
            ));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player p = event.getPlayer();
        Item item = event.getItemDrop();
        ItemStack stack = item.getItemStack();
        Map<String, String> data = itemData(stack);
        data.put("itemEntityUuid", item.getUniqueId().toString());
        UUID thrower = item.getThrower();
        if (thrower != null) {
            data.put("throwerUuid", thrower.toString());
        }
        emit(GameplayEventType.ITEM_DROP, p.getUniqueId(), null, item.getLocation(), data);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player p)) {
            return;
        }
        Item item = event.getItem();
        ItemStack stack = item.getItemStack();
        Map<String, String> data = itemData(stack);
        data.put("itemEntityUuid", item.getUniqueId().toString());
        data.put("remaining", Integer.toString(event.getRemaining()));
        UUID thrower = item.getThrower();
        if (thrower != null) {
            data.put("throwerUuid", thrower.toString());
        }
        // targetUuid = original thrower when known (still not an authoritative transfer)
        UUID target = thrower != null && !thrower.equals(p.getUniqueId()) ? thrower : null;
        emit(GameplayEventType.ITEM_PICKUP, p.getUniqueId(), target, item.getLocation(), data);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        Player shooter = resolvePlayerShooter(projectile.getShooter());
        if (shooter == null) {
            return;
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("projectileType", projectile.getType().name());
        data.put("projectileUuid", projectile.getUniqueId().toString());
        emit(GameplayEventType.PROJECTILE_LAUNCH, shooter.getUniqueId(), null, projectile.getLocation(), data);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        Player shooter = resolvePlayerShooter(projectile.getShooter());
        if (shooter == null) {
            return;
        }
        Entity hit = event.getHitEntity();
        if (!(hit instanceof Player victim)) {
            return;
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("projectileType", projectile.getType().name());
        data.put("projectileUuid", projectile.getUniqueId().toString());
        emit(GameplayEventType.PROJECTILE_HIT, shooter.getUniqueId(), victim.getUniqueId(), victim.getLocation(), data);
    }

    private void emit(
            GameplayEventType type,
            UUID actor,
            UUID target,
            Location location,
            Map<String, String> data
    ) {
        if (!recordingActive.getAsBoolean()) {
            return;
        }
        GameplayEventSink active = sink.get();
        if (active == null) {
            return;
        }
        DimensionId dimension = null;
        ReplayMath.Vec3d position = null;
        if (location != null && location.getWorld() != null) {
            dimension = DimensionId.of(location.getWorld().getKey().toString());
            position = new ReplayMath.Vec3d(location.getX(), location.getY(), location.getZ());
        }
        int relativeTick = Math.max(0, Bukkit.getCurrentTick() - recordingStartTick);
        active.accept(new GameplayEvent(
                relativeTick,
                type,
                actor,
                target,
                dimension,
                position,
                data
        ));
    }

    private static Map<String, String> itemData(ItemStack stack) {
        Map<String, String> data = new LinkedHashMap<>();
        if (stack == null) {
            data.put("item", "minecraft:air");
            data.put("amount", "0");
            return data;
        }
        data.put("item", stack.getType().getKey().toString());
        data.put("amount", Integer.toString(stack.getAmount()));
        return data;
    }

    private static Player resolvePlayerAttacker(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile projectile) {
            return resolvePlayerShooter(projectile.getShooter());
        }
        return null;
    }

    private static Player resolvePlayerShooter(ProjectileSource source) {
        if (source instanceof Player p) {
            return p;
        }
        return null;
    }

    private static String safeName(Player p) {
        String name = p.getName();
        return name == null ? "" : name;
    }
}
