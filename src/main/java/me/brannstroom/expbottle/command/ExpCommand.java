package me.brannstroom.expbottle.command;

import me.brannstroom.expbottle.ExpBottle;
import me.brannstroom.expbottle.handlers.InfoKeeper;
import me.brannstroom.expbottle.handlers.MessageHandler;
import me.brannstroom.expbottle.model.Experience;
import me.brannstroom.expbottle.model.OwnedTasks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.Location;

import net.kyori.adventure.text.minimessage.MiniMessage;

public class ExpCommand implements CommandExecutor, TabCompleter {

    // #region Fields & Constants
    private final ExpBottle plugin = ExpBottle.instance;
    private final NamespacedKey expKey = new NamespacedKey(plugin, "experience_points");
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private static final String PERM_BASE = "expbottle.use";
    private static final String PERM_GET = "expbottle.get";
    private static final String PERM_SEND = "expbottle.send";
    private static final String PERM_SPLIT = "expbottle.split";
    private static final String PERM_GIVE = "expbottle.give";
    private static final String PERM_HELP = "expbottle.help";
    private static final String PERM_RELOAD = "expbottle.reload";
    // #endregion

    // #region Utility Methods (Experience Calculation)
    private int getExpToNextLevel(int level) {
        if (level < 0)
            return 0;
        if (level <= 15)
            return 2 * level + 7;
        if (level <= 30)
            return 5 * level - 38;
        return 9 * level - 158;
    }

    private int getExpInLevel(Player player) {
        return (int) (player.getExp() * getExpToNextLevel(player.getLevel()));
    }
    // #endregion

