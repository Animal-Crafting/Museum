package me.andrei9876.museum.managers;

import me.andrei9876.museum.Museum;
import me.andrei9876.museum.models.MuseumRegion;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.logging.Level;

public class MuseumsCreatures {

    private final Museum plugin;

    // creatures per category, e.g. "Bugs" -> ["AnimalBugAntBlackArcher", ...]
    private final Map<String, List<String>> creaturesByCategory = new HashMap<>();

    // flat set of all creature ids for quick membership checks
    private final Set<String> allCreatures = new HashSet<>();

    public MuseumsCreatures(Museum plugin) {
        this.plugin = plugin;
        loadFromConfig();
    }

    private record cVal (String category, String value){};

    /** Call this onEnable (after saveDefaultConfig) and whenever you /reload. */
    public void loadFromConfig() {
        creaturesByCategory.clear();
        allCreatures.clear();

        ConfigurationSection root = plugin.getConfig().getConfigurationSection("creatures");
        if (root == null) {
            plugin.getLogger().log(Level.WARNING, "No 'creatures' section found in config.yml");
            return;
        }

        for (String category : root.getKeys(false)) {
            // Path is creatures.<category>
            List<String> list = root.getStringList(category);

            // Trim + drop empties
            List<String> cleaned = new ArrayList<>(list.size());
            for (String s : list) {
                if (s == null) continue;
                String t = s.trim();
                if (!t.isEmpty()) cleaned.add(t);
            }

            // Store an unmodifiable copy
            creaturesByCategory.put(category, Collections.unmodifiableList(cleaned));
            allCreatures.addAll(cleaned);
        }

        plugin.getLogger().info(() ->
                "Loaded creatures: " + creaturesByCategory.size() + " categories, " + allCreatures.size() + " total entries");
    }

    /** Returns unmodifiable list; empty if category missing. */
    public List<String> getByCategory(String category) {
        return creaturesByCategory.getOrDefault(category, Collections.emptyList());
    }

    /** All categories present in the config. */
    public Set<String> getCategories() {
        return Collections.unmodifiableSet(creaturesByCategory.keySet());
    }

    private cVal get(String creatureID){
        for (Map.Entry<String, List<String>> e : creaturesByCategory.entrySet()) {
            String category = e.getKey();
            List<String> creatures = e.getValue();
            for(String creature : creatures){
                if(creatureID.equals(creature)){
                    return new cVal(category, creature);
                }
            }
        }
        return null;
    }

    /** Flat set of all creatures across categories. */
    public Set<String> getAllCreatures() {
        return Collections.unmodifiableSet(allCreatures);
    }

    /** Quick membership check. */
    public boolean contains(String creatureId) {
        return allCreatures.contains(creatureId);
    }


    public boolean addCreature(UUID playerID,  String creatureId) {
        if(!contains(creatureId)){
            return false;
        }

        MuseumRegion museumRegion = plugin.getMuseumsManager().getMuseum(playerID);
        if(museumRegion == null){
            return false;
        }

        boolean alreadyExists = false;

        for (List<String> value : museumRegion.getAvailableByCategory().values()) {
            for (String s : value) {
                if(s.equalsIgnoreCase(creatureId)){
                    alreadyExists = true;
                    break;
                }
            }
        }

        if(alreadyExists){
            return false;
        }

        cVal value = get(creatureId);
        if(value == null){
            return false;
        }

        Vector v = museumRegion.getPoint(value.category);

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mm m spawn %value% 1 %world%,%x%,%y%,%z%"
                .replace("%value%", value.value)
                .replace("%world%", museumRegion.getWorldName())
                .replace("%x%", String.valueOf(v.getX()))
                .replace("%y%", String.valueOf(v.getY()))
                .replace("%z%", String.valueOf(v.getZ())));

        museumRegion.addValue(value.category, value.value);
        plugin.getMuseumsManager().updateMuseumRegion(playerID, museumRegion);
        return true;
    }

    public boolean remCreature(UUID playerID,  String creatureId) {
        if(!contains(creatureId)){
            return false;
        }

        MuseumRegion museumRegion = plugin.getMuseumsManager().getMuseum(playerID);
        if(museumRegion == null){
            return false;
        }

        boolean alreadyExists = false;

        for (List<String> value : museumRegion.getAvailableByCategory().values()) {
            for (String s : value) {
                if(s.equalsIgnoreCase(creatureId)){
                    alreadyExists = true;
                    break;
                }
            }
        }

        if(!alreadyExists){
            return false;
        }

        cVal value = get(creatureId);
        if(value == null){
            return false;
        }

        museumRegion.removeValue(value.category, value.value);
        plugin.getMuseumsManager().updateMuseumRegion(playerID, museumRegion);
        return true;
    }

    public void clear(){
        creaturesByCategory.clear();
        allCreatures.clear();
    }
}
