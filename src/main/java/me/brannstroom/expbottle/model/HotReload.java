package me.brannstroom.expbottle.model;

import me.brannstroom.expbottle.ExpBottle;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.CommandExecutor;
import org.bukkit.help.GenericCommandHelpTopic;
import org.bukkit.help.IndexHelpTopic;
import org.bukkit.help.HelpTopic;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.lang.reflect.*;
import java.lang.invoke.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Exact PlugManX 3.1.0-Beta.2 command integration; no tick waits for another region. */
public final class HotReload {
    private final ExpBottle plugin;
    private Plugin manager;
    private Map<Object, Object> registrations;
    private Object hook;
    private volatile boolean ready;
    private final AtomicBoolean preparing = new AtomicBoolean();
    private volatile ScheduledTask poll;
    private CompletableFuture<Void> saved;
    private PluginCommand managedCommand;
    private CommandExecutor originalExecutor;
    private CommandExecutor wrapper;
    private final List<NodePatch> nodePatches = new ArrayList<>();
    private record NodePatch(Object node, Field field, Object original, Object wrapper) {}
    private record Invocation(Consumer<ScheduledTask> callback, AtomicBoolean finished) {}

    public HotReload(ExpBottle plugin) { this.plugin = plugin; }

    @SuppressWarnings("unchecked")
    public void register() {
        refreshCustomHelp();
        manager = Bukkit.getPluginManager().getPlugin("PlugManX");
        if (manager == null || !manager.isEnabled()) return;
        try {
            ClassLoader loader = manager.getClass().getClassLoader();
            Class<?> api = Class.forName("bukkit.com.rylinaux.plugman.api.PlugManAPI", true, loader);
            Class<?> contract = Class.forName("bukkit.com.rylinaux.plugman.api.GentleUnload", true, loader);
            // This exact API exposes only a copy and has no unregister method.
            Field map = api.getDeclaredField("gentleUnloads");
            map.setAccessible(true);
            registrations = (Map<Object, Object>) map.get(null);
            hook = Proxy.newProxyInstance(loader, new Class<?>[]{contract}, (proxy, method, args) -> {
                if (method.getName().equals("askingForGentleUnload")) {
                    if (!ready) prepare(null, null);
                    return ready;
                }
                return switch (method.getName()) {
                    case "toString" -> "ExpBottle gentle unload";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            });
            boolean added = (Boolean) api.getMethod("pleaseAddMeToGentleUnload", Plugin.class, contract)
                    .invoke(null, plugin, hook);
            if (!added) throw new IllegalStateException("Gentle unload registration refused");
            managedCommand = Bukkit.getPluginCommand("plugman");
            if (managedCommand != null && managedCommand.getPlugin() == manager) {
                originalExecutor = managedCommand.getExecutor();
                wrapper = (sender, command, label, args) -> {
                    try {
                        MethodHandle call = MethodHandles.publicLookup().unreflect(CommandExecutor.class.getMethod(
                                "onCommand", CommandSender.class, org.bukkit.command.Command.class, String.class, String[].class));
                        call = MethodHandles.insertArguments(call, 0, originalExecutor, sender, command, label, args);
                        if (intercept(sender, args, nativeInvocation(call))) return true;
                    } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
                    return originalExecutor.onCommand(sender, command, label, args);
                };
                managedCommand.setExecutor(wrapper);
            } else {
                plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS.newHandler(event -> {
                    try {
                        restoreNodes();
                        if (!patchPaperCommands()) throw new IllegalStateException("No PlugManX command nodes after registration");
                    }
                    catch (ReflectiveOperationException | RuntimeException ex) {
                        plugin.getWork().drain();
                        plugin.getLogger().log(Level.SEVERE, "Cannot protect PlugManX commands; ExpBottle paused", ex);
                    }
                }).priority(Integer.MAX_VALUE));
                // Hot enable happens after the server's initial command registration event.
                patchPaperCommands();
            }
            plugin.getLogger().info("PlugManX controlled hot unload enabled (" + manager.getPluginMeta().getVersion() + ").");
        } catch (ReflectiveOperationException | RuntimeException ex) {
            unregister();
            throw new IllegalStateException("PlugManX integration unavailable; refusing unsafe startup", ex);
        }
    }

