package me.andrei9876.museum.storage;

import com.sk89q.worldedit.math.BlockVector3;
import me.andrei9876.museum.models.MuseumRegion;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.util.Vector;
import java.util.*;
import java.util.function.Function;

/** Legacy record reader shared by production storage and compatibility tests. */
public final class MuseumRegionCodec {
    private MuseumRegionCodec() {}

    public static MuseumRegion read(ConfigurationSection section, UUID key, Vector spawnOffset,
                                    Function<BlockVector3, Map<String, Vector>> fallbackPoints) {
        BlockVector3 min = blockVector(section, "min");
        BlockVector3 max = blockVector(section, "max");
        BlockVector3 center = blockVector(section, "center");
        List<?> savedSpawn = section.getList("spawn");
        Vector spawn = savedSpawn != null && savedSpawn.size() >= 3 ? vector(savedSpawn)
                : new Vector(center.x() + 0.5 + spawnOffset.getX(), center.y() + spawnOffset.getY(),
                        center.z() + 0.5 + spawnOffset.getZ());
        Map<String, Vector> points = new LinkedHashMap<>();
        ConfigurationSection savedPoints = section.getConfigurationSection("points");
        if (savedPoints != null) for (String name : savedPoints.getKeys(false)) {
            List<?> coordinates = savedPoints.getList(name);
            if (coordinates != null && coordinates.size() >= 3) points.put(name, vector(coordinates));
        }
        if (points.isEmpty()) points.putAll(fallbackPoints.apply(center));
        LinkedHashSet<String> categories = new LinkedHashSet<>(section.getStringList("categories"));
        Map<String, List<String>> available = new LinkedHashMap<>();
        ConfigurationSection savedAvailable = section.getConfigurationSection("availableByCategory");
        if (savedAvailable != null) for (String category : savedAvailable.getKeys(false)) {
            available.put(category, savedAvailable.getStringList(category));
        }
        if (categories.isEmpty()) categories.addAll(available.keySet());
        for (String category : categories) available.computeIfAbsent(category, ignored -> new ArrayList<>());
        return new MuseumRegion(UUID.fromString(section.getString("owner", key.toString())),
                section.getString("world", "world"), min, max, center, spawn, points, categories, available);
    }

    private static BlockVector3 blockVector(ConfigurationSection section, String name) {
        List<?> values = section.getList(name);
        if (values == null || values.size() < 3) throw new IllegalArgumentException("Missing vector: " + name);
        return BlockVector3.at(((Number) values.get(0)).intValue(), ((Number) values.get(1)).intValue(),
                ((Number) values.get(2)).intValue());
    }

    private static Vector vector(List<?> values) {
        return new Vector(((Number) values.get(0)).doubleValue(), ((Number) values.get(1)).doubleValue(),
                ((Number) values.get(2)).doubleValue());
    }
}
