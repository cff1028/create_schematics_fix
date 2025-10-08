package com.cff1028.schematicsfix;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Arrays;
import java.util.List;

public class Config {
    public static final ModConfigSpec SPEC;
    public static final Config INSTANCE;
    
    static {
        final Pair<Config, ModConfigSpec> specPair = new ModConfigSpec.Builder().configure(Config::new);
        SPEC = specPair.getRight();
        INSTANCE = specPair.getLeft();
    }
    
    public final ModConfigSpec.BooleanValue enableFileWatcher;
    public final ModConfigSpec.LongValue stableCheckInterval;
    public final ModConfigSpec.LongValue maxStableTime;
    public final ModConfigSpec.BooleanValue autoCleanAnomalies;
    public final ModConfigSpec.BooleanValue backupAnomalousFiles;
    public final ModConfigSpec.BooleanValue notifyPlayerOnAnomaly;
    
    public Config(ModConfigSpec.Builder builder) {
        builder.comment("Schematic Fix Mod Configuration")
               .push("general");
        
        enableFileWatcher = builder
            .comment("Enable file watcher for schematic uploads")
            .define("enableFileWatcher", true);
            
        stableCheckInterval = builder
            .comment("Interval in milliseconds to check file stability")
            .defineInRange("stableCheckInterval", 200L, 50L, 5000L);
            
        maxStableTime = builder
            .comment("Maximum time in milliseconds to consider a file stable")
            .defineInRange("maxStableTime", 500L, 100L, 10000L);
            
        autoCleanAnomalies = builder
            .comment("Automatically clean detected anomalies")
            .define("autoCleanAnomalies", true);
            
        backupAnomalousFiles = builder
            .comment("Backup anomalous files to anomaly directory")
            .define("backupAnomalousFiles", true);

        notifyPlayerOnAnomaly = builder
            .comment("Notify player in chat when anomalies are detected")
            .define("notifyPlayerOnAnomaly", false);
            
        builder.pop();
    }
}
