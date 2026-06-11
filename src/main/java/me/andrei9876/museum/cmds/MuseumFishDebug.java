package me.andrei9876.museum.cmds;

import me.andrei9876.museum.Museum;
import me.andrei9876.museum.models.MuseumRegion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MuseumFishDebug implements CommandExecutor {

    private final Museum plugin;

    public MuseumFishDebug(Museum plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("museum.fishdebug")) {
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <playerName|uuid>");
            return true;
        }

        UUID targetId = resolvePlayerId(args[0]);
        if (targetId == null) {
            sender.sendMessage(ChatColor.RED + "Player not found: " + args[0]);
            return true;
        }

        MuseumRegion region = plugin.getMuseumsManager().getMuseum(targetId);
        if (region == null) {
            sender.sendMessage(ChatColor.RED + "That player doesn't have a museum: " + args[0]);
            return true;
        }

        String fishCategoryKey = findCategoryKeyIgnoreCase(region.getAvailableByCategory(), "Fish");
        if (fishCategoryKey == null) {
            sender.sendMessage(ChatColor.YELLOW + "No Fish category saved for that museum.");
            return true;
        }

        List<String> fish = region.getAvailableByCategory().get(fishCategoryKey);
        if (fish == null || fish.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No fish saved for that museum.");
            return true;
        }

        Vector spawnPoint = region.getPoint(fishCategoryKey);
        if (spawnPoint == null) {
            // fallback: common config key is exactly "Fish"
            spawnPoint = region.getPoint("Fish");
        }

        if (spawnPoint == null) {
            sender.sendMessage(ChatColor.RED + "No spawn point found for Fish in that museum.");
            return true;
        }

        int spawned = 0;
        for (String mobId : fish) {
            if (mobId == null || mobId.isBlank()) continue;

            Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(),
                    "mm m spawn %value% 1 %world%,%x%,%y%,%z%"
                            .replace("%value%", mobId.trim())
                            .replace("%world%", region.getWorldName())
                            .replace("%x%", String.valueOf(spawnPoint.getX()))
                            .replace("%y%", String.valueOf(spawnPoint.getY()))
                            .replace("%z%", String.valueOf(spawnPoint.getZ()))
            );
            spawned++;
        }

        sender.sendMessage(ChatColor.GREEN + "Respawned " + spawned + " fish for " + args[0] + ".");
        return true;
    }

    private static String findCategoryKeyIgnoreCase(Map<String, List<String>> map, String wanted) {
        if (map == null || wanted == null) return null;
        for (String k : map.keySet()) {
            if (k != null && k.equalsIgnoreCase(wanted)) return k;
        }
        return null;
    }

    private static UUID resolvePlayerId(String input) {
        if (input == null || input.isBlank()) return null;

        // UUID support
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException ignored) {
            // not a uuid
        }

        // Online first
        Player p = Bukkit.getPlayerExact(input);
        if (p != null) return p.getUniqueId();

        // Offline fallback
        OfflinePlayer off = Bukkit.getOfflinePlayer(input);
        if (off.hasPlayedBefore() || off.isOnline()) return off.getUniqueId();

        return null;
    }
}
