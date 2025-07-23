package com.example.schematicsfix.commands;

import com.example.schematicsfix.SchematicFixMod;
import com.example.schematicsfix.SchematicProcessor;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

public class ScanAllCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("schematic-all")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> scanAllSchematics(ctx.getSource()))
        );
    }

    private static int scanAllSchematics(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        Path uploadedDir = SchematicFixMod.UPLOADED_DIR;
        
        if (!Files.exists(uploadedDir)) {
            source.sendFailure(Component.literal("Uploaded schematics directory not found!"));
            return 0;
        }
        
        source.sendSuccess(() -> Component.literal("Starting schematic scan..."), true);
        
        AtomicInteger processedCount = new AtomicInteger();
        AtomicInteger anomalyCount = new AtomicInteger();
        
        server.execute(() -> {
            try (var paths = Files.walk(uploadedDir)) {
                paths.filter(Files::isRegularFile)
                     .filter(p -> p.toString().endsWith(".nbt"))
                     .forEach(file -> {
                         Path playerDir = file.getParent().getFileName();
                         if (playerDir != null) {
                             boolean modified = SchematicProcessor.processSchematicFile(
                                 server, file, playerDir.toString()
                             );
                             
                             if (modified) {
                                 anomalyCount.incrementAndGet();
                             }
                             processedCount.incrementAndGet();
                         }
                     });
                
                String resultMsg = String.format(
                    "Scanned %d schematics, found %d anomalies",
                    processedCount.get(), anomalyCount.get()
                );
                source.sendSuccess(() -> Component.literal(resultMsg), true);
            } catch (IOException e) {
                source.sendFailure(Component.literal("Error scanning schematics: " + e.getMessage()));
            }
        });
        
        return 1;
    }
}