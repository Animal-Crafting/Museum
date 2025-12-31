package me.andrei9876.museum.cmds;

import me.andrei9876.museum.Museum;
import me.andrei9876.museum.models.MuseumRegion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.UUID;

public class MuseumRem implements CommandExecutor {

    private final Museum plugin;

    public MuseumRem(Museum plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if(!sender.hasPermission("museum.rem")){
            return true;
        }

        if(args.length < 2){
            sender.sendMessage("Invalid ussage use /museumrem <playerName> <creature>");
            return true;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(args[0]);
        UUID id = offlinePlayer.getUniqueId();
        MuseumRegion region = plugin.getMuseumsManager().getMuseum(id);

        if (region == null) {
            sender.sendMessage("§cThis player doesn’t have a museum yet!");
            return true;
        }

        boolean result = plugin.getMuseumsCreatures().remCreature(id, args[1]);
        if(!result){
            sender.sendMessage(ChatColor.RED + "A problem has been found in trying to add a creature!");
        }

        return true;
    }
}