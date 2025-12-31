package me.andrei9876.museum.cmds;

import me.andrei9876.museum.Museum;
import me.andrei9876.museum.models.MuseumRegion;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class MyMuseumCommand implements CommandExecutor {

    private final Museum plugin;

    public MyMuseumCommand(Museum plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cOnly players can use this.");
            return true;
        }

        UUID id = p.getUniqueId();
        MuseumRegion region = plugin.getMuseumsManager().getMuseum(id);

        if (region == null) {
            p.sendMessage("§cYou don’t have a museum yet!");
            return true;
        }


        // Teleport to center, adjust Y+1 so player stands above ground

        p.teleport(region.getSpawnLocation());
        p.sendMessage("§aTeleported to your museum!");
        return true;
    }
}