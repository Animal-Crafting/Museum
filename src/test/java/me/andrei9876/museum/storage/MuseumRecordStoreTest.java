package me.andrei9876.museum.storage;

import me.andrei9876.museum.fossils.FossilProgress;
import me.andrei9876.museum.fossils.FossilDonations;
import me.andrei9876.museum.models.MuseumRegion;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MuseumRecordStoreTest {
    @TempDir Path temp;

    private Map<UUID, MuseumRegion> load(YamlConfiguration document, MuseumRecordStore store) {
        Map<UUID, MuseumRegion> regions = new LinkedHashMap<>();
        ConfigurationSection root = document.getConfigurationSection("museums");
        assertNotNull(root);
        for (String key : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                MuseumRegion region = MuseumRegionCodec.read(root.getConfigurationSection(key), id, new Vector(), ignored -> Map.of());
                regions.put(id, region);
                store.remember(id, region);
            } catch (IllegalArgumentException ex) {
                // Production retains failed records in the document as well.
            }
        }
        return regions;
    }

    private static Map<String, Object> flatten(ConfigurationSection section) {
        Map<String, Object> result = new LinkedHashMap<>();
        section.getValues(true).forEach((key, value) -> {
            if (!(value instanceof ConfigurationSection)) result.put(key, value);
        });
        return result;
    }

    @Test void retainsUnknownFieldsMalformedRecordsAndLegacyOmissions() throws Exception {
        UUID id = UUID.randomUUID();
        YamlConfiguration document = new YamlConfiguration();
        String prefix = "museums." + id;
        document.set(prefix + ".min", List.of(0, 0, 0));
        document.set(prefix + ".max", List.of(100, 100, 100));
        document.set(prefix + ".center", List.of(50, 50, 50));
        document.set(prefix + ".future.unknown", "preserve me");
        document.set(prefix + ".availableByCategory.Fossils", List.of("Trex"));
        document.set("museums.unreadable.unexpected", List.of(1, 2, 3));
        exercisePreservation(document, 1);
    }

    @Test
    @EnabledIfSystemProperty(named = "museum.liveData", matches = ".+")
    void existingMuseumsRoundTripAndOneDonation() throws Exception {
        Path source = Path.of(System.getProperty("museum.liveData"));
        byte[] original = Files.readAllBytes(source);
        Path copy = temp.resolve("live-copy.yml");
        Files.copy(source, copy);
        YamlConfiguration document = new YamlConfiguration();
        document.load(copy.toFile());
        exercisePreservation(document, document.getConfigurationSection("museums").getKeys(false).size());
        assertArrayEquals(original, Files.readAllBytes(source), "Live input must never be modified");
    }

    private void exercisePreservation(YamlConfiguration document, int expectedCount) throws Exception {
        MuseumRecordStore store = new MuseumRecordStore();
        Map<UUID, MuseumRegion> regions = load(document, store);
        assertEquals(expectedCount, regions.size(), "Every valid museum must load");
        Map<String, Object> before = flatten(document);
        Path target = temp.resolve("museums.yml");
        document.save(target.toFile());
        byte[] bytes = Files.readAllBytes(target);
        assertFalse(store.save(document, regions, target), "Startup/reconciliation must not rewrite data");
        assertArrayEquals(bytes, Files.readAllBytes(target));
        // Reading render state for every fossil must not migrate or modify donations.
        for (MuseumRegion region : regions.values()) for (String id : List.of("Trex", "Mammoth", "Pteranodon"))
            FossilProgress.parts(region.getValue("Fossils"), id);
        assertFalse(store.save(document, regions, target));
        var selected = regions.entrySet().stream().filter(e -> e.getValue().getValue("Fossils") != null
                && !FossilProgress.parts(e.getValue().getValue("Fossils"), "Mammoth").contains("head")).findFirst().orElseThrow();
        assertTrue(FossilDonations.donate(selected.getValue(), "Mammoth", "head"));
        assertTrue(store.save(document, regions, target));
        YamlConfiguration after = new YamlConfiguration();
        after.load(target.toFile());
        Map<String, Object> expected = new LinkedHashMap<>(before);
        String changed = "museums." + selected.getKey() + ".availableByCategory.Fossils";
        List<String> updated = new ArrayList<>(selected.getValue().getValue("Fossils"));
        expected.put(changed, updated);
        assertEquals(expected, flatten(after), "Only the selected donation list may change, across every museum field");
        assertFalse(store.save(document, regions, target), "Successful save updates baseline");
    }

    @Test void failedWriteDoesNotAdvanceBaseline() throws Exception {
        UUID id = UUID.randomUUID();
        var region = new MuseumRegion(id, "Museums", com.sk89q.worldedit.math.BlockVector3.ZERO,
                com.sk89q.worldedit.math.BlockVector3.ZERO, com.sk89q.worldedit.math.BlockVector3.ZERO,
                new Vector(), Map.of());
        MuseumRecordStore store = new MuseumRecordStore();
        store.remember(id, region);
        region.addValue("Fossils", "Trex@head");
        YamlConfiguration document = new YamlConfiguration();
        assertThrows(java.io.IOException.class, () -> store.save(document, Map.of(id, region), temp.resolve("missing/museums.yml")));
        assertTrue(store.save(document, Map.of(id, region), temp.resolve("recovered.yml")));
    }
}
