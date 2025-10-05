package com.cff1028.schematicsfix;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;

@Mod(SchematicFixMod.MODID)
public class SchematicFixMod {
    public static final String MODID = "schematicsfix";
    public static final Logger LOGGER = LogManager.getLogger();
    
    private static SchematicFixMod INSTANCE;
    
    private FileWatcher fileWatcher;
    private SchematicNBTDetector nbtDetector;
    private boolean fileWatcherRunning = false;
    
    public SchematicFixMod(IEventBus modEventBus, ModContainer modContainer) {
        INSTANCE = this;
        
        modEventBus.addListener(this::commonSetup);
        
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }
    
    public static SchematicFixMod getInstance() {
        return INSTANCE;
    }
    
    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Initializing Schematic Fix Mod");
        this.nbtDetector = new SchematicNBTDetector();
    }
    
    private void onServerStarting(ServerStartingEvent event) {
        try {
            this.fileWatcher = new FileWatcher(nbtDetector);
            this.fileWatcher.start();
            this.fileWatcherRunning = true;
            LOGGER.info("Schematic file watcher started successfully");
        } catch (IOException e) {
            LOGGER.error("Failed to start schematic file watcher", e);
            this.fileWatcherRunning = false;
        }
    }
    
    private void onServerStopping(ServerStoppingEvent event) {
        if (this.fileWatcher != null) {
            try {
                this.fileWatcher.stop();
                this.fileWatcherRunning = false;
                LOGGER.info("Schematic file watcher stopped successfully");
            } catch (IOException e) {
                LOGGER.error("Error stopping schematic file watcher", e);
            }
        }
    }
    
    public SchematicNBTDetector getNbtDetector() {
        return nbtDetector;
    }
    
    public boolean isFileWatcherRunning() {
        return fileWatcherRunning;
    }
    
    public List<String> getBannedKeywords() {
        return nbtDetector != null ? nbtDetector.getBannedKeywords() : List.of();
    }
}