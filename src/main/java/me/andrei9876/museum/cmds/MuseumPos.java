package me.andrei9876.museum.cmds;

import com.sk89q.worldedit.math.BlockVector3;
import me.andrei9876.museum.Museum;
import me.andrei9876.museum.models.MuseumRegion;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class MuseumPos implements CommandExecutor {

    private final Museum plugin;

    public MuseumPos(Museum plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cOnly players can use this.");
            return true;
        }

        if (!p.hasPermission("museum.pos")) {
            return true;
        }

        UUID id = p.getUniqueId();
        MuseumRegion region = plugin.getMuseumsManager().getMuseum(id);

        if (region == null) {
            p.sendMessage("§cYou don’t have a museum yet!");
            return true;
        }

        Location loc = p.getLocation();
        BlockVector3 center = region.getCenter();

        double dx = loc.getX() - center.x();
        double dy = loc.getY() - center.y();
        double dz = loc.getZ() - center.z();

        p.sendMessage("§e[Museum] §7Poziția ta vs centrul muzeului:");
        p.sendMessage("§7World: §f" + loc.getWorld().getName());
        p.sendMessage("§7Center: §f" + center.x() + " " + center.y() + " " + center.z());
        p.sendMessage("§7You: §f" + fmt(loc.getX()) + " " + fmt(loc.getY()) + " " + fmt(loc.getZ()));
        p.sendMessage("§7Delta (you - center): §a"
                + fmtSigned(dx) + " "
                + fmtSigned(dy) + " "
                + fmtSigned(dz));

        p.sendMessage("§8Tip: valorile Delta sunt exact offset-urile de pus în config (x,y,z) dacă \"base\" e centrul.");
        return true;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private static String fmtSigned(double v) {
        return (v >= 0 ? "+" : "") + fmt(v);
    }
}
