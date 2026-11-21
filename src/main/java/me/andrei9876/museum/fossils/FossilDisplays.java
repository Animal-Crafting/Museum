package me.andrei9876.museum.fossils;

import me.andrei9876.museum.Museum;
import me.andrei9876.museum.models.MuseumRegion;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.event.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;
import java.util.function.BiConsumer;

/** Reconciles display state without ever modifying museum donation records. Main-thread only. */
public final class FossilDisplays implements Listener {
    private record ChunkKey(String world, int x, int z) {}
    private record Exhibit(MuseumRegion museum, FossilDefinition fossil, double x, double y, double z) {
        Location location(World world) { return new Location(world, x, y, z, fossil.yaw(), fossil.pitch()); }
        Set<String> parts() { return FossilProgress.parts(museum.getValue("Fossils"), fossil.id()); }
    }

    private final Museum plugin;
    private final NamespacedKey ownerKey;
    private final NamespacedKey fossilKey;
    private final Map<String, FossilDefinition> definitions = new LinkedHashMap<>();
    private final Map<ChunkKey, List<Exhibit>> byChunk = new HashMap<>();
    private final Map<ChunkKey, List<Exhibit>> retiredByChunk = new HashMap<>();
    private final Map<UUID, List<Exhibit>> byOwner = new HashMap<>();
    private final Set<String> warnings = new HashSet<>();
    private final Map<String, String> definitionErrors = new HashMap<>();
    private String integrationProblem;
    private FossilBridge bridge;
    private BukkitTask retryTask;

    public FossilDisplays(Museum plugin) {
        this.plugin = plugin;
        ownerKey = new NamespacedKey(plugin, "fossil_owner");
        fossilKey = new NamespacedKey(plugin, "fossil_id");
        File file = new File(plugin.getDataFolder(), "fossil-stages.yml");
        if (!file.exists()) plugin.saveResource("fossil-stages.yml", false);
        YamlConfiguration mappings = new YamlConfiguration();
        try { mappings.load(file); }
        catch (Exception ex) {
            integrationProblem = "Cannot read fossil-stages.yml: " + ex.getMessage();
            warn(integrationProblem);
            return;
        }
        ConfigurationSection placements = plugin.getConfig().getConfigurationSection("offsets.Fossils");
        if (placements != null) for (String id : placements.getKeys(false)) {
            ConfigurationSection placement = placements.getConfigurationSection(id);
            if (placement == null || !placement.getBoolean("staged", false)) continue;
            try { definitions.put(id, FossilDefinition.read(id, placement, mappings.getConfigurationSection("fossils." + id))); }
            catch (IllegalArgumentException ex) { definitionErrors.put(id, ex.getMessage()); warn(ex.getMessage()); }
        }
        for (MuseumRegion museum : plugin.getMuseumsManager().getAllMuseums()) index(museum);
        if (Bukkit.getPluginManager().isPluginEnabled("MythicMobs")
                && Bukkit.getPluginManager().isPluginEnabled("ModelEngine")) {
            try { bridge = new MythicModelBridge(plugin, this); }
            catch (LinkageError | RuntimeException ex) { integrationProblem = "Integration unavailable: " + ex; warn(integrationProblem); }
        } else {
            integrationProblem = "Staged fossil rendering requires MythicMobs 5.12.0 and ModelEngine 4.1.0. Donations remain saved.";
            warn(integrationProblem);
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (bridge != null) {
            retryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::reconcileLoaded, 1L, 100L);
        }
    }

    private void index(MuseumRegion museum) {
        if (byOwner.containsKey(museum.getOwner())) return;
        List<Exhibit> exhibits = new ArrayList<>();
        Vector base = museum.getPoint("Fossils");
        double x = base == null ? museum.getCenter().x() : base.getX();
        double y = base == null ? museum.getCenter().y() : base.getY();
        double z = base == null ? museum.getCenter().z() : base.getZ();
        for (FossilDefinition fossil : definitions.values()) {
            Exhibit exhibit = new Exhibit(museum, fossil, x + fossil.x(), y + fossil.y(), z + fossil.z());
            exhibits.add(exhibit);
            byChunk.computeIfAbsent(chunkKey(exhibit), key -> new ArrayList<>()).add(exhibit);
        }
        byOwner.put(museum.getOwner(), exhibits);
        FossilDefinition pteranodon = definitions.get("Pteranodon");
        if (pteranodon != null) {
            Exhibit retired = new Exhibit(museum, pteranodon, x + 6.5, y - 19.0, z - 10.0);
            retiredByChunk.computeIfAbsent(chunkKey(retired), key -> new ArrayList<>()).add(retired);
        }
    }

