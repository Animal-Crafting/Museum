package me.andrei9876.museum.cmds;

import me.andrei9876.museum.Museum;
import me.andrei9876.museum.models.MuseumRegion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.util.Vector;

import java.util.UUID;

public class DebugMuseum implements CommandExecutor {

    private final Museum plugin;

    public DebugMuseum(Museum plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if(!sender.hasPermission("museum.debug")){
            return true;
        }

        plugin.getMuseumsManager().museums.values().forEach(museumRegion -> museumRegion.getAvailableByCategory().forEach((category, values) -> {
            Vector v = museumRegion.getPoint(category);
            if(v != null) {
                for (String value : values) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mm m spawn %value% 1 %world%,%x%,%y%,%z%"
                            .replace("%value%", value)
                            .replace("%world%", museumRegion.getWorldName())
                            .replace("%x%", String.valueOf(v.getX()))
                            .replace("%y%", String.valueOf(v.getY()))
                            .replace("%z%", String.valueOf(v.getZ())));
                }
            }
        }));

        return true;
    }
}