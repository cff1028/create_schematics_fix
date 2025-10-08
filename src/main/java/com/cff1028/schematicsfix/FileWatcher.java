package com.cff1028.schematicsfix;

import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.util.thread.SidedThreadGroups;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FileWatcher {
    private static final Logger LOGGER = LogManager.getLogger();
    
    private final Path watchDir;
    private WatchService watchService;
    private final Map<Path, FileTracker> trackedFiles = new ConcurrentHashMap<>();
    private final Map<WatchKey, Path> watchKeys = new HashMap<>();
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    private final SchematicNBTDetector nbtDetector;

    public FileWatcher(SchematicNBTDetector nbtDetector) {
        this.watchDir = Paths.get("schematics/uploaded").toAbsolutePath();
        this.nbtDetector = nbtDetector;
    }

    public void start() throws IOException {
        if (running || !Config.INSTANCE.enableFileWatcher.get()) return;
        
        running = true;
        watchService = FileSystems.getDefault().newWatchService();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> 
            new Thread(SidedThreadGroups.SERVER, r, "FileWatcher-Scheduler")
        );

        registerAllDirectories(watchDir);
        
        Thread monitorThread = new Thread(SidedThreadGroups.SERVER, this::monitorFiles, "FileWatcher-Monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();

        long interval = Config.INSTANCE.stableCheckInterval.get();
        scheduler.scheduleAtFixedRate(this::checkFileStability, 
            interval, interval, TimeUnit.MILLISECONDS);
        
        LOGGER.info("Started monitoring directory: {}", watchDir);
    }

    public void stop() throws IOException {
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (watchService != null) {
            watchService.close();
        }
        trackedFiles.clear();
        watchKeys.clear();
    }

    private void registerAllDirectories(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        
        // 递归注册所有子目录
        Files.walk(dir)
            .filter(Files::isDirectory)
            .forEach(subDir -> {
                try {
                    WatchKey key = subDir.register(watchService, 
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                    watchKeys.put(key, subDir);
                    LOGGER.debug("Registered directory for monitoring: {}", subDir);
                } catch (IOException e) {
                    LOGGER.warn("Failed to register directory: {}", subDir, e);
                }
            });
    }

    private void monitorFiles() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                WatchKey key = watchService.take();
                Path dir = watchKeys.get(key);
                
                if (dir == null) {
                    key.reset();
                    continue;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path fileName = ev.context();
                    Path filePath = dir.resolve(fileName);

                    if (Files.isDirectory(filePath)) {
                        handleDirectoryEvent(kind, filePath);
                    } else if (isNbtFile(filePath)) {
                        handleFileEvent(kind, filePath, dir);
                    }
                }
                
                if (!key.reset()) {
                    watchKeys.remove(key);
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            } catch (Exception e) {
                LOGGER.error("Error in file monitor", e);
            }
        }
    }

    private void handleDirectoryEvent(WatchEvent.Kind<?> kind, Path dirPath) {
        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
            try {
                // 递归注册新创建目录及其所有子目录
                registerAllDirectories(dirPath);
                LOGGER.debug("Registered new directory and its subdirectories: {}", dirPath);
                
                // 立即检查新目录中是否已有NBT文件
                checkExistingFilesInDirectory(dirPath);
            } catch (IOException e) {
                LOGGER.warn("Failed to register new directory: {}", dirPath, e);
            }
        }
    }

    private void checkExistingFilesInDirectory(Path directory) {
        try {
            Files.walk(directory)
                .filter(Files::isRegularFile)
                .filter(this::isNbtFile)
                .forEach(filePath -> {
                    // 对于已存在的文件，模拟创建事件
                    handleFileCreate(filePath, getFolderName(filePath), filePath.getFileName().toString());
                    LOGGER.debug("Found existing NBT file during directory registration: {}", filePath);
                });
        } catch (IOException e) {
            LOGGER.warn("Failed to check existing files in directory: {}", directory, e);
        }
    }

    private String getFolderName(Path filePath) {
        // 获取相对于监控根目录的路径
        Path relativePath = watchDir.relativize(filePath.getParent());
        return relativePath.toString();
    }

    private void handleFileEvent(WatchEvent.Kind<?> kind, Path filePath, Path parentDir) {
        String folderName = getFolderName(filePath);
        String fileName = filePath.getFileName().toString();

        try {
            if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                handleFileCreate(filePath, folderName, fileName);
            } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                handleFileModify(filePath, folderName, fileName);
            } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                trackedFiles.remove(filePath);
                LOGGER.debug("File {} in folder {} has been deleted", fileName, folderName);
            }
        } catch (Exception e) {
            LOGGER.warn("Error handling file event for: {}", filePath, e);
        }
    }

    private void handleFileCreate(Path filePath, String folderName, String fileName) {
        FileTracker tracker = new FileTracker(filePath);
        trackedFiles.put(filePath, tracker);
        LOGGER.info("\033[94mDetected new file {} in folder {}\033[0m", fileName, folderName);
    }

    private void handleFileModify(Path filePath, String folderName, String fileName) {
        FileTracker tracker = trackedFiles.get(filePath);
        
        if (tracker == null) {
            tracker = new FileTracker(filePath);
            trackedFiles.put(filePath, tracker);
            LOGGER.info("\033[94mDetected existing file {} in folder {}\033[0m", fileName, folderName);
        } else {
            tracker.update();
            LOGGER.debug("File {} in folder {} has been modified", fileName, folderName);
        }
    }

    private void checkFileStability() {
        long currentTime = System.currentTimeMillis();
        long maxStableTime = Config.INSTANCE.maxStableTime.get();
        
        trackedFiles.entrySet().removeIf(entry -> {
            Path filePath = entry.getKey();
            FileTracker tracker = entry.getValue();
            
            try {
                if (!Files.exists(filePath)) {
                    LOGGER.debug("File {} has been deleted, removing from tracking", filePath.getFileName());
                    return true;
                }

                long currentSize = Files.size(filePath);
                long timeSinceLastChange = currentTime - tracker.getLastModifiedTime();
                
                if (currentSize != tracker.getLastSize()) {
                    tracker.updateSize(currentSize);
                    tracker.setNotifiedStable(false);
                    LOGGER.debug("File {} size changed to {} bytes", filePath.getFileName(), currentSize);
                    return false;
                }
                
                if (timeSinceLastChange >= maxStableTime && !tracker.hasNotifiedStable()) {
                    String folderName = getFolderName(filePath);
                    String fileName = filePath.getFileName().toString().replace(".nbt", "");
                    
                    LOGGER.info("\033[94mFile {} in folder {} upload completed (stable for {}ms), starting anomaly detection\033[0m", 
                        fileName, folderName, timeSinceLastChange);
                    
                    if (Config.INSTANCE.autoCleanAnomalies.get()) {
                        SchematicNBTDetector.DetectionResult result = nbtDetector.detectAnomalies(folderName, fileName);
                        if (result.hasAnomalies) {
                            LOGGER.warn("\033[91mAnomalies detected and processed: {}\033[0m", result.message);
                        } else {
                            LOGGER.info("\033[92mSchematic validation passed: {}\033[0m", result.message);
                        }
                    }
                    
                    tracker.setNotifiedStable(true);
                }
                
                boolean shouldRemove = tracker.hasNotifiedStable() && timeSinceLastChange > maxStableTime * 2;
                if (shouldRemove) {
                    LOGGER.debug("File {} has been stable for long enough, removing from tracking", filePath.getFileName());
                }
                return shouldRemove;
                
            } catch (IOException e) {
                LOGGER.warn("Error checking file stability: {}", filePath, e);
                return true;
            }
        });
    }

    private boolean isNbtFile(Path filePath) {
        String fileName = filePath.getFileName().toString();
        return fileName.toLowerCase().endsWith(".nbt");
    }

    private static class FileTracker {
        private final Path filePath;
        private long lastModifiedTime;
        private long lastSize;
        private boolean notifiedStable = false;

        public FileTracker(Path filePath) {
            this.filePath = filePath;
            update();
        }

        public void update() {
            try {
                this.lastModifiedTime = System.currentTimeMillis();
                this.lastSize = Files.size(filePath);
            } catch (IOException e) {
                this.lastSize = 0;
            }
        }

        public void updateSize(long newSize) {
            this.lastSize = newSize;
            this.lastModifiedTime = System.currentTimeMillis();
        }

        public long getLastModifiedTime() {
            return lastModifiedTime;
        }

        public long getLastSize() {
            return lastSize;
        }

        public boolean hasNotifiedStable() {
            return notifiedStable;
        }

        public void setNotifiedStable(boolean notifiedStable) {
            this.notifiedStable = notifiedStable;
        }
    }
}