    private static ChunkKey chunkKey(Exhibit exhibit) {
        return new ChunkKey(exhibit.museum.getWorldName(), Location.locToBlock(exhibit.x) >> 4,
                Location.locToBlock(exhibit.z) >> 4);
    }

    public void refresh(MuseumRegion museum, String fossil) {
        index(museum);
        if (bridge == null) return;
        for (Exhibit exhibit : byOwner.get(museum.getOwner())) {
            if (!exhibit.fossil.id().equals(fossil)) continue;
            reconcile(exhibit);
            // MythicMobs' onLoad model attachment occurs at tick 20.
            Bukkit.getScheduler().runTaskLater(plugin, () -> reconcile(exhibit), 25L);
        }
    }

    public boolean configured(String id) {
        return plugin.getConfig().getBoolean("offsets.Fossils." + id + ".staged", false);
    }

    /** Read-only inspection: no spawns, visibility changes, chunk loads, or donation writes. */
    public List<String> diagnose(MuseumRegion museum, String requestedId) {
        String id = requestedId.equals("Pteranodon2") ? "Pteranodon" : requestedId;
        List<String> lines = new ArrayList<>();
        lines.add("Museum " + plugin.getDescription().getVersion() + " | fossil " + id);
        for (String name : List.of("MythicMobs", "ModelEngine")) {
            var installed = Bukkit.getPluginManager().getPlugin(name);
            lines.add(name + ": " + (installed == null ? "NOT INSTALLED"
                    : installed.getDescription().getVersion() + ", enabled=" + installed.isEnabled()));
        }
        List<String> values = museum.getValue("Fossils");
        lines.add("Saved fossil tokens: " + (values == null ? List.of() : values.stream()
                .filter(v -> v.equals(id) || v.startsWith(id + "@") || (id.equals("Pteranodon")
                        && (v.equals("Pteranodon2") || v.startsWith("Pteranodon2@")))).toList()));
        lines.add("Effective donated pieces: " + FossilProgress.parts(values, id));
        var placement = plugin.getConfig().getConfigurationSection("offsets.Fossils." + id);
        if (placement == null) { lines.add("ERROR: fossil placement not configured (IDs are case-sensitive)."); return lines; }
        lines.add("staged=" + placement.getBoolean("staged", false) + ", armorStand=" + placement.getBoolean("armorStand", false));
        if (!configured(id)) lines.add("STAGING DISABLED: existing config.yml needs staged: true, mob, model and yaw fields, then restart.");
        if (integrationProblem != null) lines.add("Integration error: " + integrationProblem);
        FossilDefinition fossil = definitions.get(id);
        if (fossil == null) {
            lines.add("No loaded stage definition: " + definitionErrors.getOrDefault(id,
                    "staging is disabled or fossil-stages.yml could not load"));
            return lines;
        }
        lines.add("mob=" + fossil.mob() + ", model=" + fossil.model());
        Vector base = museum.getPoint("Fossils");
        Exhibit exhibit = new Exhibit(museum, fossil,
                (base == null ? museum.getCenter().x() : base.getX()) + fossil.x(),
                (base == null ? museum.getCenter().y() : base.getY()) + fossil.y(),
                (base == null ? museum.getCenter().z() : base.getZ()) + fossil.z());
        lines.add("Exhibit: " + museum.getWorldName() + " " + exhibit.x + " " + exhibit.y + " " + exhibit.z
                + " yaw=" + fossil.yaw());
        if (bridge == null) { lines.add("Rendering integration: UNAVAILABLE"); return lines; }
        try {
            lines.addAll(bridge.diagnose(fossil, null));
            World world = Bukkit.getWorld(museum.getWorldName());
            if (world == null) { lines.add("World: NOT LOADED"); return lines; }
            ChunkKey key = chunkKey(exhibit);
            if (!world.isChunkLoaded(key.x, key.z)) { lines.add("Exhibit chunk: NOT LOADED (visit the exhibit first)"); return lines; }
            Chunk chunk = world.getChunkAt(key.x, key.z);
            if (!chunk.isEntitiesLoaded()) { lines.add("Chunk entities: NOT LOADED"); return lines; }
            List<Entity> tagged = new ArrayList<>(), legacy = new ArrayList<>();
            int unidentified = 0;
            for (Entity entity : chunk.getEntities()) {
                String owner = entity.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
                String storedId = entity.getPersistentDataContainer().get(fossilKey, PersistentDataType.STRING);
                if (museum.getOwner().toString().equals(owner) && id.equals(storedId)) tagged.add(entity);
                else if (owner == null && storedId == null && entity.getLocation().distanceSquared(exhibit.location(world)) < 0.0625) {
                    if (fossil.mob().equals(bridge.mobType(entity))) legacy.add(entity);
                    else if (entity instanceof org.bukkit.entity.ArmorStand) unidentified++;
                }
            }
            lines.add("Entities: managed=" + tagged.size() + ", matching legacy=" + legacy.size()
                    + ", unidentified/other armor stands at exhibit=" + unidentified);
            List<Entity> found = new ArrayList<>(tagged);
            found.addAll(legacy);
            if (found.isEmpty()) lines.add("No fossil entity found. Refresh and inspect console errors.");
            for (Entity entity : found.stream().limit(3).toList()) lines.addAll(bridge.diagnose(fossil, entity));
            if (tagged.size() > 1 || legacy.size() > 1 || (!tagged.isEmpty() && !legacy.isEmpty()) || unidentified > 0)
                lines.add("Entity ambiguity/blocker detected. Inspect before removing anything.");
        } catch (RuntimeException | LinkageError ex) { lines.add("DIAGNOSTIC ERROR: " + ex); }
        return lines;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        ChunkKey key = new ChunkKey(event.getWorld().getName(), event.getChunk().getX(), event.getChunk().getZ());
        // Defer until MythicMobs has restored its ActiveMob records for these entities.
        Bukkit.getScheduler().runTaskLater(plugin, () -> reconcileChunk(key), 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> reconcileChunk(key), 25L);
    }

