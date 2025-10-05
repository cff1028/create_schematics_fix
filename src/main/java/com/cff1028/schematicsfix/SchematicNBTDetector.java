package com.cff1028.schematicsfix;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import net.minecraft.nbt.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public class SchematicNBTDetector {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> DEFAULT_IGNORED_KEYS = Set.of("count", "id");
    
    private final Path configPath;
    private final List<FilterRule> filterRules;
    private final List<String> bannedKeywords;
    
    public SchematicNBTDetector() {
        this.configPath = Paths.get("config/schematicsfix.jsonl").toAbsolutePath();
        this.filterRules = new ArrayList<>();
        this.bannedKeywords = new ArrayList<>();
        loadConfig();
    }
    
    private void loadConfig() {
        if (!Files.exists(configPath)) {
            try {
                Files.createDirectories(configPath.getParent());
                copyDefaultConfig();
                LOGGER.info("Created default config file from assets: {}", configPath);
            } catch (IOException e) {
                LOGGER.error("Failed to create config file: {}", configPath, e);
            }
            return;
        }
        
        reloadConfigInternal();
    }
    
    /**
     * 获取当前加载的禁止关键词列表
     */
    public List<String> getBannedKeywords() {
        return new ArrayList<>(bannedKeywords);
    }

    /**
     * 重新加载配置文件
     * @return 是否成功加载
     */
    public boolean reloadConfig() {
        LOGGER.info("Reloading schematic filter configuration...");
        boolean success = reloadConfigInternal();
        loadBannedKeywords();
        return success;
    }
    
    private void loadBannedKeywords() {
        bannedKeywords.clear();
        try {
            List<? extends String> keywords = Config.INSTANCE.bannedKeywords.get();
            bannedKeywords.addAll(keywords);
            LOGGER.info("Loaded {} banned keywords: {}", bannedKeywords.size(), bannedKeywords);
        } catch (Exception e) {
            LOGGER.error("Failed to load banned keywords from config", e);
            // 使用默认值
            bannedKeywords.addAll(Arrays.asList("clickEvent", "run_command", "create:filter", "create:attribute_filter"));
        }
    }
    
    private boolean reloadConfigInternal() {
        filterRules.clear();
        loadBannedKeywords();
        
        int lineNumber = 0;
        int loadedRules = 0;
        
        try (BufferedReader reader = Files.newBufferedReader(configPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                if (line.isEmpty()) continue;
                
                try {
                    JsonObject ruleJson = JsonParser.parseString(line).getAsJsonObject();
                    FilterRule rule = parseFilterRule(ruleJson);
                    if (rule != null) {
                        filterRules.add(rule);
                        loadedRules++;
                        LOGGER.debug("Loaded filter rule for id: {}", rule.id);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to parse rule at line {}: {}", lineNumber, line, e);
                }
            }
            LOGGER.info("Loaded {} filter rules from config", loadedRules);
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to load config file: {}", configPath, e);
            return false;
        }
    }
    
    private void copyDefaultConfig() {
        InputStream inputStream = null;
        try {
            inputStream = getClass().getResourceAsStream("/assets/schematicsfix/schematicsfix.jsonl");
            
            if (inputStream == null) {
                inputStream = getClass().getClassLoader().getResourceAsStream("assets/schematicsfix/schematicsfix.jsonl");
            }
            
            if (inputStream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                     BufferedWriter writer = Files.newBufferedWriter(configPath)) {
                    
                    String line;
                    while ((line = reader.readLine()) != null) {
                        writer.write(line);
                        writer.newLine();
                    }
                    LOGGER.info("Successfully copied default config from assets");
                }
            } else {
                LOGGER.warn("Default config not found in assets, creating example config file");
                createExampleConfig();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to copy default config from assets", e);
            try {
                createExampleConfig();
            } catch (IOException ex) {
                LOGGER.error("Failed to create example config file", ex);
            }
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    LOGGER.error("Error closing input stream", e);
                }
            }
        }
    }
    
    private void createExampleConfig() throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(configPath)) {
            // 创建示例配置
            JsonObject chestRule = new JsonObject();
            chestRule.addProperty("id", "minecraft:chest");
            
            JsonArray blacklist = new JsonArray();
            blacklist.add("Items");
            blacklist.add("Lock");
            blacklist.add("LootTable");
            chestRule.add("blacklist", blacklist);
            
            writer.write(GSON.toJson(chestRule));
            writer.newLine();
            
            JsonObject shulkerRule = new JsonObject();
            shulkerRule.addProperty("id", "minecraft:shulker_box");
            shulkerRule.add("blacklist", blacklist);
            
            writer.write(GSON.toJson(shulkerRule));
            writer.newLine();
            
            LOGGER.info("Created example configuration");
        }
    }
    
    private FilterRule parseFilterRule(JsonObject json) {
        if (!json.has("id")) {
            return null;
        }
        
        String id = json.get("id").getAsString();
        FilterRule rule = new FilterRule(id);
        
        if (json.has("whitelist") && json.has("blacklist")) {
            LOGGER.warn("Rule for id '{}' has both whitelist and blacklist, ignoring this rule", id);
            return null;
        }
        
        if (json.has("whitelist")) {
            rule.whitelist = new HashSet<>();
            json.get("whitelist").getAsJsonArray().forEach(element -> 
                rule.whitelist.add(element.getAsString()));
        }
        
        if (json.has("blacklist")) {
            rule.blacklist = new HashSet<>();
            json.get("blacklist").getAsJsonArray().forEach(element -> 
                rule.blacklist.add(element.getAsString()));
        }
        
        if (json.has("include")) {
            rule.include = parseIncludeRules(json.get("include").getAsJsonObject());
        }
        
        return rule;
    }
    
    private Map<String, FilterRule> parseIncludeRules(JsonObject includeJson) {
        Map<String, FilterRule> includeRules = new HashMap<>();
        
        for (String key : includeJson.keySet()) {
            JsonObject ruleJson = includeJson.get(key).getAsJsonObject();
            FilterRule includeRule = new FilterRule(null);
            
            if (ruleJson.has("whitelist") && ruleJson.has("blacklist")) {
                LOGGER.warn("Include rule has both whitelist and blacklist, ignoring this include rule");
                continue;
            }
            
            if (ruleJson.has("whitelist")) {
                includeRule.whitelist = new HashSet<>();
                ruleJson.get("whitelist").getAsJsonArray().forEach(element -> 
                    includeRule.whitelist.add(element.getAsString()));
            }
            
            if (ruleJson.has("blacklist")) {
                includeRule.blacklist = new HashSet<>();
                ruleJson.get("blacklist").getAsJsonArray().forEach(element -> 
                    includeRule.blacklist.add(element.getAsString()));
            }
            
            if (ruleJson.has("include")) {
                includeRule.include = parseIncludeRules(ruleJson.get("include").getAsJsonObject());
            }
            
            includeRules.put(key, includeRule);
        }
        
        return includeRules;
    }
    
    /**
     * 获取当前加载的规则数量
     */
    public int getRuleCount() {
        return filterRules.size();
    }
    
    /**
     * 获取当前加载的禁止关键词数量
     */
    public int getBannedKeywordCount() {
        return bannedKeywords.size();
    }
    
    public DetectionResult detectAnomalies(String playerName, String fileName) {
        Path schematicPath = Paths.get("schematics/uploaded", playerName, fileName + ".nbt").toAbsolutePath();
        Path anomalyPath = Paths.get("schematics/anomaly", playerName, fileName + ".nbt").toAbsolutePath();
        Path bannedPath = Paths.get("schematics/banned", playerName, fileName + ".nbt").toAbsolutePath();
        
        if (!Files.exists(schematicPath)) {
            return new DetectionResult(false, "Schematic file not found: " + schematicPath);
        }
        
        try {
            CompoundTag nbt = readNbtFile(schematicPath);
            if (nbt == null) {
                return new DetectionResult(false, "Failed to read NBT file - unsupported compression or corrupted file");
            }
            
            // 首先检查禁止关键词
            BannedKeywordResult bannedResult = checkForBannedKeywords(nbt, playerName, fileName);
            if (bannedResult.found) {
                // 创建禁止目录并移动文件
                Files.createDirectories(bannedPath.getParent());
                Files.move(schematicPath, bannedPath, StandardCopyOption.REPLACE_EXISTING);
                
                LOGGER.warn("BANNED KEYWORD DETECTED: File {}.nbt from player {} contained banned keyword '{}'. File moved to banned directory.", 
                    fileName, playerName, bannedResult.keyword);
                return new DetectionResult(true, 
                    String.format("Banned keyword '%s' detected. Schematic has been moved to banned directory.", bannedResult.keyword));
            }
            
            // 如果没有禁止关键词，继续常规处理
            boolean modified = processBlocks(nbt, playerName, fileName);
            
            if (modified && Config.INSTANCE.backupAnomalousFiles.get()) {
                // Create anomaly directory if needed
                Files.createDirectories(anomalyPath.getParent());
                // Copy original to anomaly directory
                Files.copy(schematicPath, anomalyPath, StandardCopyOption.REPLACE_EXISTING);
                // Write cleaned version to original location
                writeNbtFile(nbt, schematicPath);
                
                LOGGER.warn("Anomalous schematic {}.nbt has been detected from player {}. Original backed up.", fileName, playerName);
                return new DetectionResult(true, 
                    "Anomalies detected and cleaned. Original saved to anomaly directory.");
            } else if (modified) {
                // Write cleaned version to original location without backup
                writeNbtFile(nbt, schematicPath);
                LOGGER.warn("Anomalous schematic {}.nbt has been detected from player {}.", fileName, playerName);
                return new DetectionResult(true, "Anomalies detected and cleaned.");
            } else {
                return new DetectionResult(false, "No anomalies detected in schematic.");
            }
            
        } catch (IOException e) {
            LOGGER.error("Error processing schematic file: {}", schematicPath, e);
            return new DetectionResult(false, "Error processing schematic: " + e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Unexpected error processing schematic file: {}", schematicPath, e);
            return new DetectionResult(false, "Unexpected error: " + e.getMessage());
        }
    }
    
    /**
     * 检查NBT数据中是否包含禁止的关键词
     */
    private BannedKeywordResult checkForBannedKeywords(CompoundTag nbt, String playerName, String fileName) {
        return checkTagForBannedKeywords(nbt, "");
    }
    
    private BannedKeywordResult checkTagForBannedKeywords(Tag tag, String path) {
        if (tag instanceof CompoundTag compound) {
            for (String key : compound.getAllKeys()) {
                Tag child = compound.get(key);
                BannedKeywordResult result = checkTagForBannedKeywords(child, path + "." + key);
                if (result.found) {
                    return result;
                }
            }
        } else if (tag instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                Tag child = list.get(i);
                BannedKeywordResult result = checkTagForBannedKeywords(child, path + "[" + i + "]");
                if (result.found) {
                    return result;
                }
            }
        } else if (tag instanceof StringTag stringTag) {
            String value = stringTag.getAsString();
            for (String keyword : bannedKeywords) {
                if (value.contains(keyword)) {
                    LOGGER.debug("Found banned keyword '{}' in NBT path {} with value: {}", keyword, path, value);
                    return new BannedKeywordResult(true, keyword, path, value);
                }
            }
        } else if (tag instanceof ByteArrayTag byteArray) {
            byte[] bytes = byteArray.getAsByteArray();
            String byteString = Arrays.toString(bytes);
            for (String keyword : bannedKeywords) {
                if (byteString.contains(keyword)) {
                    LOGGER.debug("Found banned keyword '{}' in byte array at path {}", keyword, path);
                    return new BannedKeywordResult(true, keyword, path, "[byte array]");
                }
            }
        } else if (tag instanceof IntArrayTag intArray) {
            int[] ints = intArray.getAsIntArray();
            String intString = Arrays.toString(ints);
            for (String keyword : bannedKeywords) {
                if (intString.contains(keyword)) {
                    LOGGER.debug("Found banned keyword '{}' in int array at path {}", keyword, path);
                    return new BannedKeywordResult(true, keyword, path, "[int array]");
                }
            }
        } else if (tag instanceof LongArrayTag longArray) {
            long[] longs = longArray.getAsLongArray();
            String longString = Arrays.toString(longs);
            for (String keyword : bannedKeywords) {
                if (longString.contains(keyword)) {
                    LOGGER.debug("Found banned keyword '{}' in long array at path {}", keyword, path);
                    return new BannedKeywordResult(true, keyword, path, "[long array]");
                }
            }
        }
        
        return new BannedKeywordResult(false, null, null, null);
    }
    
    /**
     * 读取NBT文件，支持多种压缩格式
     */
    private CompoundTag readNbtFile(Path filePath) throws IOException {
        // 方法1: 尝试标准读取（未压缩或内部处理压缩）
        try {
            return NbtIo.read(filePath);
        } catch (Exception e1) {
            LOGGER.debug("Standard NBT read failed, trying compressed formats: {}", e1.getMessage());
        }
        
        // 方法2: 尝试GZIP压缩格式
        try (InputStream is = Files.newInputStream(filePath);
             GZIPInputStream gzipIs = new GZIPInputStream(is);
             DataInputStream dataIs = new DataInputStream(gzipIs)) {
            return NbtIo.read(dataIs);
        } catch (Exception e2) {
            LOGGER.debug("GZIP NBT read failed: {}", e2.getMessage());
        }
        
        // 方法3: 尝试zlib压缩格式
        try (InputStream is = Files.newInputStream(filePath);
             InflaterInputStream inflaterIs = new InflaterInputStream(is);
             DataInputStream dataIs = new DataInputStream(inflaterIs)) {
            return NbtIo.read(dataIs);
        } catch (Exception e3) {
            LOGGER.debug("Zlib NBT read failed: {}", e3.getMessage());
        }
        
        // 方法4: 尝试直接读取（作为未压缩）
        try (InputStream is = Files.newInputStream(filePath);
             DataInputStream dataIs = new DataInputStream(is)) {
            return NbtIo.read(dataIs);
        } catch (Exception e4) {
            LOGGER.debug("Direct input stream NBT read failed: {}", e4.getMessage());
        }
        
        // 所有方法都失败
        LOGGER.error("All NBT reading methods failed for file: {}", filePath);
        return null;
    }
    
    /**
     * 写入NBT文件，使用标准压缩格式
     */
    private void writeNbtFile(CompoundTag nbt, Path filePath) throws IOException {
        // 使用标准写入方法，让Minecraft处理压缩
        NbtIo.write(nbt, filePath);
    }
    
    private boolean processBlocks(CompoundTag rootNbt, String playerName, String fileName) {
        if (!rootNbt.contains("blocks", 9)) { // 9 = ListTag
            LOGGER.debug("No blocks found in NBT file {}.nbt", fileName);
            return false;
        }
        
        ListTag blocks = rootNbt.getList("blocks", 10); // 10 = CompoundTag
        boolean modified = false;
        
        LOGGER.debug("Processing {} blocks in file {}.nbt", blocks.size(), fileName);
        
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            if (block.contains("nbt", 10)) { // 10 = CompoundTag
                CompoundTag nbtData = block.getCompound("nbt");
                modified |= processNbtData(nbtData, playerName, fileName);
            }
        }
        
        return modified;
    }
    
    private boolean processNbtData(CompoundTag nbtData, String playerName, String fileName) {
        boolean modified = false;
        
        // Check all top-level keys that might contain item data
        for (String key : nbtData.getAllKeys()) {
            if (nbtData.get(key) instanceof CompoundTag itemData) {
                modified |= processItemData(itemData, key, playerName, fileName);
            }
        }
        
        return modified;
    }
    
    private boolean processItemData(CompoundTag itemData, String parentKey, String playerName, String fileName) {
        if (!itemData.contains("id", 8)) { // 8 = StringTag
            return false;
        }
        
        String itemId = itemData.getString("id");
        FilterRule matchingRule = findMatchingRule(itemId);
        
        if (matchingRule == null) {
            return false;
        }
        
        LOGGER.debug("Applying filter rule for item '{}' in file {}.nbt", itemId, fileName);
        return applyFilterRule(itemData, matchingRule, playerName, fileName, itemId);
    }
    
    private FilterRule findMatchingRule(String itemId) {
        for (FilterRule rule : filterRules) {
            if (rule.id.equals(itemId)) {
                return rule;
            }
        }
        return null;
    }
    
    private boolean applyFilterRule(CompoundTag data, FilterRule rule, 
                                  String playerName, String fileName, String itemId) {
        boolean modified = false;
        
        // Apply main rule to current level
        Set<String> keysToRemove = new HashSet<>();
        for (String key : data.getAllKeys()) {
            if (DEFAULT_IGNORED_KEYS.contains(key)) {
                continue; // Skip count and id by default
            }
            
            if (rule.whitelist != null) {
                if (!rule.whitelist.contains(key)) {
                    keysToRemove.add(key);
                }
            } else if (rule.blacklist != null) {
                if (rule.blacklist.contains(key)) {
                    keysToRemove.add(key);
                }
            }
        }
        
        // Remove invalid keys
        for (String key : keysToRemove) {
            data.remove(key);
            modified = true;
            LOGGER.info("Removed key '{}' from item '{}' in file {}.nbt", key, itemId, fileName);
        }
        
        // Apply include rules recursively
        if (rule.include != null) {
            for (Map.Entry<String, FilterRule> includeEntry : rule.include.entrySet()) {
                String includeKey = includeEntry.getKey();
                FilterRule includeRule = includeEntry.getValue();
                
                if (data.contains(includeKey)) {
                    if (data.get(includeKey) instanceof CompoundTag includeData) {
                        modified |= applyIncludeRule(includeData, includeRule, playerName, fileName, itemId, includeKey);
                    }
                }
            }
        }
        
        return modified;
    }
    
    private boolean applyIncludeRule(CompoundTag data, FilterRule rule, 
                                   String playerName, String fileName, String itemId, String path) {
        boolean modified = false;
        
        // Apply rule to current include level
        Set<String> keysToRemove = new HashSet<>();
        for (String key : data.getAllKeys()) {
            if (rule.whitelist != null) {
                if (!rule.whitelist.contains(key)) {
                    keysToRemove.add(key);
                }
            } else if (rule.blacklist != null) {
                if (rule.blacklist.contains(key)) {
                    keysToRemove.add(key);
                }
            }
        }
        
        // Remove invalid keys
        for (String key : keysToRemove) {
            data.remove(key);
            modified = true;
            LOGGER.info("Removed key '{}' from path '{}' in item '{}' in file {}.nbt", 
                key, path, itemId, fileName);
        }
        
        // Recursively apply nested include rules
        if (rule.include != null) {
            for (Map.Entry<String, FilterRule> includeEntry : rule.include.entrySet()) {
                String includeKey = includeEntry.getKey();
                FilterRule includeRule = includeEntry.getValue();
                
                if (data.contains(includeKey)) {
                    if (data.get(includeKey) instanceof CompoundTag includeData) {
                        modified |= applyIncludeRule(includeData, includeRule, playerName, fileName, itemId, path + "." + includeKey);
                    }
                }
            }
        }
        
        return modified;
    }
    
    private static class FilterRule {
        final String id;
        Set<String> whitelist;
        Set<String> blacklist;
        Map<String, FilterRule> include;
        
        FilterRule(String id) {
            this.id = id;
        }
    }
    
    public static class DetectionResult {
        public final boolean hasAnomalies;
        public final String message;
        
        public DetectionResult(boolean hasAnomalies, String message) {
            this.hasAnomalies = hasAnomalies;
            this.message = message;
        }
    }
    
    private static class BannedKeywordResult {
        public final boolean found;
        public final String keyword;
        public final String path;
        public final String value;
        
        public BannedKeywordResult(boolean found, String keyword, String path, String value) {
            this.found = found;
            this.keyword = keyword;
            this.path = path;
            this.value = value;
        }
    }
}