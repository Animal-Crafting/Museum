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

import java.util.UUID;

public class MuseumAdd implements CommandExecutor {

    private final Museum plugin;

    public MuseumAdd(Museum plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if(!sender.hasPermission("museum.add")){
            return true;
        }

        if(args.length < 2){
            sender.sendMessage("Invalid ussage use /museumadd <playerName> <creature> [head|body|tail]");
            return true;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(args[0]);
        UUID id = offlinePlayer.getUniqueId();
        MuseumRegion region = plugin.getMuseumsManager().getMuseum(id);

        if (region == null) {
            sender.sendMessage("§cThis player doesn’t have a museum yet!");
            return true;
        }

        String part = null;
        if (args.length >= 3) {
            part = args[2];
        }

        boolean result = plugin.getMuseumsCreatures().addCreature(id, args[1], part);
        if(!result){
            sender.sendMessage(ChatColor.RED + "A problem has been found in trying to add a creature!");
            if (plugin.getConfig().contains("offsets.Fossils." + args[1]))
                sender.sendMessage("Use /museumfossils status " + args[0] + " " + args[1] + " to inspect saved pieces and rendering.");
        } else {
            sender.sendMessage(ChatColor.GREEN + "Museum donation saved.");
            if (plugin.getConfig().contains("offsets.Fossils." + args[1]))
                sender.sendMessage("Rendering status: /museumfossils status " + args[0] + " " + args[1]);
        }

        return true;
    }
}
