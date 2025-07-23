package com.example.schematicsfix;

import net.neoforged.neoforge.common.ModConfigSpec;
import java.util.ArrayList;
import java.util.List;

public class Config {
    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.ConfigValue<List<? extends String>> BANNED_KEYWORDS;
    public static final ModConfigSpec.BooleanValue ENABLE_KEYWORD_CHECK;

    static {
        BUILDER.push("Schematic Patch Config");
        
        BANNED_KEYWORDS = BUILDER
                .comment("List of banned keywords for schematic files")
                .defineList("bannedKeywords", 
                            List.of("minecraft:bedrock", "minecraft:command_block"),
                            o -> o instanceof String);
        
        ENABLE_KEYWORD_CHECK = BUILDER
                .comment("Enable keyword checking")
                .define("enableKeywordCheck", true);
        
        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}