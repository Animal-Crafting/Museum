package me.andrei9876.museum.utils;

import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.holograms.Hologram;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

public class HologramUtil {

    public static void spawnOffsetHologram(Location center, ConfigurationSection hs, String hologramName) {
        if (center == null || hs == null) return;

        // Don't touch DHAPI unless the plugin is actually enabled
        if (!isDecentHologramsEnabled()) {
            return;
        }

        double x = hs.getDouble("x", 0.0);
        double y = hs.getDouble("y", 0.0);
        double z = hs.getDouble("z", 0.0);

        List<String> lines = hs.getStringList("lines");
        if (lines == null || lines.isEmpty()) return;

        Location loc = center.clone().add(x, y, z);

        try {
            Hologram existing = DHAPI.getHologram(hologramName);
            if (existing != null) existing.delete();

            DHAPI.createHologram(hologramName, loc, true, lines);
        } catch (IllegalStateException | ExceptionInInitializerError ex) {
            // DecentHolograms not ready (yet) or missing; skip silently or log if you prefer
            return;
        }
    }

    private static boolean isDecentHologramsEnabled() {
        var plugin = Bukkit.getPluginManager().getPlugin("DecentHolograms");
        return plugin != null && plugin.isEnabled();
    }
}
