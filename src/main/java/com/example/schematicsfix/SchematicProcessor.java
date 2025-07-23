package com.example.schematicsfix;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SchematicProcessor {
    private static final String[] ALLOWED_TAGS = {
        "create:clipboard_pages", 
        "create:clipboard_type"
    };
    
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 300;
    private static final Logger LOGGER = Logger.getLogger(SchematicProcessor.class.getName());

    public static boolean processSchematicFile(MinecraftServer server, Path file, String playerName) {
        for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                return processSchematicFileInternal(server, file, playerName);
            } catch (Exception e) {
                if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                    if (isRetryableException(e)) {
                        try {
                            Thread.sleep(RETRY_DELAY_MS * (attempt + 1));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }
                }
                LOGGER.log(Level.WARNING, 
                    String.format("Failed to process schematic file %s for player %s: %s",
                        file.getFileName(), playerName, e.getClass().getSimpleName())
                );
            }
        }
        return false;
    }

    private static boolean isRetryableException(Exception e) {
        return e instanceof java.io.EOFException ||
               e instanceof java.util.zip.ZipException ||
               e instanceof net.minecraft.nbt.ReportedNbtException ||
               (e instanceof IOException && (
                   e.getMessage() != null && (
                       e.getMessage().contains("EOF") || 
                       e.getMessage().contains("ZLIB") ||
                       e.getMessage().contains("being used by another process") ||
                       e.getMessage().contains("Unexpected end")
                   )
               ));
    }

    private static boolean processSchematicFileInternal(MinecraftServer server, Path file, String playerName) throws Exception {
        if (!Files.exists(file)) return false;
        
        long fileSize = Files.size(file);
        if (fileSize < 10) {
            return false;
        }
        
        CompoundTag root = null;
        
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            try (FileLock lock = channel.tryLock(0L, Long.MAX_VALUE, true)) {
                if (lock == null) {
                    throw new IOException("File is being used by another process");
                }
                
                if (!isValidNbtFile(file)) {
                    throw new IOException("Invalid NBT file format");
                }
                
                try {
                    root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                } catch (net.minecraft.nbt.ReportedNbtException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof java.io.EOFException || 
                        cause instanceof java.util.zip.ZipException) {
                        throw new IOException("NBT file read error: " + cause.getMessage(), cause);
                    }
                    throw e;
                }
            }
        } catch (IOException e) {
            // 重试前关闭文件通道
            throw e;
        }
        
        if (root == null) return false;
        
        boolean modified = false;
        boolean bannedContent = false;
        
        // 递归处理整个NBT结构
        modified = processNbtRecursively(root);
        bannedContent = containsBannedKeywords(server, root);
        
        if (modified || bannedContent) {
            handleAnomalousSchematic(server, file, playerName, root, modified, bannedContent);
            return true;
        }
        
        return false;
    }
    
    private static void handleAnomalousSchematic(MinecraftServer server, Path file, String playerName, 
                                               CompoundTag root, boolean modified, boolean bannedContent) {
        try {
            Path anomalyDir = SchematicFixMod.ANOMALY_DIR.resolve(playerName);
            Files.createDirectories(anomalyDir);
            
            Path backupFile = anomalyDir.resolve(file.getFileName());
            Files.copy(file, backupFile, StandardCopyOption.REPLACE_EXISTING);
            
            String logMsg = String.format(
                "[Create Bugfix] Found anomalous schematic by player '%s': %s",
                playerName, file.getFileName()
            );
            server.execute(() -> {
                server.getPlayerList().getServer().sendSystemMessage(Component.literal(logMsg));
            });
            
            if (bannedContent) {
                Files.write(file, new byte[0]);
                return;
            }
            
            if (modified) {
                writeNbtFilesSafely(root, file);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to handle anomalous schematic: " + e.getMessage());
        }
    }
    
    private static boolean isValidNbtFile(Path file) {
        try {
            byte[] header = new byte[2];
            try (var inputStream = Files.newInputStream(file)) {
                int bytesRead = inputStream.read(header);
                if (bytesRead < 2) return false;
                
                return (header[0] & 0xFF) == 0x1F && (header[1] & 0xFF) == 0x8B;
            }
        } catch (IOException e) {
            return false;
        }
    }
    
    private static void writeNbtFilesSafely(CompoundTag root, Path file) throws IOException {
        Path tempFile = file.getParent().resolve(file.getFileName() + ".tmp");
        
        try {
            NbtIo.writeCompressed(root, tempFile);
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private static boolean processNbtRecursively(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            boolean modified = false;
            
            // 处理当前层的components标签
            if (compound.contains("components", Tag.TAG_COMPOUND)) {
                modified |= cleanComponents(compound.getCompound("components"));
            }
            
            // 递归处理所有子标签
            for (String key : compound.getAllKeys()) {
                Tag child = compound.get(key);
                modified |= processNbtRecursively(child);
            }
            return modified;
        } 
        else if (tag instanceof ListTag list) {
            boolean modified = false;
            for (Tag element : list) {
                modified |= processNbtRecursively(element);
            }
            return modified;
        }
        return false;
    }

    private static boolean cleanComponents(CompoundTag components) {
        if (components == null || components.isEmpty()) {
            return false;
        }

        List<String> keysToRemove = new ArrayList<>();
        for (String key : components.getAllKeys()) {
            boolean allowed = false;
            for (String allowedTag : ALLOWED_TAGS) {
                if (key.equals(allowedTag)) {
                    allowed = true;
                    break;
                }
            }
            
            if (!allowed) {
                keysToRemove.add(key);
            }
        }
        
        for (String key : keysToRemove) {
            components.remove(key);
        }
        return !keysToRemove.isEmpty();
    }

    private static boolean containsBannedKeywords(MinecraftServer server, CompoundTag nbt) {
        if (!Config.ENABLE_KEYWORD_CHECK.get()) return false;
        
        List<? extends String> bannedKeywords = Config.BANNED_KEYWORDS.get();
        if (bannedKeywords.isEmpty()) return false;
        
        return containsBannedKeywordsRecursive(nbt, bannedKeywords);
    }

    private static boolean containsBannedKeywordsRecursive(Tag tag, List<? extends String> bannedKeywords) {
        if (tag instanceof CompoundTag compound) {
            for (String key : compound.getAllKeys()) {
                if (containsBannedKeywordsRecursive(compound.get(key), bannedKeywords)) {
                    return true;
                }
            }
        } else if (tag instanceof ListTag list) {
            for (Tag element : list) {
                if (containsBannedKeywordsRecursive(element, bannedKeywords)) {
                    return true;
                }
            }
        } else if (tag instanceof StringTag stringTag) {
            String value = stringTag.getAsString().toLowerCase();
            for (String keyword : bannedKeywords) {
                if (value.contains(keyword.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }
}