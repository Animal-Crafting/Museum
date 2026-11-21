package me.andrei9876.museum.fossils;

import com.ticxo.modelengine.api.generator.blueprint.BlueprintBone;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.bone.ModelBone;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FossilVisibilityTest {
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    @Test void allMappingsAndCombinationsKeepTransformParentsVisible() {
        var mappings = YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("/fossil-stages.yml")), StandardCharsets.UTF_8));
        var config = YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")), StandardCharsets.UTF_8));
        assertEquals(21, mappings.getConfigurationSection("fossils").getKeys(false).size());
        for (String id : mappings.getConfigurationSection("fossils").getKeys(false)) {
            var fossil = FossilDefinition.read(id, config.getConfigurationSection("offsets.Fossils." + id),
                    mappings.getConfigurationSection("fossils." + id));
            Map<String, Boolean> visibility = new HashMap<>();
            Map<String, ModelBone> bones = new HashMap<>();
            Set<String> renderers = new HashSet<>();
            fossil.bones().values().forEach(renderers::addAll);
            Set<String> all = new HashSet<>(renderers);
            all.add("transform_parent");
            for (String name : all) {
                BlueprintBone blueprint = new BlueprintBone();
                blueprint.setRenderer(renderers.contains(name));
                visibility.put(name, true);
                bones.put(name, proxy(ModelBone.class, (obj, method, args) -> switch (method.getName()) {
                    case "getBlueprintBone" -> blueprint;
                    case "setVisible" -> { visibility.put(name, (Boolean) args[0]); yield null; }
                    default -> null;
                }));
            }
            ActiveModel model = proxy(ActiveModel.class, (obj, method, args) -> method.getName().equals("getBones") ? bones : null);
            for (int mask = 0; mask < 8; mask++) {
                Set<String> parts = new HashSet<>();
                for (int i = 0; i < 3; i++) if ((mask & (1 << i)) != 0) parts.add(FossilProgress.PARTS.get(i));
                MythicModelBridge.apply(model, fossil, parts);
                Set<String> expected = fossil.visibleBones(parts);
                for (String name : renderers) assertEquals(expected.contains(name), visibility.get(name), id + "/" + name);
                assertTrue(visibility.get("transform_parent"));
            }
            // Outdated resource packs must hide everything rather than reveal the full skeleton.
            bones.remove(renderers.iterator().next());
            assertThrows(IllegalStateException.class, () -> MythicModelBridge.apply(model, fossil, Set.of("head")));
            for (String name : bones.keySet()) if (renderers.contains(name)) assertFalse(visibility.get(name));
        }
    }
}
