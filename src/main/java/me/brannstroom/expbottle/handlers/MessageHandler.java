package me.brannstroom.expbottle.handlers;

import me.brannstroom.expbottle.ExpBottle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class MessageHandler {

    // #region Fields & Static Initializer
    private static final ExpBottle plugin = ExpBottle.instance;
    private static FileConfiguration config;
    private static String prefixStringMiniMessage;
    private static Component prefixComponent;
    private static String commandName;

    private static final Map<String, FileConfiguration> loadedLocales = new HashMap<>();
    private static String defaultLocaleKey = "en_US";
    private static FileConfiguration defaultLocaleConfig = null;

    private static final MiniMessage mm = MiniMessage.miniMessage();

    static {
        loadMessageSettings();
    }
    // #endregion

    // #region MessageContext Class
    public static class MessageContext {
        private final CommandSender sender;
        @SuppressWarnings("unused")
        private final String commandLabel;
        private final Map<String, String> placeholders;

        public MessageContext(CommandSender sender, String commandLabel) {
            this.sender = sender;
            this.commandLabel = commandLabel;
            this.placeholders = new HashMap<>();

            if (commandLabel != null) {
                placeholders.put("command", commandLabel);
            }

            if (sender instanceof Player) {
                placeholders.put("player", sender.getName());
            }
        }

        public MessageContext add(String placeholderKey, String value) {
            placeholders.put(placeholderKey, value);
            return this;
        }

        public MessageContext addAll(Map<String, String> newPlaceholders) {
            if (newPlaceholders != null) {
                this.placeholders.putAll(newPlaceholders);
            }
            return this;
        }

        public CommandSender getSender() {
            return sender;
        }

        public String getCommandLabel() {
            return placeholders.get("command");
        }

        public Map<String, String> getPlaceholders() {
            return placeholders;
        }
    }
    // #endregion

    // #region Message Retrieval
    public static String getRawMessage(CommandSender sender, String path) {
        Object rawValue = null;
        FileConfiguration langConfig = getLangConfigForSender(sender);

        if (langConfig != null) {
            rawValue = langConfig.get(path);
        }

        if (rawValue == null && defaultLocaleConfig != null) {
            rawValue = defaultLocaleConfig.get(path);
        }

        if (rawValue == null) {
            FileConfiguration internalEn = loadedLocales.get("en_US");
            if (internalEn != null) {
                rawValue = internalEn.get(path);
            }
        }

        if (rawValue == null) {
            // Older supplied language files use these existing keys for the same messages.
            String fallback = switch (path) {
                case "send.success.points_sender" -> "send.success.all_sender";
                case "send.success.points_target" -> "send.success.all_target";
                case "send.error.not_enough_for_level" -> "send.error.not_enough_lvl";
                case "send.error.not_enough_points" -> "get.error.not_enough_points";
                default -> null;
            };
            if (fallback != null) return getRawMessage(sender, fallback);
        }

        if (rawValue instanceof String) {
            return (String) rawValue;
        } else if (rawValue instanceof List) {
            List<?> potentiallyStringList = (List<?>) rawValue;
            if (potentiallyStringList.isEmpty()) {
                return "";
            }

            List<String> stringList = new ArrayList<>();
            boolean allStrings = true;
            for (Object item : potentiallyStringList) {
                if (item instanceof String) {
                    stringList.add((String) item);
                } else {
                    allStrings = false;
                    plugin.getLogger().warning("Non-string item found in lang path '" + path + "': " + item);
                    break;
                }
            }

            if (allStrings) {
                return String.join("\n", stringList);
            } else {
                plugin.getLogger()
                        .warning("Path '" + path + "' in lang file is a List but not all elements are Strings.");
                return "<red>Invalid list content (non-string elements): " + path + "</red>";
            }
        }

        if (rawValue != null) {
            plugin.getLogger().warning("Path '" + path + "' in lang file is not a String or List of Strings, but: "
                    + rawValue.getClass().getName());
        }
        return "<red>Missing or invalid type translation: " + path + "</red>";
    }

    public static String getMessage(CommandSender sender, String path) {
        return getRawMessage(sender, path);
    }
    // #endregion

    // #region Message Sending
    public static void sendMessage(MessageContext context, String path) {
        CommandSender sender = context.getSender();
        String messageString = getMessage(sender, path);
        messageString = replacePlaceholders(messageString, context);
        Component messageComponent = mm.deserialize(messageString);
        Component currentPrefix = getLocalizedPrefix(sender);
        sender.sendMessage(currentPrefix.append(messageComponent));
    }

    public static void sendMessage(CommandSender sender, String path, String... replacements) {
        MessageContext context = new MessageContext(sender, null);

        if (replacements != null && replacements.length % 2 == 0) {
            for (int i = 0; i < replacements.length; i += 2) {
                context.add(replacements[i], replacements[i + 1]);
            }
        }

        sendMessage(context, path);
    }

    public static void sendRawMessage(MessageContext context, String path) {
        CommandSender sender = context.getSender();
        String messageString = getMessage(sender, path);
        messageString = replacePlaceholders(messageString, context);
        Component messageComponent = mm.deserialize(messageString);
        sender.sendMessage(messageComponent);
    }

    public static void sendRawMessage(CommandSender sender, String path, String... replacements) {
        MessageContext context = new MessageContext(sender, null);

        if (replacements != null && replacements.length % 2 == 0) {
            for (int i = 0; i < replacements.length; i += 2) {
                context.add(replacements[i], replacements[i + 1]);
            }
        }

        sendRawMessage(context, path);
    }
    // #endregion

    // #region Message Formatting
    public static Component getFormattedMessage(String path, MessageContext context) {
        String messageString = getMessage(context.getSender(), path);
        messageString = replacePlaceholders(messageString, context);
        return mm.deserialize(messageString);
    }

    public static Component getFormattedMessage(String path, CommandSender sender, String... replacements) {
        MessageContext context = new MessageContext(sender, null);

        if (replacements != null && replacements.length % 2 == 0) {
            for (int i = 0; i < replacements.length; i += 2) {
                context.add(replacements[i], replacements[i + 1]);
            }
        }

        return getFormattedMessage(path, context);
    }

    private static String replacePlaceholders(String message, MessageContext context) {
        if (message == null)
            return "";

        String result = message;
        for (Map.Entry<String, String> entry : context.getPlaceholders().entrySet()) {
            result = result.replace("<" + entry.getKey() + ">", entry.getValue());
        }

        if (result.contains("%command%")) {
            result = result.replace("%command%", commandName);
        }

        if (result.contains("<command>") && context.getPlaceholders().get("command") == null && commandName != null) {
            result = result.replace("<command>", commandName);
        }

        return result;
    }
    // #endregion

    // #region Locales & Config
    public static void loadMessageSettings() {
        config = plugin.getConfig();
        commandName = Objects.requireNonNull(config.getString("command.name", "exp"));
        defaultLocaleKey = config.getString("default_language", "en_US");

        FileConfiguration tempDefaultLangConfig = loadedLocales.get(defaultLocaleKey);
        if (tempDefaultLangConfig != null) {
            prefixStringMiniMessage = tempDefaultLangConfig.getString("prefix",
                    "<gray>[<yellow>ExpBottle</yellow>]</gray> ");
        } else if (defaultLocaleConfig != null) {
            prefixStringMiniMessage = defaultLocaleConfig.getString("prefix",
                    "<gray>[<yellow>ExpBottle</yellow>]</gray> ");
        } else {
            prefixStringMiniMessage = "<gray>[<yellow>ExpBottle</yellow>]</gray> ";
        }
        prefixComponent = mm.deserialize(prefixStringMiniMessage);
        plugin.getLogger().info("MessageHandler settings (re)loaded. Default locale: " + defaultLocaleKey
                + ". Command: " + commandName);
    }

    private static Component getLocalizedPrefix(CommandSender sender) {
        return prefixComponent;
    }

    public static FileConfiguration getLangConfigForSender(CommandSender sender) {
        String localeString = defaultLocaleKey;
        if (sender instanceof Player) {
            Player player = (Player) sender;
            java.util.Locale playerLocale = player.locale();
            if (playerLocale != null) {
                String lang = playerLocale.getLanguage();
                String country = playerLocale.getCountry();
                if (!country.isEmpty()) {
                    localeString = lang + "_" + country.toUpperCase();
                } else {
                    localeString = lang;
                }
            }
        }
        return loadedLocales.getOrDefault(localeString, defaultLocaleConfig);
    }

    public static void setDefaultLocaleConfig(FileConfiguration config) {
        defaultLocaleConfig = config;
        if (defaultLocaleConfig != null) {
            prefixStringMiniMessage = defaultLocaleConfig.getString("prefix",
                    "<gray>[<yellow>ExpBottle</yellow>]</gray> ");
            prefixComponent = mm.deserialize(prefixStringMiniMessage);
        }
    }

    public static void addLoadedLocale(String localeKey, FileConfiguration config) {
        loadedLocales.put(localeKey, config);
    }

    public static Set<String> getLoadedLocaleKeys() {
        return loadedLocales.keySet();
    }

    public static void clearLoadedLocales() {
        loadedLocales.clear();
        defaultLocaleConfig = null;
    }
    // #endregion

    // #region Raw Text Sending
    public static void sendRawText(MessageContext context, String text) {
        CommandSender sender = context.getSender();
        String messageString = replacePlaceholders(text, context);
        Component messageComponent = mm.deserialize(messageString);
        Component currentPrefix = getLocalizedPrefix(sender);
        sender.sendMessage(currentPrefix.append(messageComponent));
    }

    public static void sendRawText(CommandSender sender, String text, String... replacements) {
        MessageContext context = new MessageContext(sender, null);
        if (replacements != null && replacements.length % 2 == 0) {
            for (int i = 0; i < replacements.length; i += 2) {
                context.add(replacements[i], replacements[i + 1]);
            }
        }
        sendRawText(context, text);
    }
    // #endregion
}
