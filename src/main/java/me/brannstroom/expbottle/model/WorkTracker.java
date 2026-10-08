package me.brannstroom.expbottle.model;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Admission/counting only: never holds a lock while executing Bukkit work. */
public final class WorkTracker {
    private boolean accepting = true;
    private boolean closed;
    private int outstanding;
    private final Map<UUID, Lease> flights = new HashMap<>();

    public synchronized Lease enter() { return acquire(false); }
    public synchronized Lease continuation() { return acquire(true); }
    private Lease acquire(boolean continuation) {
        if (closed || (!accepting && !continuation)) return null;
        outstanding++;
        return new Lease();
    }
    public synchronized void drain() { accepting = false; }
    public synchronized void resume() { closed = false; accepting = true; }
    public synchronized boolean idle() { return outstanding == 0; }
    public synchronized int outstanding() { return outstanding; }
    public synchronized boolean accepting() { return accepting && !closed; }
    public synchronized boolean sealIfIdle() {
        if (outstanding != 0) return false;
        accepting = false; closed = true; return true;
    }
    public synchronized void trackFlight(UUID id) {
        if (!flights.containsKey(id)) {
            // Restored entities still need accounting while admission is sealed.
            outstanding++;
            flights.put(id, new Lease());
        }
    }
    public synchronized void finishFlight(UUID id) {
        Lease lease = flights.remove(id);
        if (lease != null) lease.close();
    }
    public synchronized void close() {
        accepting = false; closed = true;
        flights.values().forEach(Lease::close); flights.clear();
    }
    public final class Lease implements AutoCloseable {
        private final AtomicBoolean finished = new AtomicBoolean();
        @Override public void close() {
            if (finished.compareAndSet(false, true)) synchronized (WorkTracker.this) { outstanding--; }
        }
    }
}
