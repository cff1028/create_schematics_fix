package com.cff1028.schematicsfix;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.List;

@EventBusSubscriber(modid = SchematicFixMod.MODID)
public class SchematicConfigCommand {
    
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        
        dispatcher.register(Commands.literal("schematic-config")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("reload")
                .executes(SchematicConfigCommand::reloadConfig)
            )
            .then(Commands.literal("status")
                .executes(SchematicConfigCommand::showStatus)
            )
            .then(Commands.literal("rules")
                .executes(SchematicConfigCommand::showRules)
            )
        );
    }
    
    private static int reloadConfig(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SchematicFixMod mod = SchematicFixMod.getInstance();
        
        if (mod != null && mod.getNbtDetector() != null) {
            boolean success = mod.getNbtDetector().reloadConfig();
            int ruleCount = mod.getNbtDetector().getRuleCount();
            
            if (success) {
                source.sendSuccess(() -> 
                    Component.literal("§aConfiguration reloaded successfully! " + ruleCount + " filter rules have been loaded."),
                    true
                );
                SchematicFixMod.LOGGER.info("Configuration reloaded successfully by {} with {} rules", 
                    source.getTextName(), ruleCount);
                return Command.SINGLE_SUCCESS;
            } else {
                source.sendFailure(
                    Component.literal("§cConfiguration reload failed! Please check the console log for more details.")
                );
                return 0;
            }
        } else {
            source.sendFailure(
                Component.literal("§cSchematicFix Mod was not initialized correctly and cannot reload the configuration.")
            );
            return 0;
        }
    }
    
    private static int showStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SchematicFixMod mod = SchematicFixMod.getInstance();
        
        if (mod != null && mod.getNbtDetector() != null) {
            int ruleCount = mod.getNbtDetector().getRuleCount();
            boolean fileWatcherRunning = mod.isFileWatcherRunning();
            
            source.sendSuccess(() -> 
                Component.literal("§6SchematicFix Mod Status:\n" +
                    "§7- Filter rules loaded: §e" + ruleCount + "\n" +
                    "§7- File Watcher Status: §e" + (fileWatcherRunning ? "Running" : "Not Running") + "\n" +
                    "§7- Use §e/schematic-config reload §7to reload configuration\n" +
                    "§7- Use §e/schematic-config rules §7to view loaded rules"), 
                false
            );
            return Command.SINGLE_SUCCESS;
        } else {
            source.sendFailure(
                Component.literal("§cSchematicFix Mod was not initialized correctly.")
            );
            return 0;
        }
    }
    
    private static int showRules(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SchematicFixMod mod = SchematicFixMod.getInstance();
        
        if (mod != null && mod.getNbtDetector() != null) {
            List<String> rules = mod.getNbtDetector().getRuleNames();
            
            if (rules.isEmpty()) {
                source.sendSuccess(() -> 
                    Component.literal("§6Loaded Rules:\n§7No rules loaded"), 
                    false
                );
            } else {
                StringBuilder ruleList = new StringBuilder();
                for (int i = 0; i < rules.size(); i++) {
                    if (i > 0) ruleList.append("\n");
                    ruleList.append("§7- §e").append(rules.get(i));
                }
                
                source.sendSuccess(() -> 
                    Component.literal("§6Loaded Rules (" + rules.size() + "):\n" + ruleList.toString()), 
                    false
                );
            }
            return Command.SINGLE_SUCCESS;
        } else {
            source.sendFailure(
                Component.literal("§cSchematicFix Mod was not initialized correctly.")
            );
            return 0;
        }
    }
}
