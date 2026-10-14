package com.deepseek.neoforbric.net;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RequestLimiter {
    public static final RequestLimiter UNLIMITED = new RequestLimiter(0, 0);
    private final int capacity;
    private final int perHost;
    private final Map<String, Integer> hosts = new HashMap<>();
    private int active;

    public RequestLimiter(int maxActiveRequests, int maxPerHost) {
        capacity = Math.max(0, maxActiveRequests);
        perHost = Math.max(0, maxPerHost);
    }

    public boolean limited() { return capacity > 0; }
    public int maxActiveRequests() { return capacity; }
    public synchronized int availablePermits() { return Math.max(0, capacity - active); }

    public synchronized Permit acquire(String host, long waitMillis) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("request cancelled before acquiring a permit");
        }
        if (capacity == 0) { return Permit.NONE; }
        String key = host == null ? "" : host;
        long now = System.nanoTime();
        long nanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, waitMillis));
        long deadline = nanos >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + nanos;
        while (active >= capacity || (perHost > 0 && hosts.getOrDefault(key, 0) >= perHost)) {
            long remaining = deadline - System.nanoTime();
            if (waitMillis <= 0 || remaining <= 0) { return null; }
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        active++;
        hosts.merge(key, 1, Integer::sum);
        return new Permit(this, key);
    }

    private synchronized void release(String host) {
        active--;
        int count = hosts.getOrDefault(host, 0) - 1;
        if (count <= 0) { hosts.remove(host); }
        else { hosts.put(host, count); }
        notifyAll();
    }

    public static final class Permit implements AutoCloseable {
        static final Permit NONE = new Permit(null, "");
        private final RequestLimiter owner;
        private final String host;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(RequestLimiter owner, String host) {
            this.owner = owner;
            this.host = host;
        }

        @Override
        public void close() {
            if (owner != null && released.compareAndSet(false, true)) { owner.release(host); }
        }
    }
}
