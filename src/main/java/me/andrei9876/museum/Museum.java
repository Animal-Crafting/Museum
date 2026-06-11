package me.andrei9876.museum;

import me.andrei9876.museum.cmds.*;
import me.andrei9876.museum.listeners.MuseumCreationListener;
import me.andrei9876.museum.listeners.MuseumDoorListener;
import me.andrei9876.museum.listeners.MuseumProtectionListener;
import me.andrei9876.museum.managers.MuseumsCreatures;
import me.andrei9876.museum.managers.MuseumsManager;
import me.andrei9876.museum.managers.SchematicsManager;
import me.andrei9876.museum.utils.PlaceholderExp;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class Museum extends JavaPlugin {
    public static final String BYPASS_PERM = "museum.bypass";
    private SchematicsManager schematicsManager;
    private MuseumsManager museumsManager;
    private MuseumsCreatures museumsCreatures;

    @Override
    public void onEnable() {
        // Plugin startup logic
        saveDefaultConfig();

        schematicsManager = new SchematicsManager(this);
        museumsManager = new MuseumsManager(this);
        museumsCreatures = new MuseumsCreatures(this);
        Bukkit.getPluginManager().registerEvents(new MuseumProtectionListener(this), this);
        Bukkit.getPluginManager().registerEvents(new MuseumCreationListener(this), this);
        Bukkit.getPluginManager().registerEvents(new MuseumDoorListener(this), this);


        getCommand("mymuseum").setExecutor(new MyMuseumCommand(this));
        getCommand("museumvisit").setExecutor(new MuseumVisit(this));
        getCommand("museumadd").setExecutor(new MuseumAdd(this));
        getCommand("museumrem").setExecutor(new MuseumRem(this));
        getCommand("museumdebug").setExecutor(new DebugMuseum(this));
        getCommand("museumpos").setExecutor(new MuseumPos(this));
        getCommand("museumfishdebug").setExecutor(new MuseumFishDebug(this));


        if(Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")){
            new PlaceholderExp(this).register();
        }

//        getLogger().info("Museum plugin enabled. Schematics loaded: " + schematicMap.size());
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        if(schematicsManager != null) {
            schematicsManager.clearSchematics();
        }

        if(museumsManager != null){
            museumsManager.saveMuseums();
        }
        if(museumsCreatures != null) {
            museumsCreatures.clear();
        }
    }

    public SchematicsManager getSchematicsManager() {
        return schematicsManager;
    }

    public MuseumsManager getMuseumsManager() {
        return museumsManager;
    }

    public MuseumsCreatures getMuseumsCreatures() {
        return museumsCreatures;
    }
}
