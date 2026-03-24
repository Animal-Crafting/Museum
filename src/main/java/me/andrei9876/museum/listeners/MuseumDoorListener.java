package me.andrei9876.museum.listeners;

import me.andrei9876.museum.Museum;
import me.andrei9876.museum.managers.MuseumsManager;
import me.andrei9876.museum.models.MuseumRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class MuseumDoorListener implements Listener {

    private final Museum plugin;

    // cooldowns[playerUUID][doorKey] = lastTriggerMillis
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    public MuseumDoorListener(Museum plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        Location from = e.getFrom();
        if (to == null) return;

        // cheap filter: only act when changing block (prevents excessive checks)
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        Player p = e.getPlayer();

        // only check if player is inside a museum region
        Optional<MuseumRegion> hit = plugin.getMuseumsManager().findByLocation(to);
        if (hit.isEmpty()) return;

        MuseumRegion region = hit.get();
        Map<String, MuseumsManager.DoorSpec> doors = plugin.getMuseumsManager().getDoors();
        if (doors.isEmpty()) return;

        // region center is stored as block coords; use +0.5 to match your centerLoc style
        double baseX = region.getCenter().x() + 0.5;
        double baseY = region.getCenter().y();
        double baseZ = region.getCenter().z() + 0.5;

        for (MuseumsManager.DoorSpec door : doors.values()) {
            Vector off = door.offset();

            double dx = (baseX + off.getX()) - to.getX();
            double dy = (baseY + off.getY()) - to.getY();
            double dz = (baseZ + off.getZ()) - to.getZ();

            double distSq = dx * dx + dy * dy + dz * dz;
            double r = Math.max(0.1, door.radius());
            if (distSq > (r * r)) continue;

            if (isOnCooldown(p.getUniqueId(), door.key(), door.cooldownTicks())) {
                continue;
            }

            teleportPlayer(p, region, door);
            setCooldownNow(p.getUniqueId(), door.key());
            break; // stop after first door match
        }
    }

    private boolean isOnCooldown(UUID playerId, String doorKey, long cooldownTicks) {
        long cooldownMs = Math.max(0L, cooldownTicks) * 50L;
        long now = System.currentTimeMillis();

        Map<String, Long> map = cooldowns.get(playerId);
        if (map == null) return false;

        Long last = map.get(doorKey);
        if (last == null) return false;

        return (now - last) < cooldownMs;
    }

    private void setCooldownNow(UUID playerId, String doorKey) {
        cooldowns.computeIfAbsent(playerId, k -> new HashMap<>())
                .put(doorKey, System.currentTimeMillis());
    }

    private void teleportPlayer(Player p, MuseumRegion region, MuseumsManager.DoorSpec door) {
        Location dest = plugin.getMuseumsManager().resolveDoorTeleport(region, door);
        if (dest == null) {
            plugin.getLogger().warning("[Museums] Door '" + door.key() + "' destination is null.");
            return;
        }

        MuseumsManager.TeleportTarget t = door.target();

        float yaw = (t.yaw() != null) ? t.yaw() : p.getLocation().getYaw();
        float pitch = (t.pitch() != null) ? t.pitch() : p.getLocation().getPitch();

        dest.setYaw(yaw);
        dest.setPitch(pitch);

        if (dest.getWorld() == null) {
            plugin.getLogger().warning("[Museums] Door '" + door.key() + "' destination world is null.");
            return;
        }

        // 1) execută comenzile imediat
        if (door.commands() != null && !door.commands().isEmpty()) {
            for (String raw : door.commands()) {
                if (raw == null || raw.isBlank()) continue;
                String cmd = MuseumsManager.expandPlaceholders(raw, dest, yaw, pitch, p.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
        }

        // 2) după 10 tick-uri, încarcă chunk-ul și teleportează
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) {
                return;
            }

            if (dest.getWorld() == null) {
                plugin.getLogger().warning("[Museums] Door '" + door.key() + "' destination world became null.");
                return;
            }

            dest.getChunk().load(true);

            if (!p.teleport(dest)) {
                plugin.getLogger().warning("[Museums] Door '" + door.key() + "' teleport failed for " + p.getName());
            }
        }, 10L);
    }
}