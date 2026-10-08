package me.brannstroom.expbottle;

import me.brannstroom.expbottle.command.ExpCommand;
import me.brannstroom.expbottle.handlers.InfoKeeper;
import me.brannstroom.expbottle.handlers.MessageHandler;
import me.brannstroom.expbottle.listeners.ExpBottleListener;
import me.brannstroom.expbottle.model.ExperienceTransfers;
import me.brannstroom.expbottle.model.WorkTracker;
import me.brannstroom.expbottle.model.HotReload;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.plugin.configuration.PluginMeta;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class ExpBottle extends JavaPlugin {

    // #region Fields
    public static ExpBottle instance;
    private static final int EXPECTED_CONFIG_VERSION = 5;
    private ExperienceTransfers transfers;
    private WorkTracker work;
    private HotReload hotReload;

    public ExperienceTransfers getTransfers() { return transfers; }
    public WorkTracker getWork() { return work; }
    // #endregion

    // #region Plugin Lifecycle
    @Override
    public void onEnable() {
        instance = this;
        work = new WorkTracker();

        checkAndBackupOldConfig();
        saveDefaultConfig();
        setupLangFiles();
        reloadPluginConfig();
        try {
            transfers = new ExperienceTransfers(this);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot load pending experience refunds; refusing to discard them", ex);
        }
        getServer().getPluginManager().registerEvents(transfers, this);

        registerCommands();
        registerListeners();
        hotReload = new HotReload(this);
        hotReload.register();

        PluginMeta meta = this.getPluginMeta();
        getLogger().info("ExpBottle v" + meta.getVersion() + " enabled!");
    }

    @Override
    public void onDisable() {
        if (hotReload != null) hotReload.unregister();
        if (work != null) work.close();
        if (transfers != null) transfers.close();
        getLogger().info("ExpBottle disabled!");
        instance = null;
    }
    // #endregion

    // #region Registrations
    private void registerCommands() {
        String commandName = getConfig().getString("command.name", "exp");
        PluginCommand command = getCommand(commandName);
        if (command != null) {
            ExpCommand expCommandExecutor = new ExpCommand();
            command.setExecutor(expCommandExecutor);
            command.setTabCompleter(expCommandExecutor);
            var topic = new org.bukkit.help.GenericCommandHelpTopic(command);
            getServer().getHelpMap().addTopic(topic);
            getServer().getHelpMap().addTopic(new org.bukkit.help.IndexHelpTopic(getName(),
                    "All commands for " + getName(), null, java.util.List.of(topic)));
        } else {
            getLogger().severe("Could not register command '/" + commandName + "'! Check plugin.yml and config.yml.");
        }
    }

    private void registerListeners() {
        ExpBottleListener listener = new ExpBottleListener();
        getServer().getPluginManager().registerEvents(listener, this);
        listener.registerExistingFlights();
    }
    // #endregion

    // #region Config & Locales
    public void reloadPluginConfig() {
        reloadConfig();
        InfoKeeper.loadConfigValues();
        MessageHandler.clearLoadedLocales();
        loadLocaleFiles();
        MessageHandler.loadMessageSettings();

        List<String> loadedLocaleKeys = new ArrayList<>(MessageHandler.getLoadedLocaleKeys());
        if (!loadedLocaleKeys.isEmpty()) {
            getLogger().info("Successfully loaded " + loadedLocaleKeys.size() + " language(s): "
                    + String.join(", ", loadedLocaleKeys) + ".");
        }
        getLogger().info("Configuration and language files reloaded.");
    }

    private void setupLangFiles() {
        File langFolder = new File(getDataFolder(), "lang");
        if (!langFolder.exists()) {
            if (langFolder.mkdirs()) {
                getLogger().info("Language folder created: " + langFolder.getPath());
            } else {
                getLogger().severe("Could not create language folder: " + langFolder.getPath());
                return;
            }
        }

        String[] defaultLangs = { "en_US.yml", "ru_RU.yml", "zh_CN.yml" };
        List<String> copiedLangFiles = new ArrayList<>();
        for (String langFile : defaultLangs) {
            File destinationFile = new File(langFolder, langFile);
            if (!destinationFile.exists()) {
                try {
                    saveResource("lang/" + langFile, false);
                    copiedLangFiles.add(langFile);
                } catch (Exception e) {
                    getLogger().warning("Could not save default language file '" + langFile + "': " + e.getMessage());
                }
            }
        }
        if (!copiedLangFiles.isEmpty()) {
            getLogger().info("Copied " + copiedLangFiles.size() + " default language file(s) to lang folder: "
                    + String.join(", ", copiedLangFiles));
        }
    }

    private void loadLocaleFiles() {
        File langFolder = new File(getDataFolder(), "lang");
        List<String> loadedFromFileKeys = new ArrayList<>();

        if (!langFolder.exists() || !langFolder.isDirectory()) {
            getLogger().warning(
                    "Language folder not found or is not a directory. Attempting to load internal defaults only.");
            loadInternalLocale("en_US.yml", true);
            if (!"en_US.yml".equalsIgnoreCase(getConfig().getString("default_language", "en_US") + ".yml")) {
                loadInternalLocale(getConfig().getString("default_language", "en_US") + ".yml", false);
            }
            return;
        }

        String defaultLocaleKeyFromConfig = getConfig().getString("default_language", "en_US");
        FileConfiguration defaultLangConfig = null;
        boolean defaultExplicitlyLoaded = false;

        File[] langFiles = langFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".yml"));
        if (langFiles != null && langFiles.length > 0) {
            for (File langFile : langFiles) {
                String localeKey = langFile.getName().substring(0, langFile.getName().length() - 4);
                try {
                    FileConfiguration langConfig = YamlConfiguration.loadConfiguration(langFile);
                    mergeTransferMessages(langConfig, langFile, localeKey);
                    MessageHandler.addLoadedLocale(localeKey, langConfig);
                    loadedFromFileKeys.add(localeKey);
                    if (localeKey.equalsIgnoreCase(defaultLocaleKeyFromConfig)) {
                        defaultLangConfig = langConfig;
                        MessageHandler.setDefaultLocaleConfig(defaultLangConfig);
                        defaultExplicitlyLoaded = true;
                    }
                } catch (Exception e) {
                    getLogger().warning("Failed to load language file '" + langFile.getName() + "': " + e.getMessage());
                }
            }
            if (!loadedFromFileKeys.isEmpty()) {
                getLogger().info("Loaded " + loadedFromFileKeys.size() + " language(s) from lang folder: "
                        + String.join(", ", loadedFromFileKeys) + ".");
            }
            if (defaultExplicitlyLoaded) {
                getLogger().info("Default language set to: [" + defaultLocaleKeyFromConfig + "] from file.");
            }
        }

        if (!defaultExplicitlyLoaded) {
            getLogger().warning("Default language '" + defaultLocaleKeyFromConfig
                    + "' not found in lang folder or failed to load. Attempting to load from JAR.");
            loadInternalLocale(defaultLocaleKeyFromConfig + ".yml", true);
        }

        if (!MessageHandler.getLoadedLocaleKeys().contains("en_US")) {
            getLogger().info("en_US locale not loaded from files. Attempting to load internal en_US.yml.");
            loadInternalLocale("en_US.yml",
                    !defaultExplicitlyLoaded && !"en_US".equalsIgnoreCase(defaultLocaleKeyFromConfig));
        }
    }

    private void mergeTransferMessages(FileConfiguration config, File destination, String locale) throws java.io.IOException {
        String resource = locale.startsWith("zh") ? "zh_CN" : locale.startsWith("ru") ? "ru_RU" : "en_US";
        try (InputStream in = getResource("lang/" + resource + ".yml")) {
            if (in == null) throw new java.io.IOException("Missing internal language " + resource);
            FileConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            boolean changed = false;
            for (String key : List.of("transfer.pending", "transfer.failed", "transfer.capacity", "transfer.refunded")) {
                if (!config.contains(key)) {
                    config.set(key, defaults.getString(key));
                    changed = true;
                }
            }
            if (changed) config.save(destination);
        }
    }

    private void loadInternalLocale(String resourceName, boolean setAsDefault) {
        if (!resourceName.toLowerCase().endsWith(".yml")) {
            getLogger().warning("Attempted to load internal locale with invalid resource name: " + resourceName
                    + ". Must end with .yml");
            return;
        }
        String localeKey = resourceName.substring(0, resourceName.length() - 4);

        if (MessageHandler.getLoadedLocaleKeys().contains(localeKey) && !setAsDefault) {
            return;
        }

        try (InputStream is = getResource("lang/" + resourceName);) {
            if (is == null) {
                if (setAsDefault || resourceName.equals("en_US.yml")) {
                    getLogger().warning("Cannot load internal locale: lang/" + resourceName
                            + ". Resource not found in JAR. This may lead to issues if it was the intended default.");
                }
                return;
            }
            InputStreamReader reader = new InputStreamReader(is);
            FileConfiguration internalLangCfg = YamlConfiguration.loadConfiguration(reader);
            MessageHandler.addLoadedLocale(localeKey, internalLangCfg);
            getLogger().info("Loaded internal language: [" + localeKey + "] from JAR.");
            if (setAsDefault) {
                MessageHandler.setDefaultLocaleConfig(internalLangCfg);
                getLogger().info("Internal language [" + localeKey + "] set as default fallback.");
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to load internal locale: lang/" + resourceName, e);
        }
    }

    private void checkAndBackupOldConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists())
            return;

        FileConfiguration tempConfig = YamlConfiguration.loadConfiguration(configFile);
        int currentConfigVersion = tempConfig.getInt("config_version", 0);

        if (currentConfigVersion < EXPECTED_CONFIG_VERSION) {
            getLogger().warning("Old config.yml version (v" + currentConfigVersion + ") detected. Expected v"
                    + EXPECTED_CONFIG_VERSION + ".");

            String backupFileName = "config.yml.backup_v" + currentConfigVersion;
            File backupFile = new File(getDataFolder(), backupFileName);

            int attempt = 0;
            while (backupFile.exists()) {
                attempt++;
                backupFileName = "config.yml.backup_v" + currentConfigVersion + "_(" + attempt + ")";
                backupFile = new File(getDataFolder(), backupFileName);
            }

            if (configFile.renameTo(backupFile)) {
                getLogger().info("Backed up old config.yml to " + backupFileName + ".");
                getLogger().info("A new config.yml will be created with default values.");
                getLogger().info("Please transfer your custom settings from the backup to the new config.yml.");
            } else {
                getLogger().severe("Could not backup old config.yml! Please do it manually and restart.");
            }
        }
    }
    // #endregion
}
