package me.andrei9876.museum.managers;

import me.andrei9876.museum.Museum;
import me.andrei9876.museum.models.MuseumRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.logging.Level;

public class MuseumsCreatures {

    private final Museum plugin;

    private final Map<String, List<String>> creaturesByCategory = new HashMap<>();
    private final Set<String> allCreatures = new HashSet<>();

    public MuseumsCreatures(Museum plugin) {
        this.plugin = plugin;
        loadFromConfig();
    }

    private record cVal(String category, String value) {}

    // =========================
    // CONFIG LOADING
    // =========================

    public void loadFromConfig() {
        creaturesByCategory.clear();
        allCreatures.clear();

        ConfigurationSection root = plugin.getConfig().getConfigurationSection("creatures");
        if (root == null) {
            plugin.getLogger().warning("No 'creatures' section found in config.yml");
            return;
        }

        for (String category : root.getKeys(false)) {
            List<String> list = root.getStringList(category);

            List<String> cleaned = new ArrayList<>();
            for (String s : list) {
                if (s != null && !s.trim().isEmpty()) {
                    cleaned.add(s.trim());
                }
            }

            creaturesByCategory.put(category, Collections.unmodifiableList(cleaned));
            allCreatures.addAll(cleaned);
        }

        plugin.getLogger().info(() ->
                "Loaded creatures: " + creaturesByCategory.size()
                        + " categories, " + allCreatures.size() + " total");
    }

    // =========================
    // PUBLIC API
    // =========================

    private void debug(String msg) {
        plugin.getLogger().log(Level.INFO, "[MuseumsCreatures][DEBUG] " + msg);
    }


    public boolean addCreature(UUID playerID, String creatureId) {

        debug("addCreature called: player=" + playerID + ", creatureId=" + creatureId);

        MuseumRegion region = plugin.getMuseumsManager().getMuseum(playerID);
        if (region == null) {
            debug("❌ MuseumRegion is null");
            return false;
        }
        debug("✔ MuseumRegion found");

        boolean isFossil =
                plugin.getConfig().contains("offsets.Fossils." + creatureId);
        debug("isFossil=" + isFossil);

        cVal val = get(creatureId);
        debug("get(creatureId) => " + (val == null ? "null" : val.category + "/" + val.value));

        if (val == null && isFossil) {
            debug("Creature not registered but fossil exists → creating Fossils cVal");
            val = new cVal("Fossils", creatureId);
        }

        if (val == null) {
            debug("❌ cVal is still null → aborting");
            return false;
        }

        // Already added?
        for (Map.Entry<String, List<String>> entry : region.getAvailableByCategory().entrySet()) {
            for (String s : entry.getValue()) {
                if (s.equalsIgnoreCase(val.value)) {
                    debug("❌ Creature already added in category " + entry.getKey());
                    return false;
                }
            }
        }
        debug("✔ Creature not already added");

        // =========================
        // FOSSILS
        // =========================
        if (val.category.equalsIgnoreCase("Fossils")) {

            debug("Handling fossil spawn");

            if (!spawnFossil(region, creatureId)) {
                debug("❌ spawnFossil() returned false");
                return false;
            }

            debug("✔ Fossil spawned successfully");
            region.addValue(val.category, val.value);
            plugin.getMuseumsManager().updateMuseumRegion(playerID, region);
            debug("✔ Fossil saved to museum");
            return true;
        }

        // =========================
        // NORMAL CREATURES
        // =========================
        Vector point = region.getPoint(val.category);
        if (point == null) {
            debug("❌ No spawn point for category: " + val.category);
            return false;
        }

        debug("Spawning MythicMob: " + val.value + " at " + point);

        Bukkit.dispatchCommand(
                Bukkit.getConsoleSender(),
                "mm m spawn %value% 1 %world%,%x%,%y%,%z%"
                        .replace("%value%", val.value)
                        .replace("%world%", region.getWorldName())
                        .replace("%x%", String.valueOf(point.getX()))
                        .replace("%y%", String.valueOf(point.getY()))
                        .replace("%z%", String.valueOf(point.getZ()))
        );

        region.addValue(val.category, val.value);
        plugin.getMuseumsManager().updateMuseumRegion(playerID, region);
        debug("✔ Creature spawned and saved");
        return true;
    }


