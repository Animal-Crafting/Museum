package me.andrei9876.museum.fossils;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.events.ModelSetupEvent;
import com.ticxo.modelengine.api.model.ActiveModel;
import io.lumine.mythic.bukkit.MythicBukkit;
import me.andrei9876.museum.Museum;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.*;
import java.util.*;

/** Compiled against ModelEngine R4.1.0 and MythicMobs 5.12.0. */
public final class MythicModelBridge implements FossilBridge, Listener {
    private final FossilDisplays displays;

    public MythicModelBridge(Museum plugin, FossilDisplays displays) {
        this.displays = displays;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override public Entity spawn(FossilDefinition fossil, Location location) {
        if (ModelEngineAPI.getBlueprint(fossil.model()) == null)
            throw new IllegalStateException("ModelEngine blueprint missing: " + fossil.model());
        try {
            Entity entity = MythicBukkit.inst().getAPIHelper().spawnMythicMob(fossil.mob(), location);
            if (entity == null) throw new IllegalStateException("MythicMob spawn returned null: " + fossil.mob());
            return entity;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not spawn " + fossil.mob(), ex);
        }
    }

    @Override public String mobType(Entity entity) {
        return MythicBukkit.inst().getMobManager().getActiveMob(entity.getUniqueId())
                .map(mob -> mob.getType().getInternalName()).orElse(null);
    }

    @Override public boolean apply(Entity entity, FossilDefinition fossil, Set<String> parts) {
        var modeled = ModelEngineAPI.getModeledEntity(entity);
        if (modeled == null) return false;
        var model = modeled.getModel(fossil.model()).orElse(null);
        if (model == null) return false;
        apply(model, fossil, parts);
        modeled.setBaseEntityVisible(false);
        modeled.setYBodyRotImmediately(fossil.yaw());
        modeled.setYHeadRotImmediately(fossil.yaw());
        modeled.setXHeadRotImmediately(fossil.pitch());
        return true;
    }

    static void apply(ActiveModel model, FossilDefinition fossil, Set<String> parts) {
        Set<String> expected = new HashSet<>();
        fossil.bones().values().forEach(expected::addAll);
        Set<String> missing = new HashSet<>(expected);
        missing.removeAll(model.getBones().keySet());
        Set<String> unmapped = new HashSet<>();
        model.getBones().forEach((id, bone) -> {
            if (bone.getBlueprintBone().isRenderer() && !expected.contains(id)) unmapped.add(id);
        });
        if (!missing.isEmpty() || !unmapped.isEmpty()) {
            // Fail closed: never show an undonated full skeleton with old/mismatched assets.
            model.getBones().values().forEach(bone -> {
                if (bone.getBlueprintBone().isRenderer()) bone.setVisible(false);
            });
            throw new IllegalStateException(fossil.id() + " model mismatch: missing=" + missing + ", unmapped=" + unmapped);
        }
        Set<String> visible = fossil.visibleBones(parts);
        expected.forEach(id -> model.getBones().get(id).setVisible(visible.contains(id)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onModelSetup(ModelSetupEvent event) {
        // This event runs after bones exist and before their first render, also on model reload.
        Object base = event.getModeledEntity().getBase().getOriginal();
        if (base instanceof Entity entity) displays.modelReady(entity, event.getActiveModel().getBlueprint().getName(),
                (fossil, parts) -> apply(event.getActiveModel(), fossil, parts));
    }

    @Override public void remove(Entity entity) {
        var modeled = ModelEngineAPI.getModeledEntity(entity);
        if (modeled != null) modeled.destroy();
        var mob = MythicBukkit.inst().getMobManager().getActiveMob(entity.getUniqueId());
        if (mob.isPresent()) mob.get().remove();
        else entity.remove();
    }

    @Override public List<String> diagnose(FossilDefinition fossil, Entity entity) {
        List<String> lines = new ArrayList<>();
        if (entity == null) {
            lines.add("MythicMob definition " + fossil.mob() + ": "
                    + MythicBukkit.inst().getMobManager().getMythicMob(fossil.mob()).isPresent());
            var blueprint = ModelEngineAPI.getBlueprint(fossil.model());
            lines.add("ModelEngine blueprint " + fossil.model() + ": " + (blueprint != null));
            if (blueprint != null) {
                Set<String> available = new HashSet<>();
                java.util.function.Consumer<com.ticxo.modelengine.api.generator.blueprint.BlueprintBone> collect =
                        new java.util.function.Consumer<>() {
                            @Override public void accept(com.ticxo.modelengine.api.generator.blueprint.BlueprintBone bone) {
                                available.add(bone.getName());
                                bone.getChildren().values().forEach(this);
                            }
                        };
                blueprint.getBones().values().forEach(collect);
                Set<String> missing = new TreeSet<>();
                fossil.bones().values().forEach(missing::addAll);
                missing.removeAll(available);
                lines.add("Blueprint missing staging bones: " + summarize(missing));
            }
            return lines;
        }
        lines.add("Entity " + entity.getUniqueId() + ": type=" + mobType(entity) + ", valid=" + entity.isValid());
        var modeled = ModelEngineAPI.getModeledEntity(entity);
        if (modeled == null) { lines.add("ModelEngine entity: NOT ATTACHED"); return lines; }
        lines.add("Attached models: " + modeled.getModels().keySet());
        var model = modeled.getModel(fossil.model()).orElse(null);
        if (model == null) { lines.add("Expected model " + fossil.model() + ": NOT ATTACHED"); return lines; }
        Set<String> expected = new HashSet<>();
        fossil.bones().values().forEach(expected::addAll);
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(model.getBones().keySet());
        Set<String> unmapped = new TreeSet<>();
        model.getBones().forEach((id, bone) -> {
            if (bone.getBlueprintBone().isRenderer() && !expected.contains(id)) unmapped.add(id);
        });
        lines.add("Attached model missing bones: " + summarize(missing) + "; unmapped renderers: " + summarize(unmapped));
        fossil.bones().forEach((part, names) -> {
            long visible = names.stream().map(model.getBones()::get).filter(Objects::nonNull)
                    .filter(bone -> bone.isVisible() && !bone.isEffectivelyInvisible()).count();
            lines.add("Visible " + part + " bones: " + visible + "/" + names.size());
        });
        return lines;
    }

    private static String summarize(Set<String> names) {
        return names.isEmpty() ? "0" : names.size() + " " + names.stream().limit(8).toList()
                + (names.size() > 8 ? " ..." : "");
    }
}