    // #region Core Command Logic
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try (var lease = plugin.getWork().enter()) {
            if (lease == null) {
                sender.sendMessage("ExpBottle 正在准备热更新，请稍后再试。");
                return true;
            }
            return executeCommand(sender, command, label, args);
        }
    }

    private boolean executeCommand(CommandSender sender, Command command, String label, String[] args) {
        MessageHandler.MessageContext ctx = new MessageHandler.MessageContext(sender, label);

        if (!(sender instanceof Player)) {
            if (args.length > 0) {
                String subCmdConsole = args[0].toLowerCase();
                if (subCmdConsole.equals("reload")) {
                    if (!sender.hasPermission(PERM_RELOAD)) {
                        MessageHandler.sendMessage(ctx, "general.no_permission");
                        return true;
                    }
                    handleReload(sender, ctx);
                } else if (subCmdConsole.equals("give")) {
                    if (!sender.hasPermission(PERM_GIVE)) {
                        MessageHandler.sendMessage(ctx, "general.no_permission");
                        return true;
                    }
                    handleGiveExp(sender, args, ctx);
                } else {
                    MessageHandler.sendMessage(ctx, "general.console_usage_hint");
                }
            } else {
                MessageHandler.sendMessage(ctx, "general.console_usage_hint");
            }
            return true;
        }

        Player player = (Player) sender;
        plugin.getTransfers().applyRefund(player);
        if (!player.hasPermission(PERM_BASE)) {
            MessageHandler.sendMessage(ctx, "general.no_permission");
            return true;
        }

        if (args.length == 0) {
            handleInfo(player, ctx);
            return true;
        }

        String subCmd = args[0].toLowerCase();
        switch (subCmd) {
            case "g":
            case "get":
                handleGetExp(player, args, ctx);
                break;
            case "send":
                handleSendExp(player, args, ctx);
                break;
            case "s":
            case "split":
                handleSplitExp(player, args, ctx);
                break;
            case "give":
                handleGiveExp(sender, args, ctx);
                break;
            case "h":
            case "help":
                handleHelp(player, ctx);
                break;
            case "reload":
                if (!player.hasPermission(PERM_RELOAD)) {
                    MessageHandler.sendMessage(ctx, "general.no_permission");
                } else {
                    handleReload(player, ctx);
                }
                break;
            default:
                MessageHandler.sendMessage(ctx, "general.unknown_subcommand");
                break;
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!(sender instanceof Player)) {
            if (args.length == 1)
                StringUtil.copyPartialMatches(args[0], Collections.singletonList("reload"), completions);
            return completions;
        }

        Player player = (Player) sender;
        if (args.length == 1) {
            List<String> subCmds = new ArrayList<>();
            if (player.hasPermission(PERM_GET))
                subCmds.add("get");
            if (player.hasPermission(PERM_SEND))
                subCmds.add("send");
            if (player.hasPermission(PERM_SPLIT))
                subCmds.add("split");
            if (player.hasPermission(PERM_GIVE))
                subCmds.add("give");
            if (player.hasPermission(PERM_HELP))
                subCmds.add("help");
            if (player.hasPermission(PERM_RELOAD))
                subCmds.add("reload");
            StringUtil.copyPartialMatches(args[0], subCmds, completions);
        } else if (args.length == 2) {
            String subCmd = args[0].toLowerCase();
            if (subCmd.equals("send") && player.hasPermission(PERM_SEND)) {
                List<String> playerNames = Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> !name.equals(player.getName()))
                        .collect(Collectors.toList());
                StringUtil.copyPartialMatches(args[1], playerNames, completions);
            } else if ((subCmd.equals("get") || subCmd.equals("g")) && player.hasPermission(PERM_GET)) {
                addAmountSuggestions(args[1], completions);
            } else if ((subCmd.equals("split") || subCmd.equals("s")) && player.hasPermission(PERM_SPLIT)) {
                addAmountSuggestions(args[1], completions, false);
            } else if (subCmd.equals("give") && player.hasPermission(PERM_GIVE)) {
                List<String> playerNames = Bukkit.getOnlinePlayers().stream().map(Player::getName)
                        .collect(Collectors.toList());
                StringUtil.copyPartialMatches(args[1], playerNames, completions);
            }
        } else if (args.length == 3) {
            String subCmd = args[0].toLowerCase();
            if (subCmd.equals("send") && player.hasPermission(PERM_SEND)) {
                addAmountSuggestions(args[2], completions);
            } else if (subCmd.equals("give") && player.hasPermission(PERM_GIVE)) {
                addAmountSuggestions(args[2], completions, false);
            }
        }

        Collections.sort(completions);
        return completions;
    }
    // #endregion

    // #region Argument Parsing & Utilities
    private void addAmountSuggestions(String currentArg, List<String> completions) {
        addAmountSuggestions(currentArg, completions, true);
    }

    private void addAmountSuggestions(String currentArg, List<String> completions, boolean allowAll) {
        if (allowAll)
            completions.add("all");
        if (currentArg.matches("\\d*")) {
            if (!currentArg.isEmpty()) {
                completions.add(currentArg + "L");
            }
        }
    }

    private static class ParsedExpValue {
        final int amount;
        final boolean isLevels;
        final boolean isAll;
        final String originalInput;

        ParsedExpValue(int amount, boolean isLevels, boolean isAll, String originalInput) {
            this.amount = amount;
            this.isLevels = isLevels;
            this.isAll = isAll;
            this.originalInput = originalInput;
        }
    }

    private ParsedExpValue parseAmount(String arg, Player p, boolean allowAll) {
        if (allowAll && "all".equalsIgnoreCase(arg)) {
            return new ParsedExpValue(Experience.getExp(p), false, true, "all");
        }
        String numPart = arg;
        boolean isLevels = false;

        if (arg.endsWith("L")) {
            isLevels = true;
            numPart = arg.substring(0, arg.length() - 1);
        }

        try {
            int value = Integer.parseInt(numPart);
            if (value <= 0)
                return null;
            return new ParsedExpValue(value, isLevels, false, arg);
        } catch (NumberFormatException e) {
            return null;
        }
    }
    // #endregion

    // #region Command Handlers

    // #region Handle /exp info
    private void handleInfo(Player player, MessageHandler.MessageContext ctx) {
        if (!player.hasPermission(PERM_BASE)) {
            MessageHandler.sendMessage(ctx, "general.no_permission");
            return;
        }
        int level = player.getLevel();
        ctx.add("level", String.valueOf(level))
                .add("exp", String.valueOf(getExpInLevel(player)))
                .add("next_level_exp", String.valueOf(getExpToNextLevel(level)))
                .add("total_exp", String.valueOf(Experience.getExp(player)));
        MessageHandler.sendRawMessage(ctx, "info.display");
    }
    // #endregion

    // #region Handle /exp get
    private void handleGetExp(Player player, String[] args, MessageHandler.MessageContext ctx) {
        if (!player.hasPermission(PERM_GET)) {
            MessageHandler.sendMessage(ctx, "general.no_permission");
            return;
        }

        int currentExp = Experience.getExp(player);
        if (currentExp == 0 && !(args.length > 1 && args[1].equalsIgnoreCase("all"))) {
            if (args.length == 1 || !args[1].equalsIgnoreCase("all")) {
                MessageHandler.sendMessage(ctx, "get.error.no_exp");
                return;
            }
        }

        ParsedExpValue parsed;
        String amountArg;

        if (args.length == 1) {
            amountArg = "all";
        } else {
            amountArg = args[1];
        }

        parsed = parseAmount(amountArg, player, true);

        if (parsed == null) {
            ctx.add("input", amountArg);
            MessageHandler.sendMessage(ctx, "general.invalid_amount_format");
            return;
        }

        if (currentExp == 0 && parsed.isAll) {
            MessageHandler.sendMessage(ctx, "get.error.no_exp");
            return;
        }

        int expToWithdraw;
        int levelEquivForBottle;
        if (parsed.isAll) {
            expToWithdraw = currentExp;
            levelEquivForBottle = player.getLevel();
        } else if (parsed.isLevels) {
            expToWithdraw = Experience.getExpFromLevel(parsed.amount);
            levelEquivForBottle = parsed.amount;
            if (expToWithdraw > currentExp) {
                ctx.add("amount_lvl", String.valueOf(parsed.amount))
                        .add("amount_xp", String.valueOf(expToWithdraw))
                        .add("total_exp", String.valueOf(currentExp));
                MessageHandler.sendMessage(ctx, "get.error.not_enough_for_level");
                return;
            }
        } else {
            expToWithdraw = parsed.amount;
            levelEquivForBottle = -1;
            if (expToWithdraw > currentExp) {
                ctx.add("amount_xp", String.valueOf(parsed.amount))
                        .add("total_exp", String.valueOf(currentExp));
                MessageHandler.sendMessage(ctx, "get.error.not_enough_points");
                return;
            }
        }
        if (expToWithdraw <= 0) {
            MessageHandler.sendMessage(ctx, "general.positive_number");
            return;
        }
        if (InfoKeeper.getInventoryFullAction.equals("error") && !canReceiveItem(player)) {
            MessageHandler.sendMessage(ctx, "get.error.inventory_full");
            return;
        }
        if (!Experience.canChangeExp(player, -expToWithdraw)) {
            MessageHandler.sendMessage(ctx, "transfer.capacity");
            return;
        }
        Experience.changeExp(player, -expToWithdraw);
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("amount_xp", String.valueOf(expToWithdraw));
        String successMsgKey;
        if (parsed.isAll) {
            successMsgKey = "get.success.all";
        } else if (parsed.isLevels) {
            placeholders.put("amount_lvl", String.valueOf(parsed.amount));
            successMsgKey = "get.success.levels";
        } else {
            successMsgKey = "get.success.points";
        }
        MessageHandler.sendMessage(ctx.addAll(placeholders), successMsgKey);
        createAndGiveBottle(player, expToWithdraw, levelEquivForBottle, ctx, parsed.isLevels || parsed.isAll);
    }

    private void createAndGiveBottle(Player p, int exp, int lvlEquiv, MessageHandler.MessageContext msgCtx,
            boolean inputWasLvlOrAll) {
        ItemStack bottle = createBottle(p, exp, lvlEquiv, inputWasLvlOrAll);
        boolean selfGenerated = msgCtx != null && msgCtx.getSender().equals(p);
        if (p.getInventory().firstEmpty() == -1) {
            p.getWorld().dropItemNaturally(p.getLocation(), bottle);
            if (selfGenerated) MessageHandler.sendMessage(msgCtx, "get.success.inventory_full_drop");
        } else {
            p.getInventory().addItem(bottle);
        }
        if (selfGenerated) playGetSound(p);
    }

    private ItemStack createBottle(Player p, int exp, int lvlEquiv, boolean inputWasLvlOrAll) {
        ItemStack bottle = new ItemStack(Material.EXPERIENCE_BOTTLE);
        ItemMeta meta = bottle.getItemMeta();
        if (meta == null) {
            throw new IllegalStateException("Could not get ItemMeta for EXPERIENCE_BOTTLE");
        }
        meta.displayName(
                Component.translatable("item.minecraft.experience_bottle").decoration(TextDecoration.ITALIC, false));
        List<Component> loreComps = new ArrayList<>();
        List<String> loreLines = InfoKeeper.bottleLore;
        int fullLvlInBottle = (int) Experience.getLevelFromExp(exp);
        String playerName = p.getName();

        for (String line : loreLines) {
            String processedLine = line.replace("<player>", playerName)
                    .replace("<amount_xp>", String.valueOf(exp))
                    .replace("<full_levels>", String.valueOf(fullLvlInBottle));

            if (processedLine.contains("<amount_lvl>")) {
                if (inputWasLvlOrAll) {
                    processedLine = processedLine.replace("<amount_lvl>", String.valueOf(lvlEquiv));
                } else {
                    if (lvlEquiv > 0) {
                        processedLine = processedLine.replace("<amount_lvl>", String.valueOf(lvlEquiv));
                    } else {
                        processedLine = processedLine.replace("<amount_lvl>", "N/A");
                    }
                }
            }
            loreComps.add(miniMessage.deserialize(processedLine));
        }
        meta.getPersistentDataContainer().set(expKey, PersistentDataType.INTEGER, exp);
        meta.lore(loreComps);
        bottle.setItemMeta(meta);

        return bottle;
    }
    // #endregion

    // #region Handle /exp send
    private void handleSendExp(Player sender, String[] args, MessageHandler.MessageContext ctx) {
        if (!sender.hasPermission(PERM_SEND)) {
            MessageHandler.sendMessage(ctx, "general.no_permission");
            return;
        }
        if (args.length < 3) {
            MessageHandler.sendMessage(ctx, "send.usage");
            return;
        }
        String targetName = args[1];
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            ctx.add("target", targetName);
            MessageHandler.sendMessage(ctx, "general.player_not_found");
            return;
        }
        if (target.equals(sender)) {
            MessageHandler.sendMessage(ctx, "send.error.cannot_self");
            return;
        }
        int senderExp = Experience.getExp(sender);
        if (senderExp == 0) {
            MessageHandler.sendMessage(ctx, "send.error.no_exp");
            return;
        }
        ParsedExpValue parsed = parseAmount(args[2], sender, true);
        if (parsed == null) {
            ctx.add("input", args[2]);
            MessageHandler.sendMessage(ctx, "general.invalid_amount_format");
            return;
        }
        int expToSend;
        int originalInputAmountForMsg = parsed.amount;
        if (parsed.isAll) {
            expToSend = senderExp;
        } else if (parsed.isLevels) {
            expToSend = Experience.getExpFromLevel(parsed.amount);
            if (senderExp < expToSend) {
                ctx.add("amount_lvl", String.valueOf(parsed.amount))
                        .add("amount_xp", String.valueOf(expToSend))
                        .add("total_exp", String.valueOf(senderExp));
                MessageHandler.sendMessage(ctx, "send.error.not_enough_for_level");
                return;
            }
        } else {
            expToSend = parsed.amount;
            if (senderExp < expToSend) {
                ctx.add("amount_xp", String.valueOf(parsed.amount))
                        .add("total_exp", String.valueOf(senderExp));
                MessageHandler.sendMessage(ctx, "send.error.not_enough_points");
                return;
            }
        }
        if (expToSend <= 0) {
            MessageHandler.sendMessage(ctx, "general.positive_number");
            return;
        }
        Map<String, String> senderPlaceholders = new HashMap<>();
        Map<String, String> targetPlaceholders = new HashMap<>();
        senderPlaceholders.put("target", target.getName());
        targetPlaceholders.put("sender", sender.getName());
        senderPlaceholders.put("amount_xp", String.valueOf(expToSend));
        targetPlaceholders.put("amount_xp", String.valueOf(expToSend));
        String senderMsgKey, targetMsgKey;
        if (parsed.isAll) {
            senderMsgKey = "send.success.all_sender";
            targetMsgKey = "send.success.all_target";
        } else if (parsed.isLevels) {
            senderPlaceholders.put("amount_lvl", String.valueOf(originalInputAmountForMsg));
            targetPlaceholders.put("amount_lvl", String.valueOf(originalInputAmountForMsg));
            senderMsgKey = "send.success.levels_sender";
            targetMsgKey = "send.success.levels_target";
        } else {
            senderMsgKey = "send.success.points_sender";
            targetMsgKey = "send.success.points_target";
        }
        plugin.getTransfers().send(sender, target, expToSend, () -> {
            MessageHandler.MessageContext targetCtx = new MessageHandler.MessageContext(target, ctx.getCommandLabel());
            MessageHandler.sendMessage(targetCtx.addAll(targetPlaceholders), targetMsgKey);
            playSendReceiveSound(target);
        }, () -> MessageHandler.sendMessage(ctx.addAll(senderPlaceholders), senderMsgKey));
    }
    // #endregion

    // #region Handle /exp help
    private void handleHelp(Player player, MessageHandler.MessageContext ctx) {
        if (!player.hasPermission(PERM_HELP)) {
            MessageHandler.sendMessage(ctx, "general.no_permission");
            return;
        }
        MessageHandler.sendRawMessage(ctx, "help.header");

        sendHelpEntry(player, ctx, "get", PERM_GET);
        sendHelpEntry(player, ctx, "send", PERM_SEND);
        sendHelpEntry(player, ctx, "split", PERM_SPLIT);
        sendHelpEntry(player, ctx, "give", PERM_GIVE);
        sendHelpEntry(player, ctx, "help", PERM_HELP, false);
        sendHelpEntry(player, ctx, "reload", PERM_RELOAD, false);

        MessageHandler.sendRawMessage(ctx, "help.footer");
    }

    private void sendHelpEntry(Player player, MessageHandler.MessageContext ctx, String cmdKey, String permission) {
        sendHelpEntry(player, ctx, cmdKey, permission, true);
    }

    private void sendHelpEntry(Player player, MessageHandler.MessageContext ctx, String cmdKey, String permission,
            boolean checkSubPerm) {
        if (checkSubPerm && !player.hasPermission(permission)) {
            return;
        }
        MessageHandler.sendRawMessage(ctx, "help." + cmdKey + ".main");

        Object descObject = MessageHandler.getLangConfigForSender(player).get("help." + cmdKey + ".description");
        if (descObject instanceof List) {
            List<?> descList = (List<?>) descObject;
            for (Object lineObj : descList) {
                if (lineObj instanceof String) {
                    MessageHandler.MessageContext lineCtx = new MessageHandler.MessageContext(player,
                            ctx.getCommandLabel());
                    MessageHandler.sendRawText(lineCtx, (String) lineObj);
                }
            }
        }
    }
    // #endregion

    // #region Handle /exp reload
    private void handleReload(CommandSender sender, MessageHandler.MessageContext ctx) {
        if (!sender.hasPermission(PERM_RELOAD)) {
            MessageHandler.sendMessage(ctx, "general.no_permission");
            return;
        }
        try {
            plugin.reloadPluginConfig();
            MessageHandler.sendMessage(ctx, "reload.success");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error reloading ExpBottle configuration", e);
            MessageHandler.sendMessage(ctx, "reload.error");
        }
    }
    // #endregion

    // #region Sounds
    private void playGetSound(Player player) {
        if (InfoKeeper.getSoundEnabled) {
            try {
                Sound sound = Sound.valueOf(InfoKeeper.getSoundName.toUpperCase());
                player.playSound(player.getLocation(), sound, InfoKeeper.getSoundVolume, InfoKeeper.getSoundPitch);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid sound name in config.yml for GET sound: '" + InfoKeeper.getSoundName
                        + "'. Please check the Bukkit Sound enum.");
            }
        }
    }

    private void playSplitSound(Player player) {
        if (InfoKeeper.splitSoundEnabled) {
            try {
                Sound sound = Sound.valueOf(InfoKeeper.splitSoundName.toUpperCase());
                player.playSound(player.getLocation(), sound, InfoKeeper.splitSoundVolume, InfoKeeper.splitSoundPitch);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid sound name in config.yml for SPLIT sound: '"
                        + InfoKeeper.splitSoundName + "'. Please check the Bukkit Sound enum.");
            }
        }
    }

    private void playSendReceiveSound(Player player) {
        if (InfoKeeper.sendReceiveSoundEnabled) {
            try {
                Sound sound = Sound.valueOf(InfoKeeper.sendReceiveSoundName.toUpperCase());
                player.playSound(player.getLocation(), sound, InfoKeeper.sendReceiveSoundVolume,
                        InfoKeeper.sendReceiveSoundPitch);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid sound name in config.yml for SEND/RECEIVE sound: '"
                        + InfoKeeper.sendReceiveSoundName + "'. Please check the Bukkit Sound enum.");
            }
        }
    }
    // #endregion

    // #region Handle /exp split
    private void handleSplitExp(Player player, String[] args, MessageHandler.MessageContext ctx) {
        if (!player.hasPermission(PERM_SPLIT)) {
            MessageHandler.sendMessage(ctx, "general.no_permission");
            return;
        }
        if (args.length < 2) {
            MessageHandler.sendMessage(ctx, "split.usage");
            return;
        }
        ParsedExpValue parsedPerBottle = parseAmount(args[1], player, false);
        if (parsedPerBottle == null || parsedPerBottle.isAll) {
            ctx.add("input", args[1]);
            MessageHandler.sendMessage(ctx, "general.invalid_amount_format");
            return;
        }
        int amountPerBottle = parsedPerBottle.amount;
        boolean inputIsLevels = parsedPerBottle.isLevels;
        int expPerBottle;
        int levelEquivForBottleDisplay = -1;
        if (inputIsLevels) {
            levelEquivForBottleDisplay = amountPerBottle;
            if (amountPerBottle < InfoKeeper.splitMinLevelsPerBottle) {
                ctx.add("min_levels", String.valueOf(InfoKeeper.splitMinLevelsPerBottle));
                MessageHandler.sendMessage(ctx, "split.error.min_levels_not_met");
                return;
            }
            if (InfoKeeper.splitLevelStep > 1 && amountPerBottle % InfoKeeper.splitLevelStep != 0) {
                ctx.add("level_step", String.valueOf(InfoKeeper.splitLevelStep));
                MessageHandler.sendMessage(ctx, "split.error.step_not_met");
                return;
            }
            expPerBottle = Experience.getExpFromLevel(amountPerBottle);
        } else {
            expPerBottle = amountPerBottle;
            int minExpRequired = Experience.getExpFromLevel(InfoKeeper.splitMinLevelsPerBottle);
            if (expPerBottle < minExpRequired) {
                ctx.add("min_exp", String.valueOf(minExpRequired))
                        .add("min_levels_equiv", String.valueOf(InfoKeeper.splitMinLevelsPerBottle));
                MessageHandler.sendMessage(ctx, "split.error.min_exp_not_met");
                return;
            }
            if (InfoKeeper.splitExpStep > 1 && expPerBottle % InfoKeeper.splitExpStep != 0) {
                ctx.add("exp_step", String.valueOf(InfoKeeper.splitExpStep));
                MessageHandler.sendMessage(ctx, "split.error.exp_step_not_met");
                return;
            }
            levelEquivForBottleDisplay = (int) Experience.getLevelFromExp(expPerBottle);
        }
        if (expPerBottle == 0) {
            plugin.getLogger().warning("Split failed: Calculated 0 exp for input " + parsedPerBottle.originalInput);
            MessageHandler.sendMessage(ctx, "split.error.generic");
            return;
        }
        int currentExp = Experience.getExp(player);
        int bottlesToCreate = currentExp / expPerBottle;

        if (bottlesToCreate == 0) {
            Map<String, String> p = new HashMap<>();
            p.put("amount_xp_per_bottle", String.valueOf(expPerBottle));
            p.put("total_exp", String.valueOf(currentExp));
            String msgKey;
            if (inputIsLevels) {
                p.put("amount_lvl_per_bottle", String.valueOf(amountPerBottle));
                msgKey = "split.error.not_enough_for_level_bottle";
            } else {
                p.put("amount_points_per_bottle", String.valueOf(amountPerBottle));
                msgKey = "split.error.not_enough_for_points_bottle";
            }
            MessageHandler.sendMessage(ctx.addAll(p), msgKey);
            return;
        }

        int totalExpNeeded = bottlesToCreate * expPerBottle;
        int freeSlots = getFreeInventorySlots(player);

        int requiredSlots = (bottlesToCreate - 1) / Math.min(InfoKeeper.bottleMaxStackSize, Material.EXPERIENCE_BOTTLE.getMaxStackSize()) + 1;

        if (requiredSlots > freeSlots) {
            ctx.add("count", String.valueOf(bottlesToCreate))
                    .add("free_slots", String.valueOf(freeSlots))
                    .add("required", String.valueOf(requiredSlots));
            MessageHandler.sendMessage(ctx, "split.error.inventory_full_multi");
            return;
        }

        if (!Experience.canChangeExp(player, -totalExpNeeded)) {
            MessageHandler.sendMessage(ctx, "transfer.capacity");
            return;
        }
        // Build before debiting; commit on this player's owner with a rollback snapshot.
        ItemStack bottle = createBottle(player, expPerBottle, levelEquivForBottleDisplay, inputIsLevels);
        ItemStack[] before = player.getInventory().getStorageContents();
        for (int i = 0; i < before.length; i++) if (before[i] != null) before[i] = before[i].clone();
        Experience.changeExp(player, -totalExpNeeded);
        try {
            for (int remaining = bottlesToCreate; remaining > 0;) {
                ItemStack stack = bottle.clone();
                int count = Math.min(remaining, bottle.getMaxStackSize());
                stack.setAmount(count);
                if (!player.getInventory().addItem(stack).isEmpty())
                    throw new IllegalStateException("Split inventory capacity changed");
                remaining -= count;
            }
        } catch (RuntimeException ex) {
            player.getInventory().setStorageContents(before);
            Experience.changeExp(player, totalExpNeeded);
            plugin.getLogger().log(Level.WARNING, "Split failed; inventory and experience restored", ex);
            MessageHandler.sendMessage(ctx, "split.error.generic");
            return;
        }
        Map<String, String> successPlaceholders = new HashMap<>();
        successPlaceholders.put("count", String.valueOf(bottlesToCreate));
        String baseMsgKey;
        if (inputIsLevels) {
            successPlaceholders.put("amount_lvl", String.valueOf(amountPerBottle));
            baseMsgKey = "split.success.base_levels";
        } else {
            successPlaceholders.put("amount_points", String.valueOf(amountPerBottle));
            baseMsgKey = "split.success.base_points";
        }
        MessageHandler.sendMessage(ctx.addAll(successPlaceholders), baseMsgKey);
        ctx.add("remaining_xp", String.valueOf(Experience.getExp(player)));
        MessageHandler.sendMessage(ctx, "split.success.remaining");
        playSplitSound(player);
    }
    // #endregion

    // #region Handle /exp give
    private void handleGiveExp(CommandSender sender, String[] args, MessageHandler.MessageContext ctx) {
        if (!sender.hasPermission(PERM_GIVE)) {
            MessageHandler.sendMessage(ctx, "general.no_permission");
            return;
        }

        if (args.length < 3) {
            ctx.add("command", ctx.getCommandLabel());
            MessageHandler.sendMessage(ctx, "give.usage");
            return;
        }

        Player targetPlayer = Bukkit.getPlayer(args[1]);
        if (targetPlayer == null) {
            ctx.add("target", args[1]);
            MessageHandler.sendMessage(ctx, "general.player_not_found");
            return;
        }

        String amountArg = args[2];
        if ("all".equalsIgnoreCase(amountArg)) {
            ctx.add("input", amountArg);

            MessageHandler.sendMessage(ctx, "general.invalid_amount_format");
            return;
        }

        String numPart = amountArg;
        boolean isLevels = false;
        if (amountArg.toUpperCase().endsWith("L")) {
            isLevels = true;
            numPart = amountArg.substring(0, amountArg.length() - 1);
        }

        int amountValue;
        try {
            amountValue = Integer.parseInt(numPart);
            if (amountValue <= 0) {
                ctx.add("input", amountArg);
                MessageHandler.sendMessage(ctx, "general.positive_number");
                return;
            }
        } catch (NumberFormatException e) {
            ctx.add("input", amountArg);
            MessageHandler.sendMessage(ctx, "general.invalid_number");
            return;
        }

        int expPerBottle;
        String typeKey;
        String amountValStr = amountArg;

        if (isLevels) {
            expPerBottle = Experience.getExpFromLevel(amountValue);
            typeKey = "types.levels.L";
        } else {
            expPerBottle = amountValue;
            typeKey = "types.points";
        }

        if (expPerBottle == 0) {
            MessageHandler.sendMessage(ctx, "give.error.zero_exp_per_bottle");
            return;
        }

        int count = 1;
        if (args.length > 3) {
            try {
                count = Integer.parseInt(args[3]);
                if (count <= 0) {
                    MessageHandler.sendMessage(ctx, "give.error.positive_items_amount");
                    return;
                }
            } catch (NumberFormatException e) {
                ctx.add("input", args[3]);
                MessageHandler.sendMessage(ctx, "give.error.invalid_items_amount");
                return;
            }
        }

        int itemCount = count;
        boolean levelInput = isLevels;
        String senderName = sender.getName();
        Consumer<Runnable> reply = feedback(sender);
        Runnable give = () -> {
        if (!targetPlayer.isOnline()) {
            reply.accept(() -> MessageHandler.sendMessage(ctx.add("target", args[1]), "general.player_not_found"));
            return;
        }
        String typeDisplay = MessageHandler.getRawMessage(targetPlayer, typeKey);
        if (typeDisplay == null || typeDisplay.startsWith("<red>Missing or invalid type translation:")) {
            typeDisplay = levelInput ? "levels" : "experience points";
        }
        int requiredSlots = (itemCount - 1) / Math.min(InfoKeeper.bottleMaxStackSize, Material.EXPERIENCE_BOTTLE.getMaxStackSize()) + 1;
        int availableSlots = getFreeInventorySlots(targetPlayer);

        if (availableSlots < requiredSlots) {
            ctx.add("target", targetPlayer.getName())
                    .add("required", String.valueOf(requiredSlots))
                    .add("available", String.valueOf(availableSlots));
            reply.accept(() -> MessageHandler.sendMessage(ctx, "give.error.target_inventory_full"));
            return;
        }

        String recipientNameForLore = targetPlayer.getName();

        for (int i = 0; i < itemCount; i++) {
            ItemStack bottle = new ItemStack(Material.EXPERIENCE_BOTTLE);
            ItemMeta meta = bottle.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().set(expKey, PersistentDataType.INTEGER, expPerBottle);

                List<String> loreFormat = plugin.getConfig().getStringList("bottle_lore");
                List<Component> loreComponents = new ArrayList<>();

                for (String line : loreFormat) {
                    String processedLine = line
                            .replace("<amount_xp>", String.valueOf(expPerBottle))
                            .replace("<full_levels>", String.valueOf(Experience.getLevelFromExp(expPerBottle)))
                            .replace("<amount_lvl>", String.valueOf(Experience.getLevelFromExp(expPerBottle)))
                            .replace("<player>", recipientNameForLore);
                    loreComponents.add(miniMessage.deserialize(processedLine));
                }
                meta.lore(loreComponents);
                meta.displayName(miniMessage.deserialize(
                        plugin.getConfig().getString("bottle_name_format", "<green>Experience Bottle</green>")));
                bottle.setItemMeta(meta);
            }
            targetPlayer.getInventory().addItem(bottle);
        }

        ctx.add("count", String.valueOf(itemCount))
                .add("amount_val", amountValStr)
                .add("type", typeDisplay);

        if (sender instanceof Player) {
            ctx.add("target", targetPlayer.getName());
            reply.accept(() -> MessageHandler.sendMessage(ctx, "give.success.sender"));
        }

        MessageHandler.MessageContext targetCtx = new MessageHandler.MessageContext(targetPlayer,
                ctx.getCommandLabel());
        targetCtx.add("count", String.valueOf(itemCount))
                .add("amount_val", amountValStr)
                .add("type", typeDisplay);

        if (sender instanceof Player) {
            targetCtx.add("sender", senderName);
            MessageHandler.sendMessage(targetCtx, "give.success.target");
        } else {
            MessageHandler.sendMessage(targetCtx, "give.success.target_from_console");
        }

        playSendReceiveSound(targetPlayer);
        };
        Runnable unavailable = () -> reply.accept(() -> MessageHandler.sendMessage(ctx.add("target", args[1]), "general.player_not_found"));
        try {
            OwnedTasks.run(plugin, targetPlayer, give, unavailable);
        } catch (RuntimeException ex) {
            unavailable.run();
            plugin.getLogger().log(Level.WARNING, "Could not give experience bottles", ex);
        }
    }
    // #endregion

    // #endregion

    // #region Inventory Utilities
    private Consumer<Runnable> feedback(CommandSender sender) {
        if (sender instanceof Player player) return action -> {
            OwnedTasks.run(plugin, player, action, null);
        };
        if (sender instanceof BlockCommandSender block) {
            Location location = block.getBlock().getLocation();
            return action -> trackedFeedback(action, wrapped -> Bukkit.getRegionScheduler().run(plugin, location, task -> wrapped.run()));
        }
        return action -> trackedFeedback(action, wrapped -> Bukkit.getGlobalRegionScheduler().run(plugin, task -> wrapped.run()));
    }

    private void trackedFeedback(Runnable action, Consumer<Runnable> schedule) {
        var lease = plugin.getWork().continuation();
        if (lease == null) return;
        try { schedule.accept(() -> { try { action.run(); } finally { lease.close(); } }); }
        catch (RuntimeException ex) { lease.close(); }
    }

    private boolean canReceiveItem(Player player) {
        return player.getInventory().firstEmpty() != -1;
    }

    private int getFreeInventorySlots(Player player) {
        if (!Bukkit.isOwnedByCurrentRegion(player))
            throw new IllegalStateException("Inventory access requires the player's owning region");
        int emptySlots = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getType() == Material.AIR) {
                emptySlots++;
            }
        }
        return emptySlots;
    }
    // #endregion
}
