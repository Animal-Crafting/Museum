package me.andrei9876.museum.managers;

import com.fastasyncworldedit.core.FaweAPI;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import me.andrei9876.museum.Museum;
import me.andrei9876.museum.models.MuseumRegion;
import me.andrei9876.museum.utils.HologramUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MuseumsManager {
    private final Museum plugin;
    private final Logger log;
    private final boolean debug;

    public final Map<UUID, MuseumRegion> museums = new HashMap<>();

    private World world;
    private String schematicName;
    /** Extra gap between museums; real spacing = schematic size + this */
    private int distanceBetween;
    /** Fixed museum center Y for void/custom worlds */
    private int museumCenterY;
    /** Player spawn offset (from center) */
    private Vector spawnOffset = new Vector(0, 0, 0);

    /** Other named offsets with optional commands (from config: offsets.* except player_spawn) */
    private final Map<String, OffsetSpec> namedOffsets = new LinkedHashMap<>();
    private final Map<String, DoorSpec> doors = new LinkedHashMap<>();

    private File museumsFile;
    private FileConfiguration museumsConfig;

    /**
     * @param offset   relative to museum center
     * @param commands run after paste at this offset
     */
    private record OffsetSpec(String key, Vector offset, List<String> commands, boolean runOnCreate) {
        private OffsetSpec(String key, Vector offset, List<String> commands, boolean runOnCreate) {
            this.key = key;
            this.offset = offset;
            this.commands = commands == null ? Collections.emptyList() : new ArrayList<>(commands);
            this.runOnCreate = runOnCreate;
        }
    }

    public record TeleportTarget(
            String world,
            double x,
            double y,
            double z,
            Float yaw,
            Float pitch,
            boolean relative,
            boolean relativeToSpawn
    ) {}
    public record DoorSpec(String key, Vector offset, double radius, long cooldownTicks, TeleportTarget target, List<String> commands) {}

    public MuseumsManager(Museum plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.debug = plugin.getConfig().getBoolean("debug", false);

        dbg("Initializing MuseumsManager...");

        // Load offsets first so spawnOffset fallback is correct when loading old entries without "spawn"
        loadOffsetsFromConfig();

        initMuseumsStorage();
        loadMuseums();

        if (!getWorld()) {
            log.severe("[Museums] World acquisition failed. Disabling plugin.");
            plugin.getServer().getPluginManager().disablePlugin(plugin);
            return;
        }

        schematicName = plugin.getConfig().getString("schematic_name");
        distanceBetween = plugin.getConfig().getInt("distance_between", 48);
        museumCenterY = plugin.getConfig().getInt("museum_center_y", 87);

        dbg(
                "Config loaded: schematic_name=%s, distance_between=%d, museumCenterY=%d, spawnOffset=(%.3f,%.3f,%.3f), namedOffsets=%d",
                schematicName,
                distanceBetween,
                museumCenterY,
                spawnOffset.getX(),
                spawnOffset.getY(),
                spawnOffset.getZ(),
                namedOffsets.size()
        );
    }

    private void initMuseumsStorage() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            log.warning("[Museums] Could not create plugin data folder: " + plugin.getDataFolder());
        }

        this.museumsFile = new File(plugin.getDataFolder(), "museums.yml");
        if (!museumsFile.exists()) {
            plugin.saveResource("museums.yml", false);
        }

        this.museumsConfig = YamlConfiguration.loadConfiguration(museumsFile);
        if (museumsConfig.getConfigurationSection("museums") == null) {
            museumsConfig.createSection("museums");
            saveMuseumsConfig();
        }
    }

    private void saveMuseumsConfig() {
        try {
            museumsConfig.save(museumsFile);
        } catch (IOException e) {
            log.log(Level.SEVERE, "[Museums] Failed to save museums.yml", e);
        }
    }

    /* ==================== CONFIG: OFFSETS ==================== */

    private void loadOffsetsFromConfig() {
        namedOffsets.clear();
        doors.clear();

        ConfigurationSection root = plugin.getConfig().getConfigurationSection("offsets");
        if (root == null) return;

        // ---- doors ----
        ConfigurationSection doorsSec = root.getConfigurationSection("doors");
        if (doorsSec != null) {
            for (String doorKey : doorsSec.getKeys(false)) {
                ConfigurationSection d = doorsSec.getConfigurationSection(doorKey);
                if (d == null) continue;

                double x = d.getDouble("x", 0.0);
                double y = d.getDouble("y", 0.0);
                double z = d.getDouble("z", 0.0);
                double radius = d.getDouble("radius", 1.25);
                long cooldownTicks = d.getLong("cooldown_ticks", 20L);

                ConfigurationSection t = d.getConfigurationSection("teleport");
                if (t == null) {
                    log.warning("[Museums] Door '" + doorKey + "' missing 'teleport' section, skipping.");
                    continue;
                }

                String targetWorld = t.getString("world", null);
                boolean relative = t.getBoolean("relative", false);
                boolean relativeToSpawn = t.getBoolean("relative_to_spawn", false);

                double tx = t.getDouble("x", 0.0);
                double ty = t.getDouble("y", 0.0);
                double tz = t.getDouble("z", 0.0);

                Float yaw = t.contains("yaw") ? (float) t.getDouble("yaw") : null;
                Float pitch = t.contains("pitch") ? (float) t.getDouble("pitch") : null;

                List<String> commands = d.getStringList("commands");

                DoorSpec spec = new DoorSpec(
                        doorKey,
                        new Vector(x, y, z),
                        radius,
                        cooldownTicks,
                        new TeleportTarget(targetWorld, tx, ty, tz, yaw, pitch, relative, relativeToSpawn),
                        commands == null ? Collections.emptyList() : new ArrayList<>(commands)
                );

                doors.put(doorKey, spec);

                dbg(
                        "Loaded door '%s': off=(%.3f,%.3f,%.3f) radius=%.3f cooldownTicks=%d targetWorld=%s relative=%s target=(%.3f,%.3f,%.3f)",
                        doorKey, x, y, z, radius, cooldownTicks,
                        targetWorld == null ? "<museum-world>" : targetWorld,
                        relative,
                        tx, ty, tz
                );
            }
        }

        // ---- normal offsets + grouped offsets ----
        for (String key : root.getKeys(false)) {
            if ("doors".equalsIgnoreCase(key)) continue;

            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) continue;

            // player spawn = offset simplu
            if ("player_spawn".equalsIgnoreCase(key)) {
                double x = sec.getDouble("x", 0.0);
                double y = sec.getDouble("y", 0.0);
                double z = sec.getDouble("z", 0.0);

                spawnOffset = new Vector(x, y, z);
                dbg("Loaded player_spawn: (%.3f,%.3f,%.3f)", x, y, z);
                continue;
            }

            // dacă secțiunea are commands sau lines ori doar x/y/z, o tratăm ca offset simplu
            boolean simpleOffset =
                    sec.contains("x") || sec.contains("y") || sec.contains("z") ||
                            sec.contains("commands") || sec.contains("lines");

            // detectăm sub-secțiuni reale (ex: Fossils.Diplodocus)
            List<String> childSections = new ArrayList<>();
            for (String childKey : sec.getKeys(false)) {
                if (sec.isConfigurationSection(childKey)) {
                    childSections.add(childKey);
                }
            }

            // dacă are copii și NU are commands proprii, o tratăm ca group/container
            boolean groupedContainer = !childSections.isEmpty() && !sec.contains("commands");

            if (groupedContainer) {
                dbg("Loading grouped offsets container '%s' with %d children", key, childSections.size());

                for (String childKey : childSections) {
                    ConfigurationSection child = sec.getConfigurationSection(childKey);
                    if (child == null) continue;

                    double x = child.getDouble("x", 0.0);
                    double y = child.getDouble("y", 0.0);
                    double z = child.getDouble("z", 0.0);
                    List<String> commands = child.getStringList("commands");

                    String fullKey = key + "." + childKey;

                    namedOffsets.put(fullKey, new OffsetSpec(
                            fullKey,
                            new Vector(x, y, z),
                            commands,
                            false
                    ));

                    dbg(
                            "Loaded grouped offset '%s': (%.3f,%.3f,%.3f), commands=%d, runOnCreate=%s",
                            fullKey, x, y, z, commands.size(), false
                    );
                }

                continue;
            }

            // offset simplu normal
            if (simpleOffset) {
                double x = sec.getDouble("x", 0.0);
                double y = sec.getDouble("y", 0.0);
                double z = sec.getDouble("z", 0.0);
                List<String> commands = sec.getStringList("commands");

                namedOffsets.put(key, new OffsetSpec(
                        key,
                        new Vector(x, y, z),
                        commands,
                        true
                ));
                dbg(
                        "Loaded offset '%s': (%.3f,%.3f,%.3f), commands=%d, runOnCreate=%s",
                        key, x, y, z, commands.size(), true
                );
            }
        }
    }

    public Map<String, DoorSpec> getDoors() {
        return Collections.unmodifiableMap(doors);
    }

    /* ==================== DEBUG ==================== */

    private void dbg(String msg, Object... args) {
        if (debug) log.info("[Museums:DEBUG] " + String.format(msg, args));
    }

    private boolean verifyMuseumAtLocation(MuseumRegion region) {
        World w = Bukkit.getWorld(region.getWorldName());
        if (w == null) return false;

        BlockVector3 min = region.getMin();
        BlockVector3 max = region.getMax();

        // Sample multiple points (fast, safe)
        int[][] samples = {
                {min.x(), min.y(), min.z()},
                {max.x(), min.y(), min.z()},
                {min.x(), min.y(), max.z()},
                {max.x(), min.y(), max.z()},
                {region.getCenter().x(), region.getCenter().y(), region.getCenter().z()}
        };

        for (int[] s : samples) {
            if (!w.getBlockAt(s[0], s[1], s[2]).isEmpty()) {
                return true;
            }
        }

        return false;
    }

    /* ==================== PASTE ==================== */

    public void pasteClipboardCenteredAsync(
            Clipboard clipboard,
            Location centerLoc,
            boolean ignoreAir,
            Runnable onDone
    ) {
        BlockVector3 min = clipboard.getRegion().getMinimumPoint();
        BlockVector3 max = clipboard.getRegion().getMaximumPoint();

        BlockVector3 clipCenter = BlockVector3.at(
                (min.x() + max.x()) / 2,
                (min.y() + max.y()) / 2,
                (min.z() + max.z()) / 2
        );

        BlockVector3 offset = clipboard.getOrigin().subtract(clipCenter);
        BlockVector3 target = BlockVector3.at(
                centerLoc.getBlockX(),
                centerLoc.getBlockY(),
                centerLoc.getBlockZ()
        ).add(offset);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                com.sk89q.worldedit.world.World weWorld =
                        FaweAPI.getWorld(centerLoc.getWorld().getName());

                try (EditSession es = WorldEdit.getInstance()
                        .newEditSessionBuilder()
                        .world(weWorld)
                        .fastMode(true)
                        .build()) {

                    Operation op = new ClipboardHolder(clipboard)
                            .createPaste(es)
                            .to(target)
                            .ignoreAirBlocks(ignoreAir)
                            .build();

                    Operations.complete(op);
                    es.flushQueue();
                }

                if (onDone != null) {
                    Bukkit.getScheduler().runTask(plugin, onDone);
                }

            } catch (Throwable t) {
                log.log(Level.SEVERE, "[Museums] Async paste failed", t);
                if (onDone != null) {
                    Bukkit.getScheduler().runTask(plugin, onDone);
                }
            }
        });
    }

    private void preloadChunksAround(Location loc, int radiusChunks) {
        int cx0 = loc.getBlockX() >> 4;
        int cz0 = loc.getBlockZ() >> 4;
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                world.getChunkAt(cx0 + dx, cz0 + dz).setForceLoaded(true);
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
                for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                    world.getChunkAt(cx0 + dx, cz0 + dz).setForceLoaded(false);
                }
            }
        }, 20L * 10);
    }

    /* ==================== LIGHTING REFRESH (optional) ==================== */

    private void refreshLightingForRegion(MuseumRegion r) {
        if (r == null) return;
        World w = Bukkit.getWorld(r.getWorldName());
        if (w == null) return;

        int minCX = Math.floorDiv(r.getMin().x(), 16);
        int maxCX = Math.floorDiv(r.getMax().x(), 16);
        int minCZ = Math.floorDiv(r.getMin().z(), 16);
        int maxCZ = Math.floorDiv(r.getMax().z(), 16);

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (int cx = minCX; cx <= maxCX; cx++) {
                for (int cz = minCZ; cz <= maxCZ; cz++) {
                    w.getChunkAt(cx, cz).load();
                    w.refreshChunk(cx, cz);
                }
            }
        });
    }

    /* ==================== REGION & SPAWN ==================== */

    private MuseumRegion computePastedMuseum(UUID owner, Clipboard clipboard, Location centerLoc) {
        BlockVector3 regMin = clipboard.getRegion().getMinimumPoint();
        BlockVector3 regMax = clipboard.getRegion().getMaximumPoint();
        BlockVector3 clipCenter = BlockVector3.at(
                (regMin.x() + regMax.x()) / 2,
                (regMin.y() + regMax.y()) / 2,
                (regMax.z() + regMin.z()) / 2
        );
        BlockVector3 origin = clipboard.getOrigin();
        BlockVector3 offset = origin.subtract(clipCenter);

        BlockVector3 worldTarget = BlockVector3.at(
                centerLoc.getBlockX(),
                centerLoc.getBlockY(),
                centerLoc.getBlockZ()
        ).add(offset);

        BlockVector3 delta = worldTarget.subtract(origin);

        BlockVector3 pastedMin = regMin.add(delta);
        BlockVector3 pastedMax = regMax.add(delta);

        BlockVector3 worldCenter = BlockVector3.at(centerLoc.getBlockX(), centerLoc.getBlockY(), centerLoc.getBlockZ());

        // spawn = center + spawnOffset
        Vector spawn = new Vector(
                centerLoc.getX() + spawnOffset.getX(),
                centerLoc.getY() + spawnOffset.getY(),
                centerLoc.getZ() + spawnOffset.getZ()
        );

        // FINAL ABSOLUTE LOCATIONS for all named offsets
        Map<String, Vector> points = new LinkedHashMap<>();
        for (OffsetSpec spec : namedOffsets.values()) {
            points.put(spec.key, new Vector(
                    centerLoc.getX() + spec.offset.getX(),
                    centerLoc.getY() + spec.offset.getY(),
                    centerLoc.getZ() + spec.offset.getZ()
            ));
        }

        dbg(
                "computePastedMuseum: regMin=%s regMax=%s origin=%s clipCenter=%s delta=%s => pastedMin=%s pastedMax=%s spawn=%.3f/%.3f/%.3f points=%d",
                vec(regMin), vec(regMax), vec(origin), vec(clipCenter), vec(delta), vec(pastedMin), vec(pastedMax),
                spawn.getX(), spawn.getY(), spawn.getZ(), points.size()
        );

        return new MuseumRegion(owner, centerLoc.getWorld().getName(), pastedMin, pastedMax, worldCenter, spawn, points);
    }

    /* ==================== STORAGE ==================== */

    public void saveMuseums() {
        dbg("saveMuseums: saving %d entries...", museums.size());

        museumsConfig.set("museums", null);
        ConfigurationSection root = museumsConfig.createSection("museums");

        for (Map.Entry<UUID, MuseumRegion> e : museums.entrySet()) {
            String key = e.getKey().toString();
            MuseumRegion r = e.getValue();

            ConfigurationSection sec = root.createSection(key);
            sec.set("world", r.getWorldName());
            sec.set("owner", r.getOwner().toString());
            sec.set("min", Arrays.asList(r.getMin().x(), r.getMin().y(), r.getMin().z()));
            sec.set("max", Arrays.asList(r.getMax().x(), r.getMax().y(), r.getMax().z()));
            sec.set("center", Arrays.asList(r.getCenter().x(), r.getCenter().y(), r.getCenter().z()));
            sec.set("spawn", Arrays.asList(
                    r.getSpawn().getX(),
                    r.getSpawn().getY(),
                    r.getSpawn().getZ()
            ));

            if (!r.getPoints().isEmpty()) {
                ConfigurationSection pts = sec.createSection("points");
                for (Map.Entry<String, Vector> p : r.getPoints().entrySet()) {
                    Vector v = p.getValue();
                    pts.set(p.getKey(), Arrays.asList(v.getX(), v.getY(), v.getZ()));
                }
            }

            if (!r.getCategories().isEmpty()) {
                sec.set("categories", new ArrayList<>(r.getCategories()));
            }

            if (!r.getAvailableByCategory().isEmpty()) {
                ConfigurationSection av = sec.createSection("availableByCategory");
                for (Map.Entry<String, List<String>> entry : r.getAvailableByCategory().entrySet()) {
                    av.set(entry.getKey(), new ArrayList<>(entry.getValue()));
                }
            }
        }

        saveMuseumsConfig();
        dbg("saveMuseums: done.");
    }

    @SuppressWarnings("unchecked")
    private void loadMuseums() {
        museums.clear();

        if (museumsConfig == null) {
            dbg("loadMuseums: museumsConfig not initialized.");
            return;
        }

        ConfigurationSection root = museumsConfig.getConfigurationSection("museums");
        if (root == null) {
            dbg("loadMuseums: no stored museums.");
            return;
        }

        int ok = 0, fail = 0;
        for (String key : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) continue;

            try {
                UUID id = UUID.fromString(key);
                String world = sec.getString("world", "world");
                String ownerStr = sec.getString("owner", key);
                UUID owner = UUID.fromString(ownerStr);

                List<Number> minL = (List<Number>) sec.getList("min");
                List<Number> maxL = (List<Number>) sec.getList("max");
                List<Number> cenL = (List<Number>) sec.getList("center");
                List<Number> spL = (List<Number>) sec.getList("spawn");

                if (minL == null || maxL == null || cenL == null) {
                    dbg("loadMuseums: skip %s (missing vectors)", key);
                    continue;
                }

                BlockVector3 min = BlockVector3.at(minL.get(0).intValue(), minL.get(1).intValue(), minL.get(2).intValue());
                BlockVector3 max = BlockVector3.at(maxL.get(0).intValue(), maxL.get(1).intValue(), maxL.get(2).intValue());
                BlockVector3 center = BlockVector3.at(cenL.get(0).intValue(), cenL.get(1).intValue(), cenL.get(2).intValue());

                Vector spawn;
                if (spL != null && spL.size() >= 3) {
                    spawn = new Vector(spL.get(0).doubleValue(), spL.get(1).doubleValue(), spL.get(2).doubleValue());
                } else {
                    spawn = new Vector(
                            center.x() + 0.5 + spawnOffset.getX(),
                            center.y() + spawnOffset.getY(),
                            center.z() + 0.5 + spawnOffset.getZ()
                    );
                }

                // points (absolute)
                Map<String, Vector> points = new LinkedHashMap<>();
                ConfigurationSection pts = sec.getConfigurationSection("points");
                if (pts != null) {
                    for (String pkey : pts.getKeys(false)) {
                        List<Number> v = (List<Number>) pts.getList(pkey);
                        if (v != null && v.size() >= 3) {
                            points.put(pkey, new Vector(v.get(0).doubleValue(), v.get(1).doubleValue(), v.get(2).doubleValue()));
                        }
                    }
                }

                // If old museum has no points saved, rebuild from current config offsets
                if (points.isEmpty() && !namedOffsets.isEmpty()) {
                    double baseX = center.x() + 0.5;
                    double baseY = center.y();
                    double baseZ = center.z() + 0.5;

                    for (OffsetSpec spec : namedOffsets.values()) {
                        points.put(spec.key, new Vector(
                                baseX + spec.offset.getX(),
                                baseY + spec.offset.getY(),
                                baseZ + spec.offset.getZ()
                        ));
                    }

                    dbg("loadMuseums: rebuilt %d points for %s from offsets config", points.size(), key);
                }

                // categories (ordered, trimmed, deduped)
                LinkedHashSet<String> categories = new LinkedHashSet<>();
                for (String c : sec.getStringList("categories")) {
                    if (c == null) continue;
                    String t = c.trim();
                    if (!t.isEmpty()) categories.add(t);
                }

                // availableByCategory (map: category -> ordered, trimmed, deduped values)
                LinkedHashMap<String, List<String>> availableByCategory = new LinkedHashMap<>();
                ConfigurationSection av = sec.getConfigurationSection("availableByCategory");
                if (av != null) {
                    for (String cat : av.getKeys(false)) {
                        if (cat == null) continue;
                        String cleanCat = cat.trim();
                        if (cleanCat.isEmpty()) continue;

                        LinkedHashSet<String> vals = new LinkedHashSet<>();
                        for (String v : av.getStringList(cat)) {
                            if (v == null) continue;
                            String tv = v.trim();
                            if (!tv.isEmpty()) vals.add(tv);
                        }
                        availableByCategory.put(cleanCat, new ArrayList<>(vals));
                    }
                }

                // Back-compat glue:
                // - if categories are missing but availableByCategory has entries, derive categories from its keys
                if (categories.isEmpty() && !availableByCategory.isEmpty()) {
                    categories.addAll(availableByCategory.keySet());
                }
                // - if categories contain keys not present in availableByCategory, ensure they exist with empty lists
                for (String c : categories) {
                    availableByCategory.computeIfAbsent(c, k -> new ArrayList<>());
                }

                MuseumRegion r = new MuseumRegion(
                        owner, world, min, max, center, spawn, points, categories, availableByCategory
                );

                museums.put(id, r);
                ok++;
            } catch (Exception ex) {
                fail++;
                log.log(Level.WARNING, "Failed loading museum " + key, ex);
            }
        }
        dbg("loadMuseums: loaded=%d, failed=%d", ok, fail);
    }

    public void updateMuseumRegion(UUID playerId, MuseumRegion r) {
        museums.put(playerId, r);
        saveMuseums();
    }

    /* ==================== API ==================== */

    public void putMuseum(UUID key, MuseumRegion region) {
        dbg(
                "putMuseum: key=%s region[min=%s max=%s center=%s world=%s]",
                key, vec(region.getMin()), vec(region.getMax()), vec(region.getCenter()), region.getWorldName()
        );
        museums.put(key, region);
        saveMuseums();
    }

    public MuseumRegion getMuseum(UUID key) {
        MuseumRegion r = museums.get(key);
        dbg("getMuseum: key=%s => %s", key, (r == null ? "null" : "found"));
        return r;
    }

    public Optional<MuseumRegion> findByLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) return Optional.empty();
        String w = loc.getWorld().getName();
        for (MuseumRegion r : museums.values()) {
            if (!r.getWorldName().equals(w)) continue;
            if (r.isInside(loc)) return Optional.of(r);
        }
        return Optional.empty();
    }

    public Collection<MuseumRegion> getAllMuseums() {
        return museums.values();
    }

    public World getMuseumsWorld() {
        return world;
    }

    public Location getMuseumSpawn(UUID playerId) {
        MuseumRegion r = museums.get(playerId);
        return r == null ? null : r.getSpawnLocation();
    }

    /** AABB intersection test with horizontal padding (XZ only). */
    public static boolean intersectsWithPaddingXZ(MuseumRegion a, MuseumRegion b, int padXZ) {
        int aMinX = a.getMin().x() - padXZ, aMaxX = a.getMax().x() + padXZ;
        int aMinZ = a.getMin().z() - padXZ, aMaxZ = a.getMax().z() + padXZ;
        int bMinX = b.getMin().x() - padXZ, bMaxX = b.getMax().x() + padXZ;
        int bMinZ = b.getMin().z() - padXZ, bMaxZ = b.getMax().z() + padXZ;

        boolean xOverlap = aMinX <= bMaxX && aMaxX >= bMinX;
        boolean zOverlap = aMinZ <= bMaxZ && aMaxZ >= bMinZ;
        return xOverlap && zOverlap;
    }

    private boolean collidesAny(MuseumRegion candidate, int padXZ) {
        for (MuseumRegion ex : getAllMuseums()) {
            if (intersectsWithPaddingXZ(candidate, ex, padXZ)) {
                dbg(
                        "collidesAny: collision with region owner=%s min=%s max=%s",
                        ex.getOwner(), vec(ex.getMin()), vec(ex.getMax())
                );
                return true;
            }
        }
        return false;
    }

    private static boolean aabbInsideBorder(MuseumRegion r, WorldBorder wb) {
        double bx = wb.getCenter().getX();
        double bz = wb.getCenter().getZ();
        double half = wb.getSize() / 2.0;
        int minX = r.getMin().x(), maxX = r.getMax().x();
        int minZ = r.getMin().z(), maxZ = r.getMax().z();

        return minX >= bx - half && maxX <= bx + half
                && minZ >= bz - half && maxZ <= bz + half;
    }

    private boolean getWorld() {
        String worldName = plugin.getConfig().getString("default_world");
        if (worldName == null) {
            log.severe("Config worldName not found!");
            return false;
        }
        World w = Bukkit.getWorld(worldName);
        if (w == null) {
            log.severe("World '" + worldName + "' is not loaded!");
            return false;
        }
        this.world = w;
        dbg("getWorld: using world=%s", worldName);
        return true;
    }

    /* ==================== GRID USING REAL SPACING ==================== */

    private static int sizeX(Clipboard cp) {
        BlockVector3 min = cp.getRegion().getMinimumPoint();
        BlockVector3 max = cp.getRegion().getMaximumPoint();
        return (max.x() - min.x() + 1);
    }

    private static int sizeZ(Clipboard cp) {
        BlockVector3 min = cp.getRegion().getMinimumPoint();
        BlockVector3 max = cp.getRegion().getMaximumPoint();
        return (max.z() - min.z() + 1);
    }

    private int clampMuseumY(World w, int y) {
        int min = w.getMinHeight();
        int max = w.getMaxHeight() - 1;
        return Math.max(min, Math.min(max, y));
    }

    /** First museum centered near (0,0), using fixed Y from config. */
    private Location firstCenterForClipboard(Clipboard cp, World w) {
        int x = 0, z = 0;
        return new Location(w, x + 0.5, clampMuseumY(w, museumCenterY), z + 0.5);
    }

    /** Square-spiral grid around spawn. Index 0 at (0,0). Uses fixed Y from config. */
    private Location gridCenterForIndex(Clipboard cp, World w, int index, int spacingX, int spacingZ) {
        if (index <= 0) return firstCenterForClipboard(cp, w);

        int k = (int) Math.ceil((Math.sqrt(index + 1) - 1) / 2.0);
        int t = 2 * k + 1;
        int m = t * t;
        int d = m - (index + 1);

        int side = t - 1;
        int kx, kz;
        if (d < side) {
            kx = k;
            kz = -k + d;
        } else if ((d -= side) < side) {
            kx = k - d;
            kz = k;
        } else if ((d -= side) < side) {
            kx = -k;
            kz = k - d;
        } else {
            kx = -k + d - side;
            kz = -k;
        }

        int x = kx * spacingX;
        int z = kz * spacingZ;

        return new Location(w, x + 0.5, clampMuseumY(w, museumCenterY), z + 0.5);
    }

    /* ==================== COMMAND EXPANSION ==================== */

    public static String expandPlaceholders(String cmd, Location loc) {
        return expandPlaceholders(cmd, loc, null, null, null);
    }

    public static String expandPlaceholders(String cmd, Location loc, String playerName) {
        return expandPlaceholders(cmd, loc, null, null, playerName);
    }

    public static String expandPlaceholders(String cmd, Location loc, Float yaw, Float pitch, String playerName) {
        if (cmd == null) return "";
        String out = cmd
                .replace("%world%", loc.getWorld().getName())
                .replace("%x%", String.format(java.util.Locale.US, "%.3f", loc.getX()))
                .replace("%y%", String.format(java.util.Locale.US, "%.3f", loc.getY()))
                .replace("%z%", String.format(java.util.Locale.US, "%.3f", loc.getZ()))
                .replace("%bx%", Integer.toString(loc.getBlockX()))
                .replace("%by%", Integer.toString(loc.getBlockY()))
                .replace("%bz%", Integer.toString(loc.getBlockZ()));

        if (yaw != null) out = out.replace("%yaw%", String.format(java.util.Locale.US, "%.3f", yaw));
        if (pitch != null) out = out.replace("%pitch%", String.format(java.util.Locale.US, "%.3f", pitch));
        if (playerName != null) out = out.replace("%player%", playerName);
        return out;
    }

    private void runNamedOffsetCommands(Location center) {
        if (namedOffsets.isEmpty()) return;

        List<String> toRun = new ArrayList<>();

        for (OffsetSpec spec : namedOffsets.values()) {
            if (!spec.runOnCreate()) continue;
            if (spec.commands().isEmpty()) continue;

            Location at = center.clone().add(
                    spec.offset().getX(),
                    spec.offset().getY(),
                    spec.offset().getZ()
            );

            for (String raw : spec.commands()) {
                if (raw == null || raw.isEmpty()) continue;

                String expanded = expandPlaceholders(raw, at);
                toRun.add(expanded);

                dbg(
                        "Auto offset '%s' resolved at %s %.3f %.3f %.3f -> %s",
                        spec.key(),
                        at.getWorld().getName(),
                        at.getX(),
                        at.getY(),
                        at.getZ(),
                        expanded
                );
            }
        }

        if (toRun.isEmpty()) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (String cmd : toRun) {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                } catch (Throwable t) {
                    log.log(Level.WARNING, "Failed to run command: " + cmd, t);
                }
            }
        });
    }

    /**
     * Spawnează hologramele configurate pe muzeu, la "center + offset".
     *
     * Suportă 2 stiluri de config:
     *  1) offsets.<Cheie> cu "lines:" direct (ex: offsets.Bugs.lines)
     *  2) offsets.holograms.<id> cu "lines:" (ex: offsets.holograms.h1.lines)
     *
     * Numele sunt unice per muzeu, ca să nu se bată între ele.
     */
    private void spawnMuseumHolograms(UUID museumId, Location center) {
        if (museumId == null || center == null) return;

        ConfigurationSection offsets = plugin.getConfig().getConfigurationSection("offsets");
        if (offsets == null) return;

        // 1) offsets.holograms.*
        ConfigurationSection holos = offsets.getConfigurationSection("holograms");
        if (holos != null) {
            for (String holoKey : holos.getKeys(false)) {
                ConfigurationSection hs = holos.getConfigurationSection(holoKey);
                if (hs == null) continue;

                String hologramName = ("museum_" + museumId + "_holo_" + holoKey)
                        .toLowerCase(Locale.ROOT);

                HologramUtil.spawnOffsetHologram(center, hs, hologramName);
            }
        }

        // 2) offsets.<Key> care are "lines" direct (ex: offsets.Bugs)
        for (String key : offsets.getKeys(false)) {
            if ("player_spawn".equalsIgnoreCase(key)) continue;
            if ("doors".equalsIgnoreCase(key)) continue;
            if ("holograms".equalsIgnoreCase(key)) continue;

            ConfigurationSection hs = offsets.getConfigurationSection(key);
            if (hs == null) continue;

            List<String> lines = hs.getStringList("lines");
            if (lines == null || lines.isEmpty()) continue;

            String hologramName = ("museum_" + museumId + "_" + key + "_holo")
                    .toLowerCase(Locale.ROOT);

            HologramUtil.spawnOffsetHologram(center, hs, hologramName);
        }
    }

    /* ==================== CREATE ==================== */

    public boolean createMuseum(UUID playerID) {
        if (getMuseum(playerID) != null) {
            return false;
        }

        Clipboard clipboard = plugin.getSchematicsManager().getSchematic(schematicName);
        if (clipboard == null) {
            log.severe("[Museums] Schematic not found: " + schematicName);
            return false;
        }

        int spacingX = sizeX(clipboard) + distanceBetween;
        int spacingZ = sizeZ(clipboard) + distanceBetween;

        int index = museums.size();
        Location center = gridCenterForIndex(clipboard, world, index, spacingX, spacingZ);

        preloadChunksAround(center, 2);

        pasteClipboardCenteredAsync(clipboard, center, true, () -> {
            try {
                MuseumRegion region = computePastedMuseum(playerID, clipboard, center);

                if (!verifyMuseumAtLocation(region)) {
                    log.severe("[Museums] Paste verification failed at " + loc(center));
                    return;
                }

                putMuseum(playerID, region);
                refreshLightingForRegion(region);
                runNamedOffsetCommands(center);
                spawnMuseumHolograms(playerID, center);

                dbg("Museum created for %s at %s", playerID, loc(center));
            } catch (Throwable t) {
                log.log(Level.SEVERE, "[Museums] Create failed for " + playerID, t);
            }
        });

        return true;
    }

    public Location resolveDoorTeleport(MuseumRegion region, DoorSpec door) {
        if (region == null || door == null || door.target() == null) {
            return null;
        }

        TeleportTarget t = door.target();

        World targetWorld = (t.world() != null && !t.world().isBlank())
                ? Bukkit.getWorld(t.world())
                : Bukkit.getWorld(region.getWorldName());

        if (targetWorld == null) {
            return null;
        }

        Location out;

        // relative to the museum's logical center, not an absolute point
        if (t.relativeToSpawn()) {
            BlockVector3 museumCenter = region.getCenter();

            out = new Location(
                    targetWorld,
                    museumCenter.x() + 0.5 + t.x(),
                    museumCenter.y() + t.y(),
                    museumCenter.z() + 0.5 + t.z()
            );
        } else {
            // absolute coordinates
            out = new Location(
                    targetWorld,
                    t.x(),
                    t.y(),
                    t.z()
            );
        }

        if (t.yaw() != null) {
            out.setYaw(t.yaw());
        }

        if (t.pitch() != null) {
            out.setPitch(t.pitch());
        }

        return out;
    }

    /* ==================== PRETTY ==================== */

    private static String vec(BlockVector3 v) {
        return String.format("(%d,%d,%d)", v.x(), v.y(), v.z());
    }

    private static String loc(Location l) {
        return String.format("%s@(%d,%d,%d)", l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }
}