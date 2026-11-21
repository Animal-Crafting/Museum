package me.andrei9876.museum.cmds;

import me.andrei9876.museum.Museum;
import me.andrei9876.museum.models.MuseumRegion;
import org.bukkit.Bukkit;
import org.bukkit.command.*;

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
        return false;
    }
}