    public boolean remCreature(UUID playerID, String creatureId) {

        MuseumRegion region = plugin.getMuseumsManager().getMuseum(playerID);
        if (region == null) return false;

        boolean exists = false;
        for (List<String> list : region.getAvailableByCategory().values()) {
            for (String s : list) {
                if (s.equalsIgnoreCase(creatureId)) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) return false;

        cVal val = get(creatureId);
        if (val == null) {
            val = new cVal("Fossils", creatureId);
        }

        region.removeValue(val.category, val.value);
        plugin.getMuseumsManager().updateMuseumRegion(playerID, region);
        return true;
    }

    // =========================
    // FOSSILS LOGIC
    // =========================

    private boolean spawnFossil(MuseumRegion region, String fossilId) {

        ConfigurationSection fossil =
                plugin.getConfig().getConfigurationSection("offsets.Fossils." + fossilId);

        if (fossil == null) {
            plugin.getLogger().warning(
                    "Fossil '" + fossilId + "' is missing from offsets.Fossils"
            );
            return false;
        }

        // Base position: Fossils offset or museum center
        Vector base = region.getPoint("Fossils");
        if (base == null) {
            com.sk89q.worldedit.math.BlockVector3 center = region.getCenter();
            base = new Vector(center.x(), center.y(), center.z());
        }

        Vector spawn = base.clone().add(new Vector(
                fossil.getDouble("x"),
                fossil.getDouble("y"),
                fossil.getDouble("z")
        ));

        float yaw = (float) fossil.getDouble("yaw", 0.0);
        float pitch = (float) fossil.getDouble("pitch", 0.0);

        // Commands (optional)
        for (String cmd : fossil.getStringList("commands")) {
            Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(),
                    cmd.replace("%world%", region.getWorldName())
                            .replace("%x%", String.valueOf(spawn.getX()))
                            .replace("%y%", String.valueOf(spawn.getY()))
                            .replace("%z%", String.valueOf(spawn.getZ()))
                            .replace("%yaw%", String.valueOf(yaw))
                            .replace("%pitch%", String.valueOf(pitch))
            );
        }

        // ArmorStand disabled → done
        if (!fossil.getBoolean("armorStand", false)) {
            return true;
        }

        Material material = Material.matchMaterial(fossil.getString("item", ""));
        int modelData = fossil.getInt("custommodeldata", -1);

        if (material == null || modelData < 0) {
            plugin.getLogger().warning(
                    "Invalid ArmorStand fossil config for '" + fossilId + "'"
            );
            return false;
        }

        spawnArmorStandItem(region.getWorldName(), spawn, material, modelData, yaw, pitch);
        return true;
    }



    private void spawnArmorStandItem(
            String worldName,
            Vector pos,
            Material material,
            int customModelData,
            float yaw,
            float pitch
    ) {

        World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        Location loc = new Location(
                world,
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                yaw,
                pitch
        );

        ArmorStand stand = world.spawn(loc, ArmorStand.class, as -> {
            as.setInvisible(true);
            as.setMarker(true);
            as.setGravity(false);
            as.setSilent(true);
            as.setRotation(yaw, pitch);
        });

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setCustomModelData(customModelData);
            item.setItemMeta(meta);
        }

        stand.getEquipment().setHelmet(item);
    }


    // =========================
    // UTILS
    // =========================

    public boolean contains(String creatureId) {
        return allCreatures.contains(creatureId);
    }

    public void clear() {
        creaturesByCategory.clear();
        allCreatures.clear();
    }

    private cVal get(String creatureID) {
        for (Map.Entry<String, List<String>> e : creaturesByCategory.entrySet()) {
            for (String c : e.getValue()) {
                if (creatureID.equals(c)) {
                    return new cVal(e.getKey(), c);
                }
            }
        }
        return null;
    }
}
