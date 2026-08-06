package com.pavitraxd.sparklogin.dialog;

import com.pavitraxd.sparklogin.SparkLoginPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles message localization parsing, string formatting, and Adventure Component generation.
 */
public class Messages {
    private final SparkLoginPlugin plugin;
    private YamlConfiguration langConfig;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacyAmpersand();

    public Messages(SparkLoginPlugin plugin) {
        this.plugin = plugin;
        loadLanguageFile();
    }

    /**
     * Loads the lang file from resources (defaulting to en_US.yml).
     */
    public void loadLanguageFile() {
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) {
            langDir.mkdirs();
        }

        File langFile = new File(langDir, "en_US.yml");
        if (!langFile.exists()) {
            plugin.saveResource("lang/en_US.yml", false);
        }

        this.langConfig = YamlConfiguration.loadConfiguration(langFile);
    }

    /**
     * Gets a raw string from translation yaml.
     */
    public String getRaw(String key, String def) {
        if (langConfig == null) return def;
        return langConfig.getString(key, def);
    }

    /**
     * Translates a configuration path to an elegant Adventure Component.
     * Supports both MiniMessage tagging system and legacy translation codes.
     */
    public Component getComponent(String key, String def, Map<String, String> placeholders) {
        String raw = getRaw(key, def);

        // Inject global branding placeholder first
        raw = raw.replace("{plugin_name}", plugin.getConfig().getString("branding.prefix", "SparkLogin"));

        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                raw = raw.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }

        // Detect if using MiniMessage tags (e.g. "<rainbow>", "<gold>")
        if (raw.contains("<") && raw.contains(">")) {
            return miniMessage.deserialize(raw);
        } else {
            // Otherwise parse legacy and return
            return legacySerializer.deserialize(raw);
        }
    }

    public Component getComponent(String key, String def) {
        return getComponent(key, def, null);
    }

    public Component getComponent(String key, String def, String placeholderKey, String placeholderVal) {
        Map<String, String> map = new HashMap<>();
        map.put(placeholderKey, placeholderVal);
        return getComponent(key, def, map);
    }
}
