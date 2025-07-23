package com.example.schematicsfix;

import com.example.schematicsfix.commands.ScanAllCommand;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.nio.file.Path;

@Mod("schematicsfix")
public class SchematicFixMod {
    public static final String MODID = "schematicsfix";
    public static final Path SCHEMATICS_DIR = FMLPaths.GAMEDIR.get().resolve("schematics");
    public static final Path UPLOADED_DIR = SCHEMATICS_DIR.resolve("uploaded");
    public static final Path ANOMALY_DIR = SCHEMATICS_DIR.resolve("anomaly");
    
    private SchematicWatcher watcher;

    public SchematicFixMod(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        watcher = new SchematicWatcher(server, UPLOADED_DIR);
        watcher.startWatching();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ScanAllCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (watcher != null) {
            watcher.stopWatching();
        }
    }
}