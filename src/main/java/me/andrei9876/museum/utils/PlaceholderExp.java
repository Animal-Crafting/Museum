package me.andrei9876.museum.utils;

import me.andrei9876.museum.Museum;
import me.andrei9876.museum.models.MuseumRegion;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PlaceholderExp extends PlaceholderExpansion {
    private final Museum plugin;
    public PlaceholderExp(Museum plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "museum"; // %museum_<something>%
    }

    @Override
    public @NotNull String getAuthor() {
        return "Andrei9876";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // keep registered after reloads
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        MuseumRegion region = plugin.getMuseumsManager().getMuseum(player.getUniqueId());
        if (region == null) return "false";

        Map<String, List<String>> byCat = region.getAvailableByCategory();
        if (byCat == null || byCat.isEmpty()) return "false";

        // %placeholder% with params = "list:<category>"  -> returns comma-separated values for that category
        if (params.startsWith("list:")) {
            String cat = params.substring("list:".length());
            List<String> vals = byCat.getOrDefault(cat, Collections.emptyList());
            return vals.isEmpty() ? "" : String.join(",", vals);
        }

        // %placeholder% with params = "has_in:<category>:<value>" -> true/false
        if (params.startsWith("has_in:")) {
            String[] parts = params.split(":", 3);
            if (parts.length == 3) {
                List<String> vals = byCat.get(parts[1]);
                return String.valueOf(vals != null && vals.contains(parts[2]));
            }
            return "false";
        }

        // Default: params este valoarea căutată în ORICE categorie -> true/false
        boolean presentAnywhere = byCat.values().stream()
                .filter(Objects::nonNull)
                .anyMatch(list -> list.contains(params));

        return String.valueOf(presentAnywhere);
    }
}
