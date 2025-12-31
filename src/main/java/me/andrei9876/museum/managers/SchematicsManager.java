package me.andrei9876.museum.managers;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import me.andrei9876.museum.Museum;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

import static org.bukkit.Bukkit.getLogger;

public class SchematicsManager {
    private final Museum plugin;

    public SchematicsManager(Museum plugin) {
        this.plugin = plugin;
        loadSchematics();
    }

    private Map<String, Clipboard> schematicMap = new HashMap<>();


    public void loadSchematics() {
        Map<String, Clipboard> schematicMap = new HashMap<>();
        File dir = new File(plugin.getDataFolder(), "schematics");
        if (!dir.exists() && !dir.mkdirs()) {
            getLogger().severe("Could not create schematics directory!");
            return;
        }
        for (File f : dir.listFiles((d, name) -> name.matches(".+\\.(schem|schematic|nbt)"))) {
            String base = f.getName().replaceFirst("\\.[^.]+$", "").toUpperCase();
            try {
                try (ClipboardReader reader = ClipboardFormats.findByFile(f).getReader(new FileInputStream(f))) {
                    schematicMap.put(base.toLowerCase(), reader.read());
                    getLogger().info("Mapped schematic for " + base + ": " + f.getName());
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Skipping invalid schematic " + f.getName(), e);
            }
        }
        this.schematicMap = schematicMap;
    }

    public Clipboard getSchematic(String name) {
        if(name == null) return null;
        return schematicMap.get(name.toLowerCase());
    }


    public void clearSchematics() {
        schematicMap.clear();
    }
}
