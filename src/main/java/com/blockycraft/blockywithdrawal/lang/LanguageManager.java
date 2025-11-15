package com.blockycraft.blockywithdrawal.lang;

import org.bukkit.ChatColor;

import com.blockycraft.blockywithdrawal.BlockyWithdrawal;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class LanguageManager {
    private final BlockyWithdrawal plugin;
    private final Map<String, Properties> messages = new HashMap<>();
    private final String defaultLang = "en";

    public LanguageManager(BlockyWithdrawal plugin) {
        this.plugin = plugin;
        loadLanguages();
    }

    public void loadLanguages() {
        messages.clear();
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        saveDefaultLanguageFile("messages_en.properties");
        saveDefaultLanguageFile("messages_pt.properties");
        saveDefaultLanguageFile("messages_es.properties");

        for (File langFile : langFolder.listFiles((dir, name) -> name.startsWith("messages_") && name.endsWith(".properties"))) {
            String lang = langFile.getName().replace("messages_", "").replace(".properties", "");
            Properties props = new Properties();
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(langFile), StandardCharsets.UTF_8)) {
                props.load(reader);
                messages.put(lang, props);
                plugin.getServer().getLogger().info("Loaded language: " + lang);
            } catch (Exception e) {
                plugin.getServer().getLogger().severe("Could not load language file: " + langFile.getName());
                e.printStackTrace();
            }
        }
    }

    private void saveDefaultLanguageFile(String fileName) {
        File langFile = new File(new File(plugin.getDataFolder(), "lang"), fileName);
        if (!langFile.exists()) {
            try (InputStream in = plugin.getClass().getClassLoader().getResourceAsStream("lang/" + fileName)) {
                if (in != null) {
                    File parentDir = langFile.getParentFile();
                    if (!parentDir.exists()) {
                        parentDir.mkdirs();
                    }
                    java.nio.file.Files.copy(in, langFile.toPath());
                }
            } catch (Exception e) {
                plugin.getServer().getLogger().severe("Could not save default language file: " + fileName);
                e.printStackTrace();
            }
        }
    }

    public String get(String lang, String key) {
        return get(lang, key, null);
    }

    public String get(String lang, String key, Map<String, String> placeholders) {
        String message = messages.getOrDefault(lang, messages.get(defaultLang)).getProperty(key);

        if (message == null) {
            message = messages.get(defaultLang).getProperty(key, "§cMissing translation for key: " + key);
        }
        
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                message = message.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }

        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
