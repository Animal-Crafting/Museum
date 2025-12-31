package me.andrei9876.museum.listeners;

import me.andrei9876.museum.Museum;
import me.andrei9876.museum.managers.MuseumsManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class MuseumCreationListener implements Listener {
    private final Museum plugin;

    public MuseumCreationListener(Museum plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();

//        Check if player has already a museum
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            MuseumsManager manager = plugin.getMuseumsManager();
            if (manager.getMuseum(player.getUniqueId()) == null) {
                boolean isCreated = plugin.getMuseumsManager().createMuseum(player.getUniqueId());
                if (!isCreated) {
                    Bukkit.getLogger().warning("[Museum] Museum Not Created (" + player.getName() + ")");
                }
            }
        }, 30);
    }


}
