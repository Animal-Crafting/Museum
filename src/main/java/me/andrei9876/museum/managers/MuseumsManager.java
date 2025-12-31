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
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MuseumsManager {
    private final Museum plugin;
    private final Logger log;
    private final boolean debug;

    public final Map<UUID, MuseumRegion> museums = new HashMap<>();
    private final Map<UUID, Location> pendingMuseums = new HashMap<>();

    private World world;
    private String schematicName;
    /** Extra gap between museums; real spacing = schematic size + this */
    private int distanceBetween;
    /** Player spawn offset (from center) */
    private Vector spawnOffset = new Vector(0, 0, 0);

    /** Other named offsets with optional commands (from config: offsets.* except player_spawn) */
    private final Map<String, OffsetSpec> namedOffsets = new LinkedHashMap<>();

    /**
     * @param offset   relative to museum center
     * @param commands run after paste at this offset
     */
    private record OffsetSpec(String key, Vector offset, List<String> commands) {
            private OffsetSpec(String key, Vector offset, List<String> commands) {
                this.key = key;
                this.offset = offset;
                this.commands = commands == null ? Collections.emptyList() : new ArrayList<>(commands);
            }
        }

    public MuseumsManager(Museum plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.debug = plugin.getConfig().getBoolean("debug", false);

        dbg("Initializing MuseumsManager...");
        loadMuseums();
        if (!getWorld()) {
            log.severe("[Museums] World acquisition failed. Disabling plugin.");
            plugin.getServer().getPluginManager().disablePlugin(plugin);
            return;
        }
        schematicName   = plugin.getConfig().getString("schematic_name");
        distanceBetween = plugin.getConfig().getInt("distance_between", 48);

        // Load offsets (player_spawn + any other named offsets)
        loadOffsetsFromConfig();

        dbg("Config loaded: schematic_name=%s, distance_between=%d, spawnOffset=(%.3f,%.3f,%.3f), namedOffsets=%d",
                schematicName, distanceBetween, spawnOffset.getX(), spawnOffset.getY(), spawnOffset.getZ(), namedOffsets.size());
    }

    /* ==================== CONFIG: OFFSETS ==================== */

    @SuppressWarnings("unchecked")
    private void loadOffsetsFromConfig() {
        namedOffsets.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("offsets");
        if (root == null) return;

        for (String key : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) continue;
            double x = sec.getDouble("x", 0.0);
            double y = sec.getDouble("y", 0.0);
            double z = sec.getDouble("z", 0.0);
            List<String> commands = sec.getStringList("commands");

            if ("player_spawn".equalsIgnoreCase(key)) {
                spawnOffset = new Vector(x, y, z);
                dbg("Loaded player_spawn: (%.3f,%.3f,%.3f)", x, y, z);
            } else {
                namedOffsets.put(key, new OffsetSpec(key, new Vector(x, y, z), commands));
                dbg("Loaded offset '%s': (%.3f,%.3f,%.3f), commands=%d", key, x, y, z, commands == null ? 0 : commands.size());
            }
        }
    }

    /* ==================== DEBUG ==================== */

    private void dbg(String msg, Object... args) {
        if (debug) log.info("[Museums:DEBUG] " + String.format(msg, args));
    }

    /* ==================== PASTE ==================== */

    public void pasteClipboardCenteredAsync(Clipboard clipboard,
                                            Location centerLoc,
                                            boolean ignoreAir,
                                            Runnable onDone) {
        long t0 = System.currentTimeMillis();
        if (clipboard == null || centerLoc == null || centerLoc.getWorld() == null) {
            dbg("pasteClipboardCenteredAsync: invalid input (clipboard=%s, centerLoc=%s)", clipboard, centerLoc);
            return;
        }

        // Capture primitives for async use
        final String worldName = centerLoc.getWorld().getName();
        final int cx = centerLoc.getBlockX();
        final int cy = centerLoc.getBlockY();
        final int cz = centerLoc.getBlockZ();

        // Compute centered target (pure WE math)
        BlockVector3 min = clipboard.getRegion().getMinimumPoint();
        BlockVector3 max = clipboard.getRegion().getMaximumPoint();
        BlockVector3 clipCenter = BlockVector3.at(
                (min.x() + max.x()) / 2,
                (min.y() + max.y()) / 2,
                (min.z() + max.z()) / 2
        );
        BlockVector3 origin = clipboard.getOrigin();
        BlockVector3 offset = origin.subtract(clipCenter);
        BlockVector3 worldTarget = BlockVector3.at(cx, cy, cz).add(offset);

        dbg("pasteClipboardCentered: min=%s max=%s origin=%s clipCenter=%s offset=%s worldTarget=%s",
                vec(min), vec(max), vec(origin), vec(clipCenter), vec(offset), vec(worldTarget));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                com.sk89q.worldedit.world.World weWorld = FaweAPI.getWorld(worldName);
                if (weWorld == null) {
                    log.warning("[Museums] FAWE world not found: " + worldName);
                    return;
                }

                try (EditSession es = WorldEdit.getInstance()
                        .newEditSessionBuilder()
                        .world(weWorld)
                        .fastMode(true)
                        .build()) {

                    Operation op = new ClipboardHolder(clipboard)
                            .createPaste(es)
                            .to(worldTarget)
                            .ignoreAirBlocks(ignoreAir)
                            .build();

                    // Blocks the worker thread, not the server thread
                    Operations.complete(op);
                    es.flushQueue();
                }

                dbg("pasteClipboardCentered: paste completed in %d ms (async)", System.currentTimeMillis() - t0);

                if (onDone != null) {
                    Bukkit.getScheduler().runTask(plugin, onDone); // optional post-step on main thread
                }
            } catch (Throwable ex) {
                log.log(Level.SEVERE, "[Museums] Async paste failed at " + worldTarget + " for " + clipboard, ex);
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

        dbg("computePastedMuseum: regMin=%s regMax=%s origin=%s clipCenter=%s delta=%s => pastedMin=%s pastedMax=%s spawn=%.3f/%.3f/%.3f points=%d",
                vec(regMin), vec(regMax), vec(origin), vec(clipCenter), vec(delta), vec(pastedMin), vec(pastedMax),
                spawn.getX(), spawn.getY(), spawn.getZ(), points.size());

        return new MuseumRegion(owner, centerLoc.getWorld().getName(), pastedMin, pastedMax, worldCenter, spawn, points);
    }

    /* ==================== STORAGE ==================== */

    public void saveMuseums() {
        dbg("saveMuseums: saving %d entries...", museums.size());
        plugin.getConfig().set("museums", null);
        ConfigurationSection root = plugin.getConfig().createSection("museums");

        for (Map.Entry<UUID, MuseumRegion> e : museums.entrySet()) {
            String key = e.getKey().toString();
            MuseumRegion r = e.getValue();

            ConfigurationSection sec = root.createSection(key);
            sec.set("world", r.getWorldName());
            sec.set("owner", r.getOwner().toString());
            sec.set("min", Arrays.asList(r.getMin().x(), r.getMin().y(), r.getMin().z()));
            sec.set("max", Arrays.asList(r.getMax().x(), r.getMax().y(), r.getMax().z()));
            sec.set("center", Arrays.asList(r.getCenter().x(), r.getCenter().y(), r.getCenter().z()));
            sec.set("spawn", Arrays.asList(r.getSpawn().getX(), r.getSpawn().getY(), r.getSpawn().getZ()));

            // points (absolute) for named offsets
            if (!r.getPoints().isEmpty()) {
                ConfigurationSection pts = sec.createSection("points");
                for (Map.Entry<String, org.bukkit.util.Vector> p : r.getPoints().entrySet()) {
                    org.bukkit.util.Vector v = p.getValue();
                    pts.set(p.getKey(), Arrays.asList(v.getX(), v.getY(), v.getZ()));
                }
            }

            // categories (ordered list)
            if (!r.getCategories().isEmpty()) {
                sec.set("categories", new ArrayList<>(r.getCategories()));
            }

            // availableByCategory (map: category -> ordered list of values)
            if (!r.getAvailableByCategory().isEmpty()) {
                ConfigurationSection av = sec.createSection("availableByCategory");
                for (Map.Entry<String, List<String>> entry : r.getAvailableByCategory().entrySet()) {
                    // copy to ensure a plain mutable list gets written
                    av.set(entry.getKey(), new ArrayList<>(entry.getValue()));
                }
            }
        }

        plugin.saveConfig();
        dbg("saveMuseums: done.");
    }

    @SuppressWarnings("unchecked")
    private void loadMuseums() {
        museums.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("museums");
        if (root == null) { dbg("loadMuseums: no stored museums."); return; }

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
                List<Number> spL  = (List<Number>) sec.getList("spawn");

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
                    // fallback based on current spawnOffset (center + offset)
                    spawn = new Vector(
                            center.x() + spawnOffset.getX(),
                            center.y() + spawnOffset.getY(),
                            center.z() + spawnOffset.getZ()
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

    public void updateMuseumRegion(UUID playerId, MuseumRegion r){
        museums.put(playerId, r);
        saveMuseums();
    }

    /* ==================== API ==================== */

    public void putMuseum(UUID key, MuseumRegion region) {
        dbg("putMuseum: key=%s region[min=%s max=%s center=%s world=%s]",
                key, vec(region.getMin()), vec(region.getMax()), vec(region.getCenter()), region.getWorldName());
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
                dbg("collidesAny: collision with region owner=%s min=%s max=%s",
                        ex.getOwner(), vec(ex.getMin()), vec(ex.getMax()));
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

    /** First museum centered near (0,0), Y adapted to terrain. */
    private Location firstCenterForClipboard(Clipboard cp, World w) {
        int x = 0, z = 0;
        int groundY = w.getHighestBlockYAt(x, z);
        BlockVector3 regMin = cp.getRegion().getMinimumPoint();
        BlockVector3 regMax = cp.getRegion().getMaximumPoint();
        BlockVector3 clipCenter = BlockVector3.at(
                (regMin.x() + regMax.x()) / 2,
                (regMin.y() + regMax.y()) / 2,
                (regMin.z() + regMax.z()) / 2
        );
        int centerY = groundY - regMin.y() + clipCenter.y();
        return new Location(w, x + 0.5, centerY, z + 0.5);
    }

    /** Square-spiral grid around spawn. Index 0 at (0,0). */
    private Location gridCenterForIndex(Clipboard cp, World w, int index, int spacingX, int spacingZ) {
        if (index <= 0) return firstCenterForClipboard(cp, w);

        int k = (int)Math.ceil((Math.sqrt(index + 1) - 1) / 2.0);
        int t = 2 * k + 1;
        int m = t * t;
        int d = m - (index + 1);

        int side = t - 1;
        int kx, kz;
        if (d < side) { kx =  k;      kz = -k + d; }
        else if ((d -= side) < side) { kx =  k - d;  kz =  k; }
        else if ((d -= side) < side) { kx = -k;      kz =  k - d; }
        else                         { kx = -k + d - side; kz = -k; }

        int x = kx * spacingX;
        int z = kz * spacingZ;

        int groundY = w.getHighestBlockYAt(x, z);
        BlockVector3 regMin = cp.getRegion().getMinimumPoint();
        BlockVector3 regMax = cp.getRegion().getMaximumPoint();
        BlockVector3 clipCenter = BlockVector3.at(
                (regMin.x() + regMax.x()) / 2,
                (regMin.y() + regMax.y()) / 2,
                (regMin.z() + regMax.z()) / 2
        );
        int centerY = groundY - regMin.y() + clipCenter.y();
        return new Location(w, x + 0.5, centerY, z + 0.5);
    }

    /* ==================== COMMAND EXPANSION ==================== */

    private static String expandPlaceholders(String cmd, Location loc) {
        return cmd
                .replace("%world%", loc.getWorld().getName())
                .replace("%x%", String.format(java.util.Locale.US, "%.3f", loc.getX()))
                .replace("%y%", String.format(java.util.Locale.US, "%.3f", loc.getY()))
                .replace("%z%", String.format(java.util.Locale.US, "%.3f", loc.getZ()))
                .replace("%bx%", Integer.toString(loc.getBlockX()))
                .replace("%by%", Integer.toString(loc.getBlockY()))
                .replace("%bz%", Integer.toString(loc.getBlockZ()));
    }

    private void runNamedOffsetCommands(Location center) {
        if (namedOffsets.isEmpty()) return;
        List<String> toRun = new ArrayList<>();
        for (OffsetSpec spec : namedOffsets.values()) {
            Location at = center.clone().add(spec.offset.getX(), spec.offset.getY(), spec.offset.getZ());
            for (String raw : spec.commands) {
                if (raw == null || raw.isEmpty()) continue;
                String expanded = expandPlaceholders(raw, at);
                toRun.add(expanded);
                dbg("Offset '%s' -> %s", spec.key, expanded);
            }
        }
        if (toRun.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (String cmd : toRun) {
                try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd); }
                catch (Throwable t) { log.log(Level.WARNING, "Failed to run command: " + cmd, t); }
            }
        });
    }

    /* ==================== CREATE ==================== */

    public boolean createMuseum(UUID playerID) {
        dbg("createMuseum: player=%s", playerID);

        // Already has one?
        if (getMuseum(playerID) != null || isPending(playerID)) {
            dbg("createMuseum: player already has a museum or one is pending: %s", playerID);
            return false;
        }

        // Load schematic
        final Clipboard clipboard = plugin.getSchematicsManager().getSchematic(schematicName);
        if (clipboard == null) {
            log.severe("[Museums] Schematic not found! name=" + schematicName);
            return false;
        }

        // Compute spacing from schematic extents + configured gap
        int schemX = sizeX(clipboard);
        int schemZ = sizeZ(clipboard);
        int spacingX = schemX + distanceBetween;
        int spacingZ = schemZ + distanceBetween;
        dbg("createMuseum: schem sizeX=%d sizeZ=%d -> spacingX=%d spacingZ=%d", schemX, schemZ, spacingX, spacingZ);

        // Determine initial index safely
        final int baseIdx;
        synchronized (museums) {
            baseIdx = museums.size();
        }

        // Pick a center that doesn't collide
        Location center;
        if (baseIdx == 0) {
            center = firstCenterForClipboard(clipboard, world); // start near (0,0) by your policy
        } else {
            center = gridCenterForIndex(clipboard, world, baseIdx, spacingX, spacingZ);
            MuseumRegion candidate = computePastedMuseum(playerID, clipboard, center);

            // bump grid if colliding
            final int pad = 0; // keep if you want extra margin
            int bumps = 0;
            while (collidesAny(candidate, pad) && bumps < 32) {
                bumps++;
                spacingX += Math.max(8, distanceBetween / 2);
                spacingZ += Math.max(8, distanceBetween / 2);
                center = gridCenterForIndex(clipboard, world, baseIdx + bumps, spacingX, spacingZ);
                candidate = computePastedMuseum(playerID, clipboard, center);
                dbg("createMuseum: collision -> bump #%d new spacingX=%d spacingZ=%d", bumps, spacingX, spacingZ);
            }
            if (collidesAny(candidate, pad)) {
                log.severe("[Museums] Failed to place museum on grid without collision.");
                return false;
            }
        }

        // Reserve immediately to avoid concurrent reuse of the same slot
        reservePending(playerID, center);

        // (Optional) Preload a ring of chunks on main thread
        preloadChunksAround(center, 2);

        final Location finalCenter = center;
        final boolean ignoreAir = true; // or read from config if you want this configurable

        // Kick off async FAWE paste (no teleports, no player touches)
        pasteClipboardCenteredAsync(clipboard, finalCenter, ignoreAir, () -> {
            // back on main thread after paste
            try {
                MuseumRegion region = computePastedMuseum(playerID, clipboard, finalCenter);
                putMuseum(playerID, region);

                // Optional: relight this region if you really need it
                refreshLightingForRegion(region);

                // Execute any offset-defined commands (Loc1/Loc2...) now that blocks exist
                runNamedOffsetCommands(finalCenter);

                dbg("createMuseum: DONE for player=%s at center=%s region[min=%s max=%s]",
                        playerID, loc(finalCenter), vec(region.getMin()), vec(region.getMax()));
            } catch (Throwable t) {
                log.log(Level.SEVERE, "[Museums] Post-paste finalize failed for " + playerID, t);
            } finally {
                clearPending(playerID);
            }
        });

        return true; // queued successfully
    }

    private void reservePending(UUID playerID, Location center) {
        synchronized (pendingMuseums) {
            pendingMuseums.put(playerID, center);
        }
    }

    private void clearPending(UUID playerID) {
        synchronized (pendingMuseums) {
            pendingMuseums.remove(playerID);
        }
    }

    public boolean isPending(UUID playerID) {
        synchronized (pendingMuseums) {
            return pendingMuseums.containsKey(playerID);
        }
    }

    /* ==================== PRETTY ==================== */

    private static String vec(BlockVector3 v) {
        return String.format("(%d,%d,%d)", v.x(), v.y(), v.z());
    }

    private static String loc(Location l) {
        return String.format("%s@(%d,%d,%d)", l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }
}
