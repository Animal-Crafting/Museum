package me.andrei9876.museum.fossils;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import java.util.Set;
import java.util.List;

/** No optional plugin types escape this boundary. */
public interface FossilBridge {
    Entity spawn(FossilDefinition fossil, Location location);
    String mobType(Entity entity);
    boolean apply(Entity entity, FossilDefinition fossil, Set<String> parts);
    void remove(Entity entity);
    List<String> diagnose(FossilDefinition fossil, Entity entity);
}