    private void reconcileLoaded() {
        // Never obtain an unloaded chunk or force-load a museum.
        for (World world : Bukkit.getWorlds()) for (Chunk chunk : world.getLoadedChunks()) {
            reconcileChunk(new ChunkKey(world.getName(), chunk.getX(), chunk.getZ()));
        }
    }

    private void reconcileChunk(ChunkKey key) {
        if (bridge == null) return;
        for (Exhibit exhibit : byChunk.getOrDefault(key, List.of())) reconcile(exhibit);
        for (Exhibit retired : retiredByChunk.getOrDefault(key, List.of())) removeRetired(retired);
    }

    private void removeRetired(Exhibit retired) {
        World world = Bukkit.getWorld(retired.museum.getWorldName());
        ChunkKey key = chunkKey(retired);
        if (world == null || !world.isChunkLoaded(key.x, key.z)) return;
        Chunk chunk = world.getChunkAt(key.x, key.z);
        if (!chunk.isEntitiesLoaded()) return;
        try {
            List<Entity> matches = new ArrayList<>();
            for (Entity entity : chunk.getEntities()) {
                if (entity.getLocation().distanceSquared(retired.location(world)) >= 0.0625) continue;
                String owner = entity.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
                String fossil = entity.getPersistentDataContainer().get(fossilKey, PersistentDataType.STRING);
                if ((retired.museum.getOwner().toString().equals(owner) && "Pteranodon2".equals(fossil))
                        || (owner == null && fossil == null && "Pteranodon2".equals(bridge.mobType(entity)))) matches.add(entity);
            }
            if (matches.size() == 1) bridge.remove(matches.getFirst());
            else if (matches.size() > 1) warn("Ambiguous retired Pteranodon2 entities for " + retired.museum.getOwner());
        } catch (RuntimeException ex) { warn("Could not retire Pteranodon2 display: " + ex.getMessage()); }
    }

