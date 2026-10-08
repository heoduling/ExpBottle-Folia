package me.brannstroom.expbottle.model;

import me.brannstroom.expbottle.ExpBottle;
import me.brannstroom.expbottle.handlers.MessageHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Level;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/** One in-flight debit per sender. Failed deliveries become owner-applied refunds. */
public final class ExperienceTransfers implements Listener {
    private final ExpBottle plugin;
    private final Map<UUID, Transfer> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> refunds = new ConcurrentHashMap<>();
    private final Path file;
    private volatile ExecutorService writer = newWriter();
    private static ExecutorService newWriter() { return Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ExpBottle-refund-save");
        thread.setDaemon(true);
        return thread;
    }); }
    private final AtomicLong revision = new AtomicLong();
    private final AtomicBoolean saving = new AtomicBoolean();
    private volatile boolean closing;
    private volatile boolean prepared;

    private static final class Transfer {
        final Player sender;
        final UUID id;
        final int amount;
        final AtomicBoolean settled = new AtomicBoolean();
        volatile ScheduledTask timeout;
        Transfer(Player sender, int amount) {
            this.sender = sender; this.id = sender.getUniqueId(); this.amount = amount;
        }
    }

    public ExperienceTransfers(ExpBottle plugin) throws IOException {
        this.plugin = plugin;
        file = plugin.getDataFolder().toPath().resolve("pending-refunds.properties");
        if (Files.exists(file)) {
            Properties saved = new Properties();
            try (Reader reader = Files.newBufferedReader(file)) { saved.load(reader); }
            for (String key : saved.stringPropertyNames()) {
                long amount = Long.parseLong(saved.getProperty(key));
                if (amount <= 0) throw new IOException("Invalid pending refund for " + key);
                refunds.put(UUID.fromString(key), amount);
            }
        }
    }

    public void send(Player sender, Player target, int amount, Runnable deliveredToTarget, Runnable deliveredToSender) {
        if (closing) return;
        applyRefund(sender);
        Transfer transfer = new Transfer(sender, amount);
        if (refunds.containsKey(transfer.id) || pending.putIfAbsent(transfer.id, transfer) != null) {
            MessageHandler.sendMessage(sender, "transfer.pending");
            return;
        }
        if (amount <= 0 || !Experience.canChangeExp(sender, -amount)) {
            pending.remove(transfer.id, transfer);
            MessageHandler.sendMessage(sender, "transfer.failed");
            return;
        }
        Experience.changeExp(sender, -amount);
        Runnable deliver = () -> {
            try {
                if (closing || !target.isOnline()) { refund(transfer, "transfer.failed", true); return; }
                if (!Experience.canChangeExp(target, amount)) { refund(transfer, "transfer.capacity", true); return; }
                if (!transfer.settled.compareAndSet(false, true)) return;
                try {
                    Experience.changeExp(target, amount);
                } catch (RuntimeException ex) {
                    recordRefund(transfer.id, amount);
                    onOwner(sender, () -> applyRefund(sender));
                    plugin.getLogger().log(Level.SEVERE, "Experience delivery failed; refund retained for " + transfer.id, ex);
                    return;
                } finally {
                    finish(transfer);
                }
                // Notification failure must never refund an already delivered transfer.
                onOwner(sender, deliveredToSender);
                deliveredToTarget.run();
            } catch (RuntimeException ex) {
                refund(transfer, "transfer.failed", true);
                plugin.getLogger().log(Level.SEVERE, "Transfer callback failed", ex);
            }
        };
        try {
            transfer.timeout = Bukkit.getGlobalRegionScheduler().runDelayed(plugin,
                    task -> refund(transfer, "transfer.failed", true), 200);
            if (transfer.settled.get()) transfer.timeout.cancel();
            OwnedTasks.run(plugin, target, deliver, () -> refund(transfer, "transfer.failed", true));
        } catch (RuntimeException ex) {
            refund(transfer, "transfer.failed", true);
        }
    }

    private void refund(Transfer transfer, String message, boolean notify) {
        if (!transfer.settled.compareAndSet(false, true)) return;
        recordRefund(transfer.id, transfer.amount);
        finish(transfer);
        if (notify) onOwner(transfer.sender, () -> {
            applyRefund(transfer.sender);
            MessageHandler.sendMessage(transfer.sender, message);
        });
    }

    private void finish(Transfer transfer) {
        pending.remove(transfer.id, transfer);
        ScheduledTask timeout = transfer.timeout;
        if (timeout != null) timeout.cancel();
    }

    private void onOwner(Player player, Runnable action) {
        if (closing) return;
        try {
            OwnedTasks.run(plugin, player, action, null);
        } catch (RuntimeException ex) {
            // A missing/retired sender keeps the UUID-only refund for the next login.
            plugin.getLogger().log(Level.WARNING, "Could not schedule transfer feedback/refund", ex);
        }
    }

    public void applyRefund(Player player) {
        UUID id = player.getUniqueId();
        Long debt = refunds.get(id);
        if (debt == null) return;
        long capacity = Integer.MAX_VALUE - Experience.getExpLong(player);
        int amount = (int) Math.min(debt, Math.max(0, capacity));
        if (amount == 0) return;
        // Only this player's owning region applies refunds; concurrent failures only add debt.
        Experience.changeExp(player, amount);
        refunds.compute(id, (key, current) -> current == null || current == amount ? null : current - amount);
        changed();
        MessageHandler.sendMessage(player, "transfer.refunded", "amount_xp", String.valueOf(amount));
    }

    private void recordRefund(UUID id, int amount) {
        refunds.merge(id, (long) amount, Long::sum);
        changed();
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        try (var lease = plugin.getWork().enter()) { if (lease != null) applyRefund(event.getPlayer()); }
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        try (var lease = plugin.getWork().continuation()) {
        if (lease == null) return;
        Player player = event.getPlayer();
        Transfer transfer = pending.get(player.getUniqueId());
        if (transfer != null) refund(transfer, "transfer.failed", false);
        applyRefund(player);
        }
    }

    public boolean hasPendingTransfers() { return !pending.isEmpty(); }

    /** Called only after admission is closed and all business callbacks have drained. */
    public CompletableFuture<Void> prepareClose() {
        closing = true;
        CompletableFuture<Void> result = new CompletableFuture<>();
        writer.execute(() -> {
            try { save(); prepared = true; result.complete(null); }
            catch (IOException ex) { result.completeExceptionally(ex); }
        });
        writer.shutdown();
        return result;
    }
    public boolean writerStopped() { return writer.isTerminated(); }

    /** Manager refused the operation after a successful drain; no business is admitted yet. */
    public void resumeAfterRejectedClose() {
        if (!prepared || !writer.isTerminated()) throw new IllegalStateException("Refund save not complete");
        writer = newWriter();
        prepared = false;
        closing = false;
    }

    private void changed() {
        revision.incrementAndGet();
        saveAsync();
    }

    private void saveAsync() {
        if (closing || !saving.compareAndSet(false, true)) return;
        try { writer.execute(() -> {
            long saved = -1;
            try {
                do { saved = revision.get(); save(); } while (saved != revision.get());
            } catch (IOException ex) {
                plugin.getLogger().log(Level.SEVERE, "Cannot persist pending experience refunds", ex);
            } finally {
                saving.set(false);
                if (saved != revision.get()) saveAsync();
            }
        }); } catch (RejectedExecutionException ex) {
            saving.set(false); // Normal shutdown performs the final synchronous save.
            if (!closing) throw ex;
        }
    }

    private void save() throws IOException {
        Properties data = new Properties();
        refunds.forEach((id, amount) -> data.setProperty(id.toString(), amount.toString()));
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer out = Files.newBufferedWriter(temporary)) { data.store(out, "Unclaimed ExpBottle experience refunds"); }
        try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
    }

    /** Full server shutdown runs after regional ticks have stopped, before final process exit. */
    public void close() {
        if (prepared && writer.isTerminated()) return;
        closing = true;
        pending.values().forEach(transfer -> refund(transfer, "transfer.failed", false));
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) throw new IOException("Refund writer did not stop within 5 seconds");
            save();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            plugin.getLogger().log(Level.SEVERE, "Interrupted while saving experience refunds", ex);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Pending refunds could not be saved at shutdown", ex);
        }
    }
}
