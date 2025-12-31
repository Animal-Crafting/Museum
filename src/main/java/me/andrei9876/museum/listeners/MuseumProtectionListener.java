package me.andrei9876.museum.listeners;

import me.andrei9876.museum.Museum;
import me.andrei9876.museum.models.MuseumRegion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.Optional;

public class MuseumProtectionListener implements Listener {

    private final Museum plugin;

    public MuseumProtectionListener(Museum plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Optional<MuseumRegion> hit = plugin.getMuseumsManager().findByLocation(e.getBlock().getLocation());
        if (hit.isEmpty()) return;

        MuseumRegion r = hit.get();
        boolean isOwner = e.getPlayer().getUniqueId().equals(r.getOwner());
        boolean bypass = e.getPlayer().hasPermission(Museum.BYPASS_PERM);

        if (!isOwner && !bypass) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§cYou cannot break blocks inside this museum.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Optional<MuseumRegion> hit = plugin.getMuseumsManager().findByLocation(e.getBlockPlaced().getLocation());
        if (hit.isEmpty()) return;

        MuseumRegion r = hit.get();
        boolean isOwner = e.getPlayer().getUniqueId().equals(r.getOwner());
        boolean bypass = e.getPlayer().hasPermission(Museum.BYPASS_PERM);

        if (!isOwner && !bypass) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§cYou cannot place blocks inside this museum.");
        }
    }
}
