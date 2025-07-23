package com.example.schematicsfix;

import net.minecraft.server.MinecraftServer;
import java.io.IOException;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import static java.nio.file.StandardWatchEventKinds.*;

public class SchematicWatcher {
    private final MinecraftServer server;
    private final Path baseDir;
    private WatchService watchService;
    private final Map<WatchKey, Path> watchKeys;
    private ExecutorService executor;
    private ScheduledExecutorService delayedExecutor;
    private volatile boolean running;
    
    // 用于跟踪文件的写入状态
    private final Map<Path, Long> fileWriteTimes = new ConcurrentHashMap<>();
    private static final long FILE_STABILIZATION_DELAY = 1000; // 1秒延迟

    public SchematicWatcher(MinecraftServer server, Path baseDir) {
        this.server = server;
        this.baseDir = baseDir;
        this.watchKeys = new HashMap<>();
    }

    public void startWatching() {
        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            this.executor = Executors.newSingleThreadExecutor();
            this.delayedExecutor = Executors.newScheduledThreadPool(2);
            this.running = true;
            
            // 初始注册
            registerDirectory(baseDir);
            
            // 开始监听线程
            executor.submit(this::watchLoop);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stopWatching() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
        if (delayedExecutor != null) {
            delayedExecutor.shutdownNow();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                // Ignore
            }
        }
        fileWriteTimes.clear();
    }

    private void watchLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                break;
            }

            Path dir = watchKeys.get(key);
            if (dir == null) continue;

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();

                if (kind == OVERFLOW) {
                    continue;
                }

                WatchEvent<Path> ev = cast(event);
                Path name = ev.context();
                Path child = dir.resolve(name);

                // 处理新目录
                if (Files.isDirectory(child)) {
                    if (kind == ENTRY_CREATE) {
                        try {
                            registerDirectory(child);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    continue;
                }

                // 处理文件事件
                if (child.toString().endsWith(".nbt")) {
                    String playerName = dir.getFileName().toString();
                    
                    if (kind == ENTRY_CREATE || kind == ENTRY_MODIFY) {
                        // 记录文件写入时间
                        fileWriteTimes.put(child, System.currentTimeMillis());
                        
                        // 延迟处理文件，确保文件写入完成
                        delayedExecutor.schedule(() -> {
                            processFileIfReady(child, playerName);
                        }, FILE_STABILIZATION_DELAY, TimeUnit.MILLISECONDS);
                    }
                }
            }

            boolean valid = key.reset();
            if (!valid) {
                watchKeys.remove(key);
                if (watchKeys.isEmpty()) {
                    break;
                }
            }
        }
    }

    private void processFileIfReady(Path file, String playerName) {
        if (!running || !Files.exists(file)) {
            fileWriteTimes.remove(file);
            return;
        }

        Long lastWriteTime = fileWriteTimes.get(file);
        if (lastWriteTime == null) {
            return;
        }

        // 检查文件是否在延迟期间被再次修改
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastWriteTime < FILE_STABILIZATION_DELAY) {
            // 文件仍在被修改，重新安排处理
            delayedExecutor.schedule(() -> {
                processFileIfReady(file, playerName);
            }, FILE_STABILIZATION_DELAY, TimeUnit.MILLISECONDS);
            return;
        }

        // 文件已稳定，提交到服务器线程处理
        server.execute(() -> {
            try {
                SchematicProcessor.processSchematicFile(server, file, playerName);
            } finally {
                fileWriteTimes.remove(file);
            }
        });
    }

    private void registerDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        
        // 注册新目录 - 监听创建和修改事件
        WatchKey key = dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY);
        watchKeys.put(key, dir);
        
        // 递归注册子目录
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (Files.isDirectory(child)) {
                    registerDirectory(child);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> WatchEvent<T> cast(WatchEvent<?> event) {
        return (WatchEvent<T>) event;
    }
}