    private boolean intercept(CommandSender sender, String[] args, Invocation invocation) throws ReflectiveOperationException {
        if (args.length < 2) return false;
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!Set.of("load", "enable", "unload", "reload", "disable", "restart").contains(action)) return false;
        if (!sender.hasPermission("plugman." + action)) return false;
        if (!Set.of("load", "enable").contains(action) && (args[1].equalsIgnoreCase("all") || args[1].equals("*"))) {
            if (!sender.hasPermission("plugman." + action + ".all")) return false;
            sender.sendMessage("ExpBottle：请单独对 ExpBottle 执行热更新命令，再操作其他插件。");
            return true;
        }
        // PlugManX joins ALL remaining arguments into the plugin name and applies its ignore policy.
        Object plugins = manager.getClass().getMethod("getPluginManager").invoke(manager);
        Class<?> contract = Class.forName("core.com.rylinaux.plugman.plugins.PluginManager", true, manager.getClass().getClassLoader());
        Object target = contract.getMethod("getPluginByName", String[].class, int.class).invoke(plugins, args, 1);
        Class<?> targetType = Class.forName("core.com.rylinaux.plugman.plugins.Plugin", true, manager.getClass().getClassLoader());
        boolean ours = target != null && plugin.getName().equalsIgnoreCase((String) targetType.getMethod("getName").invoke(target));
        boolean ignored = target != null && (Boolean) contract.getMethod("isIgnored", targetType).invoke(plugins, target);
        if (ours && !ignored && !Set.of("load", "enable").contains(action) && !ready) prepare(sender, invocation);
        else if (Set.of("load", "enable", "reload", "restart").contains(action) || (ours && ready)) replay(sender, invocation);
        else return false;
        return true;
    }

    /** JDK adapter -> core/manager callback only. No old ExpBottle frame during new plugin construction. */
    @SuppressWarnings("unchecked")
    private static Invocation nativeInvocation(MethodHandle call) throws ReflectiveOperationException {
        AtomicBoolean finished = new AtomicBoolean();
        MethodHandle done = MethodHandles.publicLookup().findVirtual(AtomicBoolean.class, "set", MethodType.methodType(void.class, boolean.class));
        done = MethodHandles.insertArguments(done, 0, finished, true);
        done = MethodHandles.dropArguments(done, 0, Throwable.class);
        call = MethodHandles.tryFinally(call.asType(MethodType.methodType(void.class)), done);
        call = MethodHandles.dropArguments(call, 0, Object.class);
        return new Invocation(MethodHandleProxies.asInterfaceInstance(Consumer.class, call), finished);
    }

    /** Paper BasicCommand nodes bypass Bukkit command events and PluginCommand executors. */
    private boolean patchPaperCommands() throws ReflectiveOperationException {
        Class<?> commands = Class.forName("io.papermc.paper.command.brigadier.PaperCommands");
        Object dispatcher = commands.getMethod("getDispatcherInternal").invoke(commands.getField("INSTANCE").get(null));
        Object root = dispatcher.getClass().getMethod("getRoot").invoke(dispatcher);
        // The API root returns cached mirror nodes. Patch the actual dispatcher nodes.
        Object nativeDispatcher = Class.forName("io.papermc.paper.command.brigadier.ApiMirrorRootNode")
                .getMethod("getDispatcher").invoke(root);
        root = nativeDispatcher.getClass().getMethod("getRoot").invoke(nativeDispatcher);
        Class<?> nodeType = Class.forName("com.mojang.brigadier.tree.CommandNode");
        Class<?> commandType = Class.forName("com.mojang.brigadier.Command");
        Field field = nodeType.getDeclaredField("command"); field.setAccessible(true);
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String label : List.of("plugman", "plm", "plugmanx:plugman", "plugmanx:plm")) {
            Object node = nodeType.getMethod("getChild", String.class).invoke(root, label);
            if (node == null) continue;
            List<Object> nodes = new ArrayList<>(); nodes.add(node);
            nodes.addAll((Collection<?>) nodeType.getMethod("getChildren").invoke(node));
            for (Object current : nodes) {
                if (!seen.add(current)) continue;
                Object original = field.get(current);
                if (original == null) continue;
                boolean belongs = false;
                for (Field captured : original.getClass().getDeclaredFields()) {
                    captured.setAccessible(true); Object value = captured.get(original);
                    if (value != null && value.getClass().getName().equals("paper.com.rylinaux.plugman.commands.PaperCommandCreator$1")
                            && value.getClass().getClassLoader() == manager.getClass().getClassLoader()) belongs = true;
                }
                if (!belongs) throw new IllegalStateException("Unexpected PlugManX command node: " + label);
                Object replacement = Proxy.newProxyInstance(commandType.getClassLoader(), new Class<?>[]{commandType}, (proxy, method, args) -> {
                    if (method.getName().equals("run")) {
                        Object context = args[0]; Object source = context.getClass().getMethod("getSource").invoke(context);
                        CommandSender sender = (CommandSender) source.getClass().getMethod("getSender").invoke(source);
                        String[] parsed = new String[0];
                        if (nodeType.getMethod("getName").invoke(current).equals("args")) {
                            String value = (String) context.getClass().getMethod("getArgument", String.class, Class.class)
                                    .invoke(context, "args", String.class);
                            // Matches the exact Paper BasicCommand adapter's String.split(" ").
                            parsed = value.split(" ");
                        }
                        MethodHandle call = MethodHandles.publicLookup().unreflect(method).bindTo(original).bindTo(context);
                        if (intercept(sender, parsed, nativeInvocation(call))) return 1;
                    }
                    try { return method.invoke(original, args); }
                    catch (InvocationTargetException ex) { throw ex.getCause(); }
                });
                field.set(current, replacement);
                nodePatches.add(new NodePatch(current, field, original, replacement));
            }
        }
        if (!nodePatches.isEmpty()) plugin.getLogger().info("HOT_COMMAND_HOOKS nodes=" + nodePatches.size());
        return !nodePatches.isEmpty();
    }

    private void prepare(CommandSender sender, Invocation invocation) {
        if (!preparing.compareAndSet(false, true)) {
            if (sender != null) sender.sendMessage("ExpBottle：正在等待已接收的操作完成，请勿重复提交。");
            return;
        }
        plugin.getWork().drain();
        if (sender != null) sender.sendMessage("ExpBottle：正在完成在途操作并保存退款，完成后自动继续热更新。");
        long deadline = System.nanoTime() + 10_000_000_000L;
        poll = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            try {
                if (ready) {
                    if (invocation != null && invocation.finished().get()) {
                        // Still enabled means the manager refused or the initiating player retired.
                        plugin.getTransfers().resumeAfterRejectedClose();
                        saved = null; ready = false;
                        plugin.getWork().resume();
                        task.cancel(); preparing.set(false);
                        plugin.getLogger().warning("HOT_RESUMED manager did not disable this instance");
                    }
                    return;
                }
                if (System.nanoTime() > deadline) {
                    task.cancel(); preparing.set(false);
                    notifySender(sender, "ExpBottle：准备超时，未执行卸载；保持暂停接单，请处理未完成操作后重试或完整重启。");
                    plugin.getLogger().warning("Hot unload timed out; outstanding=" + plugin.getWork().outstanding());
                    return;
                }
                if (saved == null && !plugin.getTransfers().hasPendingTransfers() && plugin.getWork().sealIfIdle()) {
                    saved = plugin.getTransfers().prepareClose();
                }
                if (saved == null || !saved.isDone() || !plugin.getTransfers().writerStopped() || !plugin.getWork().idle()) return;
                saved.join(); // Already complete: never waits on a tick or I/O thread.
                ready = true;
                plugin.getLogger().info("HOT_UNLOAD_READY outstanding=0 pending=0 writerStopped=true");
                if (sender != null && invocation != null) replay(sender, invocation);
                else { task.cancel(); preparing.set(false); }
            } catch (RuntimeException ex) {
                task.cancel(); preparing.set(false);
                plugin.getLogger().log(Level.SEVERE, "Hot unload preparation failed; plugin remains paused", ex);
                notifySender(sender, "ExpBottle：保存或卸载准备失败，未继续卸载，请检查日志并完整重启。");
            }
        }, 1, 1);
    }

    private void replay(CommandSender sender, Invocation invocation) {
        if (sender instanceof Player player) {
            if (player.getScheduler().run(manager, invocation.callback(), () -> invocation.finished().set(true)) == null)
                invocation.finished().set(true);
        } else Bukkit.getGlobalRegionScheduler().run(manager, invocation.callback());
    }

    private void notifySender(CommandSender sender, String message) {
        if (sender instanceof Player player) player.getScheduler().run(manager, task -> player.sendMessage(message), null);
        else if (sender != null) sender.sendMessage(message);
    }

    public void unregister() {
        ScheduledTask task = poll;
        if (task != null) task.cancel();
        if (registrations != null && registrations.get(plugin) == hook) registrations.remove(plugin);
        if (managedCommand != null && managedCommand.getExecutor() == wrapper) managedCommand.setExecutor(originalExecutor);
        restoreNodes();
        removeOwnedHelp();
        registrations = null; hook = null;
    }

    private void removeOwnedHelp() {
        try {
            Field command = GenericCommandHelpTopic.class.getDeclaredField("command"); command.setAccessible(true);
            Field entries = IndexHelpTopic.class.getDeclaredField("allTopics"); entries.setAccessible(true);
            Set<HelpTopic> owned = Collections.newSetFromMap(new IdentityHashMap<>());
            var help = Bukkit.getHelpMap();
            synchronized (help) {
                for (HelpTopic topic : help.getHelpTopics()) {
                    if (topic instanceof GenericCommandHelpTopic && command.get(topic) instanceof PluginCommand c && c.getPlugin() == plugin)
                        owned.add(topic);
                }
                for (HelpTopic topic : help.getHelpTopics()) if (topic instanceof IndexHelpTopic && topic.getName().equals(plugin.getName())) {
                    Collection<?> topics = (Collection<?>) entries.get(topic);
                    if (!topics.isEmpty() && topics.stream().allMatch(owned::contains)) owned.add(topic);
                }
                help.getHelpTopics().removeIf(owned::contains);
                // Publish new collections; an in-progress /help keeps its original iterator.
                for (HelpTopic topic : help.getHelpTopics()) {
                    if (topic instanceof IndexHelpTopic && !topic.getClass().getName().endsWith(".CustomIndexHelpTopic")) {
                        Collection<?> topics = (Collection<?>) entries.get(topic);
                        if (topics.stream().anyMatch(owned::contains))
                            entries.set(topic, topics.stream().filter(t -> !owned.contains(t)).toList());
                    }
                }
                refreshCustomHelp();
            }
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().log(Level.SEVERE, "Cannot remove ExpBottle-owned help references", ex);
        }
    }

    /** Recreate core-owned lazy indexes, including the separately stored Default, from help.yml. */
    @SuppressWarnings("unchecked")
    private void refreshCustomHelp() {
        var help = Bukkit.getHelpMap();
        try {
            Field yaml = help.getClass().getDeclaredField("yaml"); yaml.setAccessible(true);
            Field defaults = help.getClass().getDeclaredField("defaultTopic"); defaults.setAccessible(true);
            Field topics = help.getClass().getDeclaredField("helpTopics"); topics.setAccessible(true);
            synchronized (help) {
                Object reader = yaml.get(help);
                Method indexes = reader.getClass().getDeclaredMethod("getIndexTopics"); indexes.setAccessible(true);
                Map<String, HelpTopic> map = (Map<String, HelpTopic>) topics.get(help);
                for (HelpTopic fresh : (Collection<HelpTopic>) indexes.invoke(reader)) {
                    boolean isDefault = fresh.getName().equals("Default");
                    HelpTopic old = isDefault ? (HelpTopic) defaults.get(help) : map.get(fresh.getName());
                    if (old == null || old.getClass() != fresh.getClass()) continue;
                    // Retain amendments and display settings; only the resolved references change.
                    for (Class<?> type : List.of(HelpTopic.class, IndexHelpTopic.class))
                        for (Field field : type.getDeclaredFields()) if (field.getType() == String.class) {
                            field.setAccessible(true); field.set(fresh, field.get(old));
                        }
                    if (isDefault) defaults.set(help, fresh); else map.put(fresh.getName(), fresh);
                }
            }
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().log(Level.SEVERE, "Cannot refresh custom help indexes", ex);
        }
    }

    private void restoreNodes() {
        for (NodePatch patch : nodePatches) try {
            if (patch.field().get(patch.node()) == patch.wrapper()) patch.field().set(patch.node(), patch.original());
        } catch (IllegalAccessException ex) { plugin.getLogger().log(Level.SEVERE, "Cannot restore PlugManX command node", ex); }
        nodePatches.clear();
    }
}
