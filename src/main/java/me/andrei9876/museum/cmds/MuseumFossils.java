package me.andrei9876.museum.cmds;

import me.andrei9876.museum.Museum;
import me.andrei9876.museum.fossils.FossilProgress;
import me.andrei9876.museum.managers.MuseumsCreatures;
import me.andrei9876.museum.models.MuseumRegion;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;

public final class MuseumFossils implements CommandExecutor {
    private final Museum plugin;
    public MuseumFossils(Museum plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("museum.fossils")) return true;
        if (args.length == 3 && args[0].equalsIgnoreCase("status")) {
            MuseumRegion museum = plugin.getMuseumsManager().getMuseum(Bukkit.getOfflinePlayer(args[1]).getUniqueId());
            if (museum == null) { sender.sendMessage("Museum not found."); return true; }
            for (String line : plugin.getFossilDisplays().diagnose(museum, args[2])) sender.sendMessage("[Fossil status] " + line);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("refresh")) {
            MuseumRegion museum = plugin.getMuseumsManager().getMuseum(Bukkit.getOfflinePlayer(args[1]).getUniqueId());
            if (museum == null) { sender.sendMessage("Museum not found."); return true; }
            var fossils = plugin.getConfig().getConfigurationSection("offsets.Fossils");
            if (fossils != null) for (String id : fossils.getKeys(false)) plugin.getFossilDisplays().refresh(museum, id);
            sender.sendMessage("Reconciled loaded fossil exhibits. Check console for asset errors.");
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("migrate-pteranodon2")) return false;
        Map<MuseumRegion, List<String>> originals = new LinkedHashMap<>();
        for (MuseumRegion museum : plugin.getMuseumsManager().getAllMuseums()) {
            List<String> fossils = museum.getValue("Fossils");
            if (fossils != null && fossils.stream().anyMatch(v -> v.equals("Pteranodon2") || v.startsWith("Pteranodon2@")))
                originals.put(museum, new ArrayList<>(fossils));
        }
        if (originals.isEmpty()) { sender.sendMessage("No Pteranodon2 donation records need migration."); return true; }
        Path source = plugin.getDataFolder().toPath().resolve("museums.yml");
        Path backup = source.resolveSibling("museums-before-pteranodon2-" + UUID.randomUUID() + ".yml.bak");
        try {
            Files.copy(source, backup);
            originals.forEach((museum, values) -> MuseumsCreatures.replaceFossils(museum, FossilProgress.migratePteranodon(values)));
            plugin.getMuseumsManager().saveMuseums();
        } catch (Exception ex) {
            originals.forEach(MuseumsCreatures::replaceFossils);
            plugin.getLogger().log(Level.SEVERE, "Pteranodon2 migration failed; progress restored in memory", ex);
            sender.sendMessage("Migration failed. Check console; original progress retained.");
            return true;
        }
        // A later display error must not roll back only memory after a successful disk commit.
        originals.keySet().forEach(museum -> plugin.getFossilDisplays().refresh(museum, "Pteranodon"));
        sender.sendMessage("Migrated " + originals.size() + " museums. Backup: " + backup.getFileName());
        return true;
    }
}