    private void reconcile(Exhibit exhibit) {
        World world = Bukkit.getWorld(exhibit.museum.getWorldName());
        ChunkKey chunk = chunkKey(exhibit);
        if (world == null || !world.isChunkLoaded(chunk.x, chunk.z)) return;
        Chunk loaded = world.getChunkAt(chunk.x, chunk.z);
        if (!loaded.isEntitiesLoaded()) return;
        try {
            Location target = exhibit.location(world);
            List<Entity> tagged = new ArrayList<>();
            List<Entity> legacy = new ArrayList<>();
            boolean unknownBase = false;
            for (Entity entity : loaded.getEntities()) {
                if (!entity.isValid()) continue;
                String owner = entity.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
                String fossil = entity.getPersistentDataContainer().get(fossilKey, PersistentDataType.STRING);
                if (exhibit.museum.getOwner().toString().equals(owner) && exhibit.fossil.id().equals(fossil)) tagged.add(entity);
                else if (owner == null && fossil == null && entity.getLocation().distanceSquared(target) < 0.0625) {
                    String type = bridge.mobType(entity);
                    if (exhibit.fossil.mob().equals(type)) legacy.add(entity);
                    else if (entity instanceof org.bukkit.entity.ArmorStand) unknownBase = true;
                }
            }
            if (unknownBase) {
                warn("Unidentified/other armor stand at " + exhibit.museum.getOwner() + "/" + exhibit.fossil.id()
                        + "; waiting for MythicMobs restoration or manual repair before spawning");
                return;
            }
            Set<String> parts = exhibit.parts();
            tagged.sort(Comparator.comparing(e -> e.getUniqueId().toString()));
            FossilDisplayReconciler.reconcile(tagged, legacy, !parts.isEmpty(),
                    () -> bridge.spawn(exhibit.fossil, target), entity -> {
                        entity.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, exhibit.museum.getOwner().toString());
                        entity.getPersistentDataContainer().set(fossilKey, PersistentDataType.STRING, exhibit.fossil.id());
                    }, entity -> {
                        if (!bridge.apply(entity, exhibit.fossil, parts))
                            warn("Waiting for model attachment: " + exhibit.fossil.id());
                    }, bridge::remove);
        } catch (RuntimeException | LinkageError ex) {
            String context = ex.getMessage() != null && ex.getMessage().startsWith("Ambiguous")
                    ? " for " + exhibit.museum.getOwner() : "";
            warn("Rendering " + exhibit.fossil.id() + context + " failed; progress retained, retrying: " + ex.getMessage());
        }
    }

    public void modelReady(Entity entity, String model, BiConsumer<FossilDefinition, Set<String>> apply) {
        if (!Bukkit.isPrimaryThread()) {
            // Bukkit world/entity reads must remain on the server thread.
            Bukkit.getScheduler().runTask(plugin, () -> modelReady(entity, model, apply));
            return;
        }
        Location location = entity.getLocation();
        var candidates = byChunk.getOrDefault(new ChunkKey(entity.getWorld().getName(),
                location.getBlockX() >> 4, location.getBlockZ() >> 4), List.of()).stream()
                .filter(e -> e.fossil.model().equals(model)
                        && location.distanceSquared(e.location(entity.getWorld())) < 0.0625).toList();
        if (candidates.size() != 1) return;
        Exhibit exhibit = candidates.getFirst();
        try { apply.accept(exhibit.fossil, exhibit.parts()); }
        catch (RuntimeException ex) { warn(ex.getMessage()); }
    }

    private void warn(String message) {
        if (warnings.add(message)) plugin.getLogger().warning("[FossilDisplays] " + message);
    }

    public void close() {
        if (retryTask != null) retryTask.cancel();
        // Persistent displays remain in the world; donations and museum data are untouched.
    }
}
