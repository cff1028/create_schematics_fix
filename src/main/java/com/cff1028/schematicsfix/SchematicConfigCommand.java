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
            .then(Commands.literal("keywords")
                .executes(SchematicConfigCommand::showKeywords)
            )
        );
    }
    
    private static int reloadConfig(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SchematicFixMod mod = SchematicFixMod.getInstance();
        
        if (mod != null && mod.getNbtDetector() != null) {
            boolean success = mod.getNbtDetector().reloadConfig();
            int ruleCount = mod.getNbtDetector().getRuleCount();
            int keywordCount = mod.getNbtDetector().getBannedKeywordCount();
            
            if (success) {
                source.sendSuccess(() -> 
                    Component.literal("§aConfiguration reloaded successfully! " + ruleCount + " filter rules and " + keywordCount + " banned keywords have been loaded."),
                    true
                );
                SchematicFixMod.LOGGER.info("Configuration reloaded successfully by {} with {} rules and {} banned keywords", 
                    source.getTextName(), ruleCount, keywordCount);
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
            int keywordCount = mod.getNbtDetector().getBannedKeywordCount();
            boolean fileWatcherRunning = mod.isFileWatcherRunning();
            
            source.sendSuccess(() -> 
                Component.literal("§6SchematicFix Mod condition:\n" +
                    "§7- Filter rules loaded: §e" + ruleCount + "\n" +
                    "§7- Prohibited keywords loaded: §e" + keywordCount + "\n" +
                    "§7- File Monitor Status: §e" + (fileWatcherRunning ? "运行中" : "未运行") + "\n" +
                    "§7- Use §e/schematic-config reload §7to reload configuration\n" +
                    "§7- Use §e/schematic-config keywords §7to view the list of prohibited keywords"), 
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
    
    private static int showKeywords(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SchematicFixMod mod = SchematicFixMod.getInstance();
        
        if (mod != null && mod.getNbtDetector() != null) {
            List<String> keywords = mod.getNbtDetector().getBannedKeywords();
            
            StringBuilder keywordList = new StringBuilder();
            for (int i = 0; i < keywords.size(); i++) {
                if (i > 0) keywordList.append(", ");
                keywordList.append("§c").append(keywords.get(i)).append("§7");
            }
            
            source.sendSuccess(() -> 
                Component.literal("§6List of banned keywords (" + keywords.size() + " ):\n" +
                    "§7" + keywordList.toString()), 
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
}