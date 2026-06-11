package me.andrei9876.museum.utils;

import me.andrei9876.museum.Museum;
import me.andrei9876.museum.models.MuseumRegion;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
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
        if (player == null) return "false";

        String p = params.trim();

        // 1) întâi: muzeul lui (owner)
        MuseumRegion region = plugin.getMuseumsManager().getMuseum(player.getUniqueId());

        // 2) dacă nu e owner, dar e în vizită în alt muzeu: ia muzeul după locație
        if (region == null) {
            region = plugin.getMuseumsManager()
                    .findByLocation(player.getLocation())
                    .orElse(null);
        }

        if (region == null) {
            if (p.equalsIgnoreCase("donations") || p.equalsIgnoreCase("donation_count")) {
                return "0";
            }
            return "false";
        }

        // %museum_donations% -> total unique donations in the museum
        if (p.equalsIgnoreCase("donations") || p.equalsIgnoreCase("donation_count")) {
            return String.valueOf(region.getDonationCount());
        }

        Map<String, List<String>> byCat = region.getAvailableByCategory();
        if (byCat == null || byCat.isEmpty()) return "false";

        // %museum_list:<category>% -> returns comma-separated values for that category
        if (p.startsWith("list:")) {
            String cat = p.substring("list:".length()).trim();
            List<String> vals = byCat.getOrDefault(cat, Collections.emptyList());
            return vals.isEmpty() ? "" : String.join(",", vals);
        }

        // %museum_has_in:<category>:<value>% -> true/false
        // %museum_has_in:<category>:<fossilId>:<part>% -> true/false (token fossilId@part)
        if (p.startsWith("has_in:")) {
            String[] parts = p.split(":", 4);

            if (parts.length == 3) {
                String category = parts[1].trim();
                String value = parts[2].trim();

                List<String> vals = byCat.get(category);
                return String.valueOf(vals != null && vals.contains(value));
            }

            if (parts.length == 4) {
                String category = parts[1].trim();
                String fossilId = parts[2].trim();
                String part = parts[3].trim();

                String token = fossilId + "@" + part;
                List<String> vals = byCat.get(category);
                return String.valueOf(vals != null && vals.contains(token));
            }

            return "false";
        }

        // Default: caută valoarea în ORICE categorie -> true/false
        boolean presentAnywhere = byCat.values().stream()
                .filter(Objects::nonNull)
                .anyMatch(list -> list.contains(p));

        return String.valueOf(presentAnywhere);
    }
}
