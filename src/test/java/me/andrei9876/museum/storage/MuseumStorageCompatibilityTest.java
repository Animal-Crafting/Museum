package me.andrei9876.museum.storage;

import me.andrei9876.museum.Museum;
import me.andrei9876.museum.fossils.FossilDonations;
import me.andrei9876.museum.fossils.FossilProgress;
import me.andrei9876.museum.managers.MuseumsManager;
import me.andrei9876.museum.models.MuseumRegion;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MuseumStorageCompatibilityTest {
    @TempDir Path temp;

    private MuseumsManager manager(Path folder) {
        Museum plugin = mock(Museum.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MuseumStorageCompatibilityTest"));
        try (var reader = new InputStreamReader(Objects.requireNonNull(
                getClass().getResourceAsStream("/config.yml")), StandardCharsets.UTF_8)) {
            when(plugin.getConfig()).thenReturn(YamlConfiguration.loadConfiguration(reader));
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        return new MuseumsManager(plugin);
    }

    private static YamlConfiguration read(Path file) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        return yaml;
    }

    private static Map<String, Object> semanticValues(ConfigurationSection yaml) {
        Map<String, Object> values = new TreeMap<>();
        yaml.getValues(true).forEach((key, value) -> {
            if (!(value instanceof ConfigurationSection)) values.put(key, value);
        });
        return values;
    }

    private static void assertSemanticValues(Map<String, Object> expected, Map<String, Object> actual,
                                             String message) throws Exception {
        Set<String> keys = new TreeSet<>(expected.keySet());
        keys.addAll(actual.keySet());
        List<String> differences = new ArrayList<>();
        for (String key : keys) {
            if (!Objects.equals(expected.get(key), actual.get(key)) || expected.containsKey(key) != actual.containsKey(key)) {
                differences.add(key + ": expected=" + (expected.containsKey(key) ? expected.get(key) : "<absent>")
                        + ", actual=" + (actual.containsKey(key) ? actual.get(key) : "<absent>"));
            }
        }
        // An optional private report stays outside build artifacts; failure messages never dump player records.
        String report = System.getProperty("museum.compatibilityReport");
        if (!differences.isEmpty() && report != null) Files.write(Path.of(report), differences, StandardCharsets.UTF_8);
        assertEquals(0, differences.size(), message + " (changed field count; use museum.compatibilityReport for details)");
    }

    private void roundTripAndDonate(Path folder, int expectedCount) throws Exception {
        Path file = folder.resolve("museums.yml");
        Map<String, Object> before = semanticValues(read(file));
        MuseumsManager manager = manager(folder);
        assertEquals(expectedCount, manager.getAllMuseums().size(), "Every supplied museum must load");
        for (MuseumRegion museum : manager.getAllMuseums()) {
            for (String fossil : List.of("Trex", "Mammoth", "Pteranodon")) {
                FossilProgress.parts(museum.getValue("Fossils"), fossil);
            }
        }
        manager.saveMuseums();
        Map<String, Object> roundTrip = semanticValues(read(file));
        MuseumRegion selected = manager.getAllMuseums().stream()
                .filter(museum -> museum.getValue("Fossils") != null
                        && !FossilProgress.parts(museum.getValue("Fossils"), "Mammoth").contains("head"))
                .findFirst().orElseThrow();
        assertTrue(FossilDonations.donate(selected, "Mammoth", "head"));
        manager.updateMuseumRegion(selected.getOwner(), selected);
        Map<String, Object> expected = new TreeMap<>(before);
        expected.put("museums." + selected.getOwner() + ".availableByCategory.Fossils",
                new ArrayList<>(selected.getValue("Fossils")));
        Map<String, Object> afterDonation = semanticValues(read(file));
        MuseumsManager reloaded = manager(folder);
        MuseumRegion saved = reloaded.getMuseum(selected.getOwner());
        assertEquals(selected.getValue("Fossils"), saved.getValue("Fossils"));
        assertTrue(FossilProgress.parts(saved.getValue("Fossils"), "Mammoth").contains("head"));
        assertFalse(FossilDonations.donate(saved, "Mammoth", "head"));
        reloaded.saveMuseums();
        Map<String, Object> afterReload = semanticValues(read(file));
        assertAll("Original storage compatibility",
                () -> assertSemanticValues(before, roundTrip, "Load, progress reads and save must preserve supplied semantic values"),
                () -> assertSemanticValues(expected, afterDonation, "Only the intended donation list may change"),
                () -> assertSemanticValues(expected, afterReload, "Reload and duplicate rejection must retain all values"));
    }

    @Test void originalStoragePreservesSupportedRecordsAndNewDonation() throws Exception {
        var resource = Objects.requireNonNull(getClass().getResourceAsStream("/museum-compatibility.yml"));
        try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            YamlConfiguration.loadConfiguration(reader).save(temp.resolve("museums.yml").toFile());
        }
        MuseumsManager loaded = manager(temp);
        MuseumRegion museum = loaded.getMuseum(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertTrue(FossilProgress.completed(museum.getValue("Fossils"), "Trex"));
        assertTrue(FossilProgress.completed(museum.getValue("Fossils"), "Pteranodon"));
        assertEquals(Set.of("tail"), FossilProgress.parts(museum.getValue("Fossils"), "Mammoth"));
        assertEquals(List.of("Amber", "Trex", "Mammoth@tail", "Pteranodon2@head", "Pteranodon@body",
                "Pteranodon2@tail"), museum.getValue("Fossils"));
        roundTripAndDonate(temp, 1);
    }

    @Test
    @EnabledIfSystemProperty(named = "museum.liveData", matches = ".+")
    void originalStoragePreservesAllSuppliedMuseums() throws Exception {
        Path source = Path.of(System.getProperty("museum.liveData"));
        byte[] original = Files.readAllBytes(source);
        Files.copy(source, temp.resolve("museums.yml"));
        int count = read(source).getConfigurationSection("museums").getKeys(false).size();
        try {
            roundTripAndDonate(temp, count);
        } finally {
            assertArrayEquals(original, Files.readAllBytes(source), "Private input must never be modified");
        }
    }
}
