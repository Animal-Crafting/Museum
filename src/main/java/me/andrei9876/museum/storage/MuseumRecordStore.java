package me.andrei9876.museum.storage;

import me.andrei9876.museum.models.MuseumRegion;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Field-level updates preserve raw legacy values, unknown keys and records that failed to load. */
public final class MuseumRecordStore {
    private final Map<UUID, Map<String, Object>> baseline = new HashMap<>();

    public void remember(UUID id, MuseumRegion region) {
        baseline.put(id, fields(region));
    }

    public boolean save(YamlConfiguration document, Map<UUID, MuseumRegion> regions, Path destination) throws IOException {
        YamlConfiguration pending = new YamlConfiguration();
        try { pending.loadFromString(document.saveToString()); }
        catch (org.bukkit.configuration.InvalidConfigurationException ex) { throw new IOException(ex); }
        boolean changed = false;
        for (var entry : regions.entrySet()) {
            UUID id = entry.getKey();
            var current = fields(entry.getValue());
            var previous = baseline.get(id);
            String prefix = "museums." + id + ".";
            if (previous != null) {
                for (String key : previous.keySet()) {
                    if (!current.containsKey(key)) current.put(key, null);
                }
            }
            for (var field : current.entrySet()) {
                if (previous == null || !Objects.equals(previous.get(field.getKey()), field.getValue())) {
                    pending.set(prefix + field.getKey(), field.getValue());
                    changed = true;
                }
            }
        }
        // Only delete explicitly removed, successfully loaded records. Unreadable records survive.
        for (UUID id : baseline.keySet()) {
            if (!regions.containsKey(id)) {
                pending.set("museums." + id, null);
                changed = true;
            }
        }
        if (!changed) return false;
        atomicWrite(destination, pending.saveToString());
        // Commit the in-memory document and baseline only after the atomic disk write succeeds.
        for (String key : new HashSet<>(document.getKeys(false))) document.set(key, null);
        for (String key : pending.getKeys(false)) document.set(key, pending.get(key));
        baseline.clear();
        regions.forEach(this::remember);
        return true;
    }

    public static void atomicWrite(Path destination, String text) throws IOException {
        Path temp = Files.createTempFile(destination.toAbsolutePath().getParent(), "museums-", ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try { Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private static Map<String, Object> fields(MuseumRegion region) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("world", region.getWorldName());
        fields.put("owner", region.getOwner().toString());
        fields.put("min", List.of(region.getMin().x(), region.getMin().y(), region.getMin().z()));
        fields.put("max", List.of(region.getMax().x(), region.getMax().y(), region.getMax().z()));
        fields.put("center", List.of(region.getCenter().x(), region.getCenter().y(), region.getCenter().z()));
        fields.put("spawn", vector(region.getSpawn()));
        Map<String, Object> points = new LinkedHashMap<>();
        region.getPoints().forEach((key, value) -> points.put(key, vector(value)));
        fields.put("points", points.isEmpty() ? null : points);
        fields.put("categories", region.getCategories().isEmpty() ? null : new ArrayList<>(region.getCategories()));
        // Update individual categories, retaining unknown sibling fields and original omitted fields.
        region.getAvailableByCategory().forEach((key, values) ->
                fields.put("availableByCategory." + key, new ArrayList<>(values)));
        return fields;
    }

    private static List<Double> vector(Vector v) { return List.of(v.getX(), v.getY(), v.getZ()); }
}
