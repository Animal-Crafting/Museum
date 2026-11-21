package me.andrei9876.museum.fossils;

import org.bukkit.configuration.ConfigurationSection;
import java.util.*;

public record FossilDefinition(String id, String mob, String model, double x, double y, double z,
                               float yaw, float pitch, Map<String, Set<String>> bones) {
    public static FossilDefinition read(String id, ConfigurationSection placement, ConfigurationSection stages) {
        if (stages == null) throw new IllegalArgumentException("No stage mapping for " + id);
        String model = placement.getString("model", stages.getString("model"));
        if (model == null || !model.equals(stages.getString("model")))
            throw new IllegalArgumentException("Model ID disagrees with stage mapping for " + id);
        Map<String, Set<String>> bones = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (String section : List.of("head", "body", "tail", "structure")) {
            List<String> names = stages.getStringList("bones." + section);
            if (names.isEmpty()) throw new IllegalArgumentException(id + " has empty " + section + " mapping");
            Set<String> unique = new LinkedHashSet<>();
            for (String name : names) {
                if (name.isBlank() || !seen.add(name))
                    throw new IllegalArgumentException(id + " has duplicate/blank bone " + name);
                unique.add(name);
            }
            bones.put(section, Collections.unmodifiableSet(unique));
        }
        return new FossilDefinition(id, placement.getString("mob", id), model,
                placement.getDouble("x"), placement.getDouble("y"), placement.getDouble("z"),
                (float) placement.getDouble("yaw"), (float) placement.getDouble("pitch"),
                Collections.unmodifiableMap(bones));
    }

    public Set<String> visibleBones(Set<String> donated) {
        Set<String> visible = new HashSet<>();
        if (!donated.isEmpty()) visible.addAll(bones.get("structure"));
        for (String part : donated) visible.addAll(bones.getOrDefault(part, Set.of()));
        return visible;
    }
}
