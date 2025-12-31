package me.andrei9876.museum.models;

import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.*;

public class MuseumRegion {

    private final UUID owner;
    private final String worldName;
    private final BlockVector3 min;
    private final BlockVector3 max;
    private final BlockVector3 center; // integer center (block coords)
    private final Vector spawn;        // precise player spawn (doubles)
    private final Map<String, Vector> points; // absolute positions for named offsets (kept internal & mutable)

    // Keep internals mutable; return read-only views via getters.
    private final LinkedHashSet<String> categories = new LinkedHashSet<>();
    private final LinkedHashMap<String, List<String>> availableByCategory = new LinkedHashMap<>();

    /** Legacy: no categories/values. */
    public MuseumRegion(UUID owner,
                        String worldName,
                        BlockVector3 min,
                        BlockVector3 max,
                        BlockVector3 center,
                        Vector spawn,
                        Map<String, Vector> points) {
        this(owner, worldName, min, max, center, spawn, points,
                Collections.emptySet(), Collections.emptyMap());
    }

    /** Convenience: single category + values. */
    public MuseumRegion(UUID owner,
                        String worldName,
                        BlockVector3 min,
                        BlockVector3 max,
                        BlockVector3 center,
                        Vector spawn,
                        Map<String, Vector> points,
                        String category,
                        List<String> values) {
        this(owner, worldName, min, max, center, spawn, points,
                category == null ? Collections.emptySet() : Collections.singleton(category),
                category == null ? Collections.emptyMap()
                        : Collections.singletonMap(category, values == null ? Collections.emptyList() : values));
    }

    /** Full constructor with multiple categories and per-category values. */
    public MuseumRegion(UUID owner,
                        String worldName,
                        BlockVector3 min,
                        BlockVector3 max,
                        BlockVector3 center,
                        Vector spawn,
                        Map<String, Vector> points,
                        Collection<String> categories,
                        Map<String, List<String>> availableByCategory) {
        this.owner = owner;
        this.worldName = worldName;
        this.min = min;
        this.max = max;
        this.center = center;
        this.spawn = spawn;

        // keep a private mutable copy for points
        this.points = points == null ? new LinkedHashMap<>() : new LinkedHashMap<>(points);

        // normalize categories (trim, dedupe, keep order)
        if (categories != null) {
            for (String c : categories) {
                if (c == null) continue;
                String t = c.trim();
                if (!t.isEmpty()) this.categories.add(t);
            }
        }

        // normalize map (trim cat names, trim & dedupe values, keep order)
        if (availableByCategory != null) {
            for (Map.Entry<String, List<String>> e : availableByCategory.entrySet()) {
                String cat = e.getKey();
                if (cat == null) continue;
                String cleanCat = cat.trim();
                if (cleanCat.isEmpty()) continue;

                LinkedHashSet<String> dedupVals = new LinkedHashSet<>();
                List<String> vals = e.getValue();
                if (vals != null) {
                    for (String v : vals) {
                        if (v == null) continue;
                        String tv = v.trim();
                        if (!tv.isEmpty()) dedupVals.add(tv);
                    }
                }

                this.categories.add(cleanCat);
                this.availableByCategory
                        .computeIfAbsent(cleanCat, k -> new ArrayList<>())
                        .addAll(dedupVals);
            }
        }
    }

    public UUID getOwner() { return owner; }
    public String getWorldName() { return worldName; }
    public BlockVector3 getMin() { return min; }
    public BlockVector3 getMax() { return max; }
    public BlockVector3 getCenter() { return center; }
    public Vector getSpawn() { return spawn; }

    /** Absolute positions (world coords) for each named offset (e.g. Loc1, Loc2). */
    public Map<String, Vector> getPoints() {
        return Collections.unmodifiableMap(points);
    }

    /** Convenience: get one named point (absolute). */
    public Vector getPoint(String name) {
        return points.get(name);
    }

    public Location getSpawnLocation() {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return null;
        return new Location(w, spawn.getX(), spawn.getY(), spawn.getZ());
    }

    /** Axis-aligned full AABB (XYZ) vs Bukkit Location. */
    public boolean isInside(Location loc) {
        if (loc == null || loc.getWorld() == null || !loc.getWorld().getName().equals(worldName)) return false;
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        return x >= min.x() && x <= max.x()
                && y >= min.y() && y <= max.y()
                && z >= min.z() && z <= max.z();
    }

    /** Read-only view of categories (keeps insertion order). */
    public Set<String> getCategories() {
        return Collections.unmodifiableSet(categories);
    }

    /** Read-only deep view: map with unmodifiable lists. */
    public Map<String, List<String>> getAvailableByCategory() {
        LinkedHashMap<String, List<String>> view = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : availableByCategory.entrySet()) {
            view.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
        }
        return Collections.unmodifiableMap(view);
    }

    /** Add a value to a category (creates category if missing). Trims & dedupes. */
    public void addValue(String category, String value) {
        if (category == null || value == null) return;
        String c = category.trim();
        String v = value.trim();
        if (c.isEmpty() || v.isEmpty()) return;

        categories.add(c);
        List<String> list = availableByCategory.computeIfAbsent(c, k -> new ArrayList<>());
        if (!list.contains(v)) list.add(v);
    }

    public void removeValue(String category, String value) {
        if (category == null || value == null) return;
        String c = category.trim();
        String v = value.trim();
        if (c.isEmpty() || v.isEmpty()) return;

        List<String> list = availableByCategory.get(c);
        if (list != null) {
            list.remove(v);
            if (list.isEmpty()) {
                availableByCategory.remove(c);
                categories.remove(c); // remove category only if it's now empty
            }
        }
    }

    /** Get values for a category (read-only list). Returns null if category missing. */
    public List<String> getValue(String category) {
        List<String> list = availableByCategory.get(category);
        return list == null ? null : Collections.unmodifiableList(list);
    }
}
