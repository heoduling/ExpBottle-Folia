package me.brannstroom.expbottle.model;

import me.brannstroom.expbottle.ExpBottle;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Counts queued entity callbacks through execution or retirement before hot unload. */
public final class OwnedTasks {
    private OwnedTasks() {}
    public static void run(ExpBottle plugin, Player player, Runnable action, Runnable retired) {
        WorkTracker.Lease lease = plugin.getWork().continuation();
        if (lease == null) return;
        Runnable unavailable = () -> { try { if (retired != null) retired.run(); } finally { lease.close(); } };
        try {
            if (Bukkit.isOwnedByCurrentRegion(player)) {
                try { if (player.isOnline()) action.run(); else if (retired != null) retired.run(); }
                finally { lease.close(); }
            } else if (player.getScheduler().run(plugin, task -> {
                try { if (player.isOnline()) action.run(); else if (retired != null) retired.run(); }
                finally { lease.close(); }
            }, unavailable) == null) unavailable.run();
        } catch (RuntimeException ex) { unavailable.run(); }
    }
}
