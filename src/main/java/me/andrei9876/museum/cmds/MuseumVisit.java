package me.andrei9876.museum.cmds;

import me.andrei9876.museum.Museum;
import me.andrei9876.museum.models.MuseumRegion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class MuseumVisit implements CommandExecutor {

    private final Museum plugin;

    public MuseumVisit(Museum plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cOnly players can use this.");
            return true;
        }

        if(args.length < 1){
            sender.sendMessage("Invalid ussage use /museumvisit <playerName>");
            return true;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(args[0]);
        UUID id = offlinePlayer.getUniqueId();
        MuseumRegion region = plugin.getMuseumsManager().getMuseum(id);

        if (region == null) {
            p.sendMessage("§cThis player doesn’t have a museum yet!");
            return true;
        }

        if(region.getOwner().equals(p.getUniqueId())){
            sender.sendMessage("You can't visit your own museum");
            return true;
        }


        // Teleport to center, adjust Y+1 so player stands above ground

        p.teleport(region.getSpawnLocation());
        p.sendMessage("§aTeleported to your museum!");
        return true;
    }
}