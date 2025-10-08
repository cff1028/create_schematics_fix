package com.cff1028.schematicsfix;

import java.util.stream.Collectors;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import net.minecraft.nbt.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

public class SchematicNBTDetector {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Yaml YAML = createYaml();
    
    private final Path configPath;
    private final List<FilterRule> filterRules;
    
    public SchematicNBTDetector() {
        this.configPath = Paths.get("config/schematicsfix.yaml").toAbsolutePath();
        this.filterRules = new ArrayList<>();
        loadConfig();
    }
    
    private static Yaml createYaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        return new Yaml(options);
    }
    
    private void loadConfig() {
        if (!Files.exists(configPath)) {
            try {
                Files.createDirectories(configPath.getParent());
                copyDefaultConfig();
                LOGGER.info("\033[94mCreated default config file from assets: {}\033[0m", configPath);
            } catch (IOException e) {
                LOGGER.error("\033[91mFailed to create config file: {}\033[0m", configPath, e);
            }
            return;
        }
        
        reloadConfigInternal();
    }

    /**
     * 重新加载配置文件
     * @return 是否成功加载
     */
    public boolean reloadConfig() {
        LOGGER.info("\033[94mReloading schematic filter configuration...\033[0m");
        return reloadConfigInternal();
    }
    
    /**
     * 获取当前加载的规则名称列表
     */
    public List<String> getRuleNames() {
        return filterRules.stream()
                .map(rule -> rule.name)
                .collect(java.util.stream.Collectors.toList());
    }

    private boolean reloadConfigInternal() {
        filterRules.clear();
        
        try (InputStream input = Files.newInputStream(configPath)) {
            Map<String, Object> config = YAML.load(input);
            
            if (config == null || !config.containsKey("rules")) {
                LOGGER.warn("\033[91mNo rules found in config file\033[0m");
                return false;
            }
            
            List<Map<String, Object>> rules = (List<Map<String, Object>>) config.get("rules");
            int loadedRules = 0;
            
            for (Map<String, Object> ruleData : rules) {
                try {
                    FilterRule rule = parseFilterRule(ruleData);
                    if (rule != null) {
                        filterRules.add(rule);
                        loadedRules++;
                        LOGGER.debug("Loaded filter rule: {}", rule.name);
                    }
                } catch (Exception e) {
                    LOGGER.warn("\033[91mFailed to parse rule: {}\033[0m", ruleData, e);
                }
            }
            
            LOGGER.info("\033[94mLoaded {} filter rules from config\033[0m", loadedRules);
            return true;
        } catch (IOException e) {
            LOGGER.error("\033[91mFailed to load config file: {}\033[0m", configPath, e);
            return false;
        }
    }
    
    private void copyDefaultConfig() {
        InputStream inputStream = null;
        try {
            inputStream = getClass().getResourceAsStream("/assets/schematicsfix/schematicsfix.yaml");
            
            if (inputStream == null) {
                inputStream = getClass().getClassLoader().getResourceAsStream("assets/schematicsfix/schematicsfix.yaml");
            }
            
            if (inputStream != null) {
                Files.copy(inputStream, configPath, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("\033[94mSuccessfully copied default config from assets\033[0m");
            } else {
                LOGGER.warn("\033[91mDefault config not found in assets, creating example config file\033[0m");
                createExampleConfig();
            }
        } catch (Exception e) {
            LOGGER.error("\033[91mFailed to copy default config from assets\033[0m", e);
            try {
                createExampleConfig();
            } catch (IOException ex) {
                LOGGER.error("\033[91mFailed to create example config file\033[0m", ex);
            }
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    LOGGER.error("\033[91mError closing input stream\033[0m", e);
                }
            }
        }
    }
    
    private void createExampleConfig() throws IOException {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("version", "1.0");
        
        List<Map<String, Object>> rules = new ArrayList<>();
        
        // 示例规则1: valve_handle 清理
        Map<String, Object> valveRule = new LinkedHashMap<>();
        valveRule.put("name", "valve_handle_capacity_cleanup");
        valveRule.put("target_path", "blocks[*].nbt");
        
        List<Map<String, Object>> valveConditions = new ArrayList<>();
        Map<String, Object> valveCondition = new LinkedHashMap<>();
        valveCondition.put("type", "field_equals");
        valveCondition.put("path", "id");
        valveCondition.put("value", "create:valve_handle");
        valveConditions.add(valveCondition);
        valveRule.put("conditions", valveConditions);
        
        List<Map<String, Object>> valveActions = new ArrayList<>();
        
        Map<String, Object> capacityAction = new LinkedHashMap<>();
        capacityAction.put("type", "conditional_remove");
        capacityAction.put("target_path", "Network.Capacity");
        Map<String, Object> capacityCondition = new LinkedHashMap<>();
        capacityCondition.put("type", "field_not_equals");
        capacityCondition.put("path", "Network.Capacity");
        capacityCondition.put("value", 256.0);
        capacityAction.put("condition", capacityCondition);
        capacityAction.put("remove_strategy", "field_only");
        valveActions.add(capacityAction);
        
        Map<String, Object> speedAction = new LinkedHashMap<>();
        speedAction.put("type", "conditional_remove");
        speedAction.put("target_path", "Speed");
        Map<String, Object> speedCondition = new LinkedHashMap<>();
        speedCondition.put("type", "field_not_equals");
        speedCondition.put("path", "Speed");
        speedCondition.put("value", 32.0);
        speedAction.put("condition", speedCondition);
        speedAction.put("remove_strategy", "field_only");
        valveActions.add(speedAction);
        
        valveRule.put("cleanup_actions", valveActions);
        rules.add(valveRule);
        
        config.put("rules", rules);
        
        try (FileWriter writer = new FileWriter(configPath.toFile())) {
            YAML.dump(config, writer);
        }
        
        LOGGER.info("Created example configuration");
    }
    
    private FilterRule parseFilterRule(Map<String, Object> ruleData) {
        if (!ruleData.containsKey("name") || !ruleData.containsKey("target_path")) {
            LOGGER.warn("\033[91mRule missing required fields: name and target_path\033[0m");
            return null;
        }
        
        String name = (String) ruleData.get("name");
        String targetPath = (String) ruleData.get("target_path");
        FilterRule rule = new FilterRule(name, targetPath);
        
        // 解析条件
        if (ruleData.containsKey("conditions")) {
            List<Map<String, Object>> conditions = (List<Map<String, Object>>) ruleData.get("conditions");
            for (Map<String, Object> conditionData : conditions) {
                Condition condition = parseCondition(conditionData);
                if (condition != null) {
                    rule.conditions.add(condition);
                }
            }
        }
        
        // 解析清理操作
        if (ruleData.containsKey("cleanup_actions")) {
            List<Map<String, Object>> actions = (List<Map<String, Object>>) ruleData.get("cleanup_actions");
            for (Map<String, Object> actionData : actions) {
                CleanupAction action = parseCleanupAction(actionData);
                if (action != null) {
                    rule.cleanupActions.add(action);
                }
            }
        }
        
        return rule;
    }
    
    private Condition parseCondition(Map<String, Object> conditionData) {
        if (!conditionData.containsKey("type")) {
            return null;
        }
        
        String type = (String) conditionData.get("type");
        Condition condition = new Condition(type);
        
        condition.path = (String) conditionData.get("path");
        condition.value = conditionData.get("value");
        
        return condition;
    }
    
    private CleanupAction parseCleanupAction(Map<String, Object> actionData) {
        if (!actionData.containsKey("type") || !actionData.containsKey("target_path")) {
            return null;
        }
        
        String type = (String) actionData.get("type");
        String targetPath = (String) actionData.get("target_path");
        CleanupAction action = new CleanupAction(type, targetPath);
        
        action.removeStrategy = (String) actionData.get("remove_strategy");
        
        if (actionData.containsKey("condition")) {
            Map<String, Object> conditionData = (Map<String, Object>) actionData.get("condition");
            action.condition = parseCondition(conditionData);
        }
        
        if (actionData.containsKey("remove_pattern")) {
            action.removePattern = Pattern.compile((String) actionData.get("remove_pattern"));
        }
        
        return action;
    }
    
    /**
     * 获取当前加载的规则数量
     */
    public int getRuleCount() {
        return filterRules.size();
    }
    
    public DetectionResult detectAnomalies(String playerName, String fileName) {
        Path schematicPath = Paths.get("schematics/uploaded", playerName, fileName + ".nbt").toAbsolutePath();
        Path anomalyPath = Paths.get("schematics/anomaly", playerName, fileName + ".nbt").toAbsolutePath();
        
        if (!Files.exists(schematicPath)) {
            return new DetectionResult(false, "Schematic file not found: " + schematicPath);
        }
        
        try {
            CompoundTag nbt = readNbtFile(schematicPath);
            if (nbt == null) {
                return new DetectionResult(false, "Failed to read NBT file - unsupported compression or corrupted file");
            }
            
            boolean modified = processWithRules(nbt, playerName, fileName);
            
            if (modified) {
                // 写入清理后的NBT到原文件
                writeNbtFile(nbt, schematicPath);
                
                if (Config.INSTANCE.backupAnomalousFiles.get()) {
                    // 创建异常文件备份
                    Files.createDirectories(anomalyPath.getParent());
                    Files.copy(schematicPath, anomalyPath, StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.warn("\033[91mRule-based anomalies detected and cleaned in {}.nbt from player {}. Original backed up.\033[0m", fileName, playerName);
                    if (modified && Config.INSTANCE.notifyPlayerOnAnomaly.get()) {
                        notifyPlayer(playerName, fileName);
                    }
                    return new DetectionResult(true, "Rule-based anomalies detected and cleaned. Original backed up to anomaly directory.");
                } else {
                    LOGGER.warn("\033[91mRule-based anomalies detected and cleaned in {}.nbt from player {}.\033[0m", fileName, playerName);
                    if (modified && Config.INSTANCE.notifyPlayerOnAnomaly.get()) {
                        notifyPlayer(playerName, fileName);
                    }
                    return new DetectionResult(true, "Rule-based anomalies detected and cleaned.");
                }
            } else {
                return new DetectionResult(false, "No anomalies detected in schematic.");
            }
            
        } catch (IOException e) {
            LOGGER.error("\033[91mError processing schematic file: {}\033[0m", schematicPath, e);
            return new DetectionResult(false, "Error processing schematic: " + e.getMessage());
        } catch (Exception e) {
            LOGGER.error("\033[91mUnexpected error processing schematic file: {}\033[0m", schematicPath, e);
            return new DetectionResult(false, "Unexpected error: " + e.getMessage());
        }
    }
    
    private boolean processWithRules(CompoundTag rootNbt, String playerName, String fileName) {
        boolean modified = false;
        
        for (FilterRule rule : filterRules) {
            modified |= applyRule(rootNbt, rule, playerName, fileName);
        }
        
        return modified;
    }
    
    private boolean applyRule(CompoundTag root, FilterRule rule, String playerName, String fileName) {
        boolean modified = false;
        
        // 解析目标路径
        String[] pathParts = rule.targetPath.split("\\.");
        List<CompoundTag> targets = findTargets(root, pathParts, 0);
        
        for (CompoundTag target : targets) {
            // 检查条件
            if (checkConditions(target, rule.conditions)) {
                // 执行清理操作
                for (CleanupAction action : rule.cleanupActions) {
                    modified |= applyCleanupAction(target, action, playerName, fileName, rule.name);
                }
            }
        }
        
        return modified;
    }
    
    private List<CompoundTag> findTargets(Tag current, String[] pathParts, int depth) {
        List<CompoundTag> results = new ArrayList<>();
        
        if (depth >= pathParts.length) {
            if (current instanceof CompoundTag) {
                results.add((CompoundTag) current);
            }
            return results;
        }
        
        String part = pathParts[depth];
        
        if (part.equals("*")) {
            // 通配符 - 遍历所有元素
            if (current instanceof CompoundTag compound) {
                for (String key : compound.getAllKeys()) {
                    Tag child = compound.get(key);
                    results.addAll(findTargets(child, pathParts, depth + 1));
                }
            } else if (current instanceof ListTag list) {
                for (int i = 0; i < list.size(); i++) {
                    Tag child = list.get(i);
                    results.addAll(findTargets(child, pathParts, depth + 1));
                }
            }
        } else if (part.endsWith("[*]")) {
            // 数组通配符
            String arrayName = part.substring(0, part.length() - 3);
            if (current instanceof CompoundTag compound && compound.contains(arrayName)) {
                Tag array = compound.get(arrayName);
                if (array instanceof ListTag list) {
                    for (int i = 0; i < list.size(); i++) {
                        Tag child = list.get(i);
                        results.addAll(findTargets(child, pathParts, depth + 1));
                    }
                }
            }
        } else {
            // 普通路径
            if (current instanceof CompoundTag compound && compound.contains(part)) {
                Tag child = compound.get(part);
                results.addAll(findTargets(child, pathParts, depth + 1));
            }
        }
        
        return results;
    }
    
    private boolean checkConditions(CompoundTag target, List<Condition> conditions) {
        for (Condition condition : conditions) {
            if (!checkCondition(target, condition)) {
                return false;
            }
        }
        return true;
    }
    
    private boolean checkCondition(CompoundTag target, Condition condition) {
        try {
            switch (condition.type) {
                case "field_equals":
                    return checkFieldEquals(target, condition);
                case "field_not_equals":
                    return !checkFieldEquals(target, condition);
                case "field_exists":
                    return checkFieldExists(target, condition);
                case "string_contains":
                    return checkStringContains(target, condition);
                default:
                    LOGGER.warn("\033[95mUnknown condition type: {}\033[0m", condition.type);
                    return false;
            }
        } catch (Exception e) {
            LOGGER.warn("\033[95mError checking condition: {}\033[0m", condition.type, e);
            return false;
        }
    }
    
    private boolean checkFieldEquals(CompoundTag target, Condition condition) {
        Tag field = getTagByPath(target, condition.path);
        if (field == null) return false;
        
        Object actualValue = getTagValue(field);
        
        // 特殊处理浮点数比较
        if (condition.value instanceof Double && actualValue instanceof Float) {
            return Math.abs(((Float) actualValue) - ((Double) condition.value).floatValue()) < 0.0001;
        }
        
        return Objects.equals(actualValue, condition.value);
    }
    
    private boolean checkFieldExists(CompoundTag target, Condition condition) {
        return getTagByPath(target, condition.path) != null;
    }
    
    private boolean checkStringContains(CompoundTag target, Condition condition) {
        // 使用新的路径查找方法
        List<TagWithPath> foundTags = findTagsByPath(target, condition.path);
        
        for (TagWithPath tagWithPath : foundTags) {
            Tag field = tagWithPath.tag;
            if (field instanceof StringTag stringTag) {
                String value = stringTag.getAsString();
                if (value.contains((String) condition.value)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    private Tag getTagByPath(CompoundTag root, String path) {
        String[] parts = path.split("\\.");
        Tag current = root;
        
        for (String part : parts) {
            if (current instanceof CompoundTag compound) {
                if (!compound.contains(part)) {
                    return null;
                }
                current = compound.get(part);
            } else {
                return null;
            }
        }
        
        return current;
    }
    
    private Object getTagValue(Tag tag) {
        if (tag instanceof StringTag) return ((StringTag) tag).getAsString();
        if (tag instanceof ByteTag) return ((ByteTag) tag).getAsByte();
        if (tag instanceof ShortTag) return ((ShortTag) tag).getAsShort();
        if (tag instanceof IntTag) return ((IntTag) tag).getAsInt();
        if (tag instanceof LongTag) return ((LongTag) tag).getAsLong();
        if (tag instanceof FloatTag) return ((FloatTag) tag).getAsFloat();
        if (tag instanceof DoubleTag) return ((DoubleTag) tag).getAsDouble();
        if (tag instanceof ByteArrayTag) return ((ByteArrayTag) tag).getAsByteArray();
        if (tag instanceof StringTag) return ((StringTag) tag).getAsString();
        if (tag instanceof ListTag) return tag;
        if (tag instanceof CompoundTag) return tag;
        return null;
    }
    
    private boolean applyCleanupAction(CompoundTag target, CleanupAction action, String playerName, String fileName, String ruleName) {
        try {
            switch (action.type) {
                case "conditional_remove":
                    return applyConditionalRemove(target, action, playerName, fileName, ruleName);
                case "path_remove":
                    return applyPathRemove(target, action, playerName, fileName, ruleName);
                case "nested_string_remove":
                    return applyNestedStringRemove(target, action, playerName, fileName, ruleName);
                default:
                    LOGGER.warn("\033[95mUnknown cleanup action type: {}\033[0m", action.type);
                    return false;
            }
        } catch (Exception e) {
            LOGGER.warn("\033[95mError applying cleanup action: {}\033[0m", action.type, e);
            return false;
        }
    }
    
    private boolean applyConditionalRemove(CompoundTag target, CleanupAction action, String playerName, String fileName, String ruleName) {
        if (action.condition != null && !checkCondition(target, action.condition)) {
            return false;
        }
        
        return removeTagAtPath(target, action.targetPath, playerName, fileName, ruleName);
    }
    
    private boolean applyPathRemove(CompoundTag target, CleanupAction action, String playerName, String fileName, String ruleName) {
        return removeTagAtPath(target, action.targetPath, playerName, fileName, ruleName);
    }
    
    private boolean applyNestedStringRemove(CompoundTag target, CleanupAction action, String playerName, String fileName, String ruleName) {
        boolean modified = false;
        
        try {
            // 使用新的路径解析方法，支持数组通配符
            List<TagWithPath> foundTags = findTagsByPath(target, action.targetPath);
            
            for (TagWithPath tagWithPath : foundTags) {
                Tag tag = tagWithPath.tag;
                String fullPath = tagWithPath.path;
                
                if (tag instanceof StringTag stringTag) {
                    String original = stringTag.getAsString();
                    
                    // 检查条件
                    if (action.condition != null && !original.contains((String) action.condition.value)) {
                        continue;
                    }
                    
                    // 应用正则表达式清理
                    String cleaned = action.removePattern.matcher(original).replaceAll("");
                    
                    if (!cleaned.equals(original)) {
                        // 实际更新NBT数据
                        if (updateTagAtPath(target, fullPath, StringTag.valueOf(cleaned))) {
                            modified = true;
                            LOGGER.info("\033[93mRule '{}' cleaned string at path '{}' in file {}.nbt\033[0m", 
                                ruleName, fullPath, fileName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("\033[95mError applying nested string remove for rule '{}': {}\033[0m", ruleName, e.getMessage());
        }
        
        return modified;
    }
    
    /**
     * 支持通配符的路径查找，返回标签及其完整路径
     */
    private List<TagWithPath> findTagsByPath(Tag current, String path) {
        return findTagsByPath(current, path.split("\\."), 0, "");
    }
    
    private List<TagWithPath> findTagsByPath(Tag current, String[] pathParts, int depth, String currentPath) {
        List<TagWithPath> results = new ArrayList<>();
        
        if (depth >= pathParts.length) {
            results.add(new TagWithPath(current, currentPath));
            return results;
        }
        
        String part = pathParts[depth];
        String newPath = currentPath.isEmpty() ? part : currentPath + "." + part;
        
        if (part.equals("*")) {
            // 通配符 - 遍历所有元素
            if (current instanceof CompoundTag compound) {
                for (String key : compound.getAllKeys()) {
                    Tag child = compound.get(key);
                    String childPath = currentPath.isEmpty() ? key : currentPath + "." + key;
                    results.addAll(findTagsByPath(child, pathParts, depth + 1, childPath));
                }
            } else if (current instanceof ListTag list) {
                for (int i = 0; i < list.size(); i++) {
                    Tag child = list.get(i);
                    String childPath = currentPath + "[" + i + "]";
                    results.addAll(findTagsByPath(child, pathParts, depth + 1, childPath));
                }
            }
        } else if (part.endsWith("[*]")) {
            // 数组通配符
            String arrayName = part.substring(0, part.length() - 3);
            if (current instanceof CompoundTag compound && compound.contains(arrayName)) {
                Tag array = compound.get(arrayName);
                if (array instanceof ListTag list) {
                    for (int i = 0; i < list.size(); i++) {
                        Tag child = list.get(i);
                        String childPath = currentPath.isEmpty() ? arrayName + "[" + i + "]" : currentPath + "." + arrayName + "[" + i + "]";
                        results.addAll(findTagsByPath(child, pathParts, depth + 1, childPath));
                    }
                }
            }
        } else if (part.contains("[")) {
            // 具体数组索引，如 messages[0]
            String listName = part.substring(0, part.indexOf("["));
            String indexStr = part.substring(part.indexOf("[") + 1, part.indexOf("]"));
            
            if (current instanceof CompoundTag compound && compound.contains(listName)) {
                Tag listTag = compound.get(listName);
                if (listTag instanceof ListTag list) {
                    if (indexStr.equals("*")) {
                        // 遍历所有数组元素
                        for (int i = 0; i < list.size(); i++) {
                            Tag child = list.get(i);
                            String childPath = currentPath.isEmpty() ? listName + "[" + i + "]" : currentPath + "." + listName + "[" + i + "]";
                            results.addAll(findTagsByPath(child, pathParts, depth + 1, childPath));
                        }
                    } else {
                        // 具体索引
                        try {
                            int index = Integer.parseInt(indexStr);
                            if (index < list.size()) {
                                Tag child = list.get(index);
                                String childPath = currentPath.isEmpty() ? listName + "[" + index + "]" : currentPath + "." + listName + "[" + index + "]";
                                results.addAll(findTagsByPath(child, pathParts, depth + 1, childPath));
                            }
                        } catch (NumberFormatException e) {
                            LOGGER.warn("\033[95mInvalid array index: {}\033[0m", indexStr);
                        }
                    }
                }
            }
        } else {
            // 普通路径
            if (current instanceof CompoundTag compound && compound.contains(part)) {
                Tag child = compound.get(part);
                results.addAll(findTagsByPath(child, pathParts, depth + 1, newPath));
            }
        }
        
        return results;
    }
    
    /**
     * 根据路径更新NBT标签
     */
    private boolean updateTagAtPath(CompoundTag root, String path, Tag newTag) {
        if (path.isEmpty()) {
            return false;
        }
        
        String[] pathParts = path.split("\\.");
        CompoundTag current = root;
        
        // 遍历到目标节点的父节点
        for (int i = 0; i < pathParts.length - 1; i++) {
            String part = pathParts[i];
            
            // 处理数组索引，如 [0]
            if (part.contains("[")) {
                String listName = part.substring(0, part.indexOf("["));
                int index = Integer.parseInt(part.substring(part.indexOf("[") + 1, part.indexOf("]")));
                
                if (current.contains(listName) && current.get(listName) instanceof ListTag list) {
                    if (index < list.size() && list.get(index) instanceof CompoundTag compound) {
                        current = compound;
                    } else {
                        return false; // 路径无效
                    }
                } else {
                    return false; // 路径无效
                }
            } else {
                if (current.contains(part) && current.get(part) instanceof CompoundTag compound) {
                    current = compound;
                } else {
                    return false; // 路径无效
                }
            }
        }
        
        // 更新目标节点
        String targetKey = pathParts[pathParts.length - 1];
        
        // 处理数组索引
        if (targetKey.contains("[")) {
            String listName = targetKey.substring(0, targetKey.indexOf("["));
            int index = Integer.parseInt(targetKey.substring(targetKey.indexOf("[") + 1, targetKey.indexOf("]")));
            
            if (current.contains(listName) && current.get(listName) instanceof ListTag list) {
                if (index < list.size()) {
                    list.set(index, newTag);
                    return true;
                }
            }
        } else {
            if (current.contains(targetKey)) {
                current.put(targetKey, newTag);
                return true;
            }
        }
        
        return false;
    }
    
    private boolean removeTagAtPath(CompoundTag root, String path, String playerName, String fileName, String ruleName) {
        String[] parts = path.split("\\.");
        CompoundTag current = root;
        
        // 遍历到目标节点的父节点
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            if (!current.contains(part) || !(current.get(part) instanceof CompoundTag)) {
                return false;
            }
            current = (CompoundTag) current.get(part);
        }
        
        String targetKey = parts[parts.length - 1];
        if (current.contains(targetKey)) {
            current.remove(targetKey);
            LOGGER.info("\033[93mRule '{}' removed path '{}' in file {}.nbt\033[0m", ruleName, path, fileName);
            return true;
        }
        
        return false;
    }
    
    private CompoundTag readNbtFile(Path filePath) throws IOException {
        int maxRetries = 6;
        int retryDelayMs = 500;
    
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try (InputStream is = Files.newInputStream(filePath);
                 GZIPInputStream gzipIs = new GZIPInputStream(is);
                 DataInputStream dataIs = new DataInputStream(gzipIs)) {
            
                CompoundTag nbt = NbtIo.read(dataIs);
                if (nbt != null) {
                    if (attempt > 1) {
                        LOGGER.info("\033[92mSuccessfully read NBT file after {} attempts: {}\033[0m", attempt, filePath);
                    }
                    return nbt;
                }
            
            } catch (Exception e) {
                if (attempt < maxRetries) {
                    LOGGER.warn("\033[91mFailed to read NBT file (attempt {}/{}): {}. Retrying in {}ms...\033[0m", 
                        attempt, maxRetries, filePath, retryDelayMs, e);
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during retry delay", ie);
                    }
                } else {
                    LOGGER.error("\033[91mFailed to read NBT file after {} attempts: {}\033[0m", maxRetries, filePath, e);
                }
            }
        }
    
        return null;
    }

    private void writeNbtFile(CompoundTag nbt, Path filePath) throws IOException {
        try (OutputStream os = Files.newOutputStream(filePath);
             GZIPOutputStream gzipOs = new GZIPOutputStream(os);
             DataOutputStream dataOs = new DataOutputStream(gzipOs)) {
            NbtIo.write(nbt, dataOs);
        }
    }
    
    private static class FilterRule {
        final String name;
        final String targetPath;
        final List<Condition> conditions = new ArrayList<>();
        final List<CleanupAction> cleanupActions = new ArrayList<>();
        
        FilterRule(String name, String targetPath) {
            this.name = name;
            this.targetPath = targetPath;
        }
    }
    
    private static class Condition {
        final String type;
        String path;
        Object value;
        
        Condition(String type) {
            this.type = type;
        }
    }
    
    private static class CleanupAction {
        final String type;
        final String targetPath;
        String removeStrategy;
        Condition condition;
        Pattern removePattern;
        
        CleanupAction(String type, String targetPath) {
            this.type = type;
            this.targetPath = targetPath;
        }
    }
    
    /**
     * 用于存储标签及其路径的内部类
     */
    private static class TagWithPath {
        public final Tag tag;
        public final String path;
        
        public TagWithPath(Tag tag, String path) {
            this.tag = tag;
            this.path = path;
        }
    }
    
    private void notifyPlayer(String playerName, String fileName) {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
                if (player != null) {
                    Component message = Component.literal("The schematic diagram you uploaded appears to be abnormal.")
                            .withStyle(ChatFormatting.GOLD);
                    player.sendSystemMessage(message);
                    
                    LOGGER.debug("Notified player {} about anomalous schematic: {}.nbt", 
                        playerName, fileName);
                } else {
                    LOGGER.debug("Player {} not online, cannot send anomaly notification", playerName);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("\033[95mFailed to notify player {} about anomalous schematic\033[0m", playerName, e);
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
}
