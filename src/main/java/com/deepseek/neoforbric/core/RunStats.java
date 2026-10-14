package com.deepseek.neoforbric.core;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;


public final class RunStats {

    public record Download(String what, String version, String source, long bytes, double seconds) {}


    public record Replacement(String fileName, String loader, String fromVersion, String toVersion, double seconds) {}


    public record FabricSupport(String connectorVersion, boolean connectorDownloaded, double connectorSeconds,
            String fabricApiVersion, boolean fabricApiDownloaded, double fabricApiSeconds,
            boolean connectorEnabled, boolean fabricApiEnabled) {}


    public record Dependency(String modId, String version, String source, String requiredBy) {}

    private static final List<Download> DOWNLOADS = new CopyOnWriteArrayList<>();
    private static final List<Replacement> REPLACEMENTS = new CopyOnWriteArrayList<>();
    private static final List<Dependency> DEPENDENCIES = new CopyOnWriteArrayList<>();
    private static final AtomicLong DOWNLOAD_NANOS = new AtomicLong();

    private static final AtomicLong DOWNLOAD_SPAN_START = new AtomicLong(Long.MAX_VALUE);
    private static final AtomicLong DOWNLOAD_SPAN_END = new AtomicLong(Long.MIN_VALUE);

    private static volatile long totalStartNanos;
    private static volatile long scanNanos;
    private static volatile FabricSupport fabricSupport;

    private RunStats() {}


    public static void begin() {
        totalStartNanos = System.nanoTime();
        DOWNLOADS.clear();
        REPLACEMENTS.clear();
        DEPENDENCIES.clear();
        DOWNLOAD_NANOS.set(0);
        DOWNLOAD_SPAN_START.set(Long.MAX_VALUE);
        DOWNLOAD_SPAN_END.set(Long.MIN_VALUE);
        fabricSupport = null;
    }


    public static void scanned(long nanos) {
        scanNanos = nanos;
    }


    public static void download(String what, String version, String source, long bytes, double seconds) {
        DOWNLOADS.add(new Download(what, version == null ? "" : version, source == null ? "" : source, bytes, seconds));
        DOWNLOAD_NANOS.addAndGet((long) (seconds * 1_000_000_000L));

        long end = System.nanoTime();
        long start = end - (long) (seconds * 1_000_000_000L);
        DOWNLOAD_SPAN_START.accumulateAndGet(start, Math::min);
        DOWNLOAD_SPAN_END.accumulateAndGet(end, Math::max);
    }


    public static void replaced(String fileName, String loader, String fromVersion, String toVersion, double seconds) {
        REPLACEMENTS.add(new Replacement(fileName, loader, fromVersion, toVersion, seconds));
    }


    public static void dependency(String modId, String version, String source, String requiredBy) {
        DEPENDENCIES.add(new Dependency(modId, version == null ? "" : version, source == null ? "" : source,
                requiredBy == null ? "" : requiredBy));
    }


    public static void fabricSupport(String connectorVersion, boolean connectorDownloaded, double connectorSeconds,
            String fabricApiVersion, boolean fabricApiDownloaded, double fabricApiSeconds,
            boolean connectorEnabled, boolean fabricApiEnabled) {
        fabricSupport = new FabricSupport(connectorVersion, connectorDownloaded, connectorSeconds,
                fabricApiVersion, fabricApiDownloaded, fabricApiSeconds, connectorEnabled, fabricApiEnabled);
    }


    public static double totalSeconds() {
        return totalStartNanos == 0 ? 0 : (System.nanoTime() - totalStartNanos) / 1e9;
    }


    public static double downloadSeconds() {
        long start = DOWNLOAD_SPAN_START.get();
        long end = DOWNLOAD_SPAN_END.get();
        if (start == Long.MAX_VALUE || end == Long.MIN_VALUE || end <= start) {
            return 0;
        }
        return (end - start) / 1e9;
    }


    public static double downloadBusySeconds() {
        return DOWNLOAD_NANOS.get() / 1e9;
    }


    public static double checkSeconds() {
        return Math.max(0, totalSeconds() - downloadSeconds());
    }


    public static void writeSummary() {

        ForbricLog.info("timing: scan=%s check=%s download=%s total=%s",
                seconds(scanNanos / 1e9), seconds(checkSeconds()), seconds(downloadSeconds()),
                seconds(totalSeconds()));

        if (REPLACEMENTS.isEmpty()) {
            ForbricLog.info("replaced: 0");
        } else {
            ForbricLog.info("replaced: %d", REPLACEMENTS.size());
            for (Replacement r : REPLACEMENTS) {
                ForbricLog.info("  %s [%s] %s -> %s (%s)",
                        r.fileName(), r.loader(), orUnknown(r.fromVersion()), orUnknown(r.toVersion()),
                        seconds(r.seconds()));
            }
        }

        FabricSupport support = fabricSupport;
        if (support != null) {
            ForbricLog.info("connector: %s (%s)", orUnknown(support.connectorVersion()),
                    state(support.connectorDownloaded(), support.connectorEnabled(),
                            support.connectorSeconds()));
            ForbricLog.info("ffapi: %s (%s)", orUnknown(support.fabricApiVersion()),
                    state(support.fabricApiDownloaded(), support.fabricApiEnabled(),
                            support.fabricApiSeconds()));
        }

        if (!DOWNLOADS.isEmpty()) {
            long totalBytes = 0;
            for (Download d : DOWNLOADS) {
                totalBytes += d.bytes();
            }
            ForbricLog.info("downloads: %d file(s), %s", DOWNLOADS.size(), humanBytes(totalBytes));
            for (Download d : DOWNLOADS) {
                ForbricLog.info("  %s %s (%s, %s, %s)", d.what(), orUnknown(d.version()), d.source(),
                        humanBytes(d.bytes()), seconds(d.seconds()));
            }
        }


        if (!DEPENDENCIES.isEmpty()) {
            ForbricLog.info("dependencies: %d installed", DEPENDENCIES.size());
            for (Dependency d : DEPENDENCIES) {
                ForbricLog.info("  %s %s (%s, required by %s)", d.modId(), orUnknown(d.version()), d.source(),
                        d.requiredBy());
            }
        }
    }


    private static String state(boolean downloaded, boolean enabled, double seconds) {
        if (downloaded) {
            return "downloaded in " + seconds(seconds);
        }
        return enabled ? "enabled" : "present";
    }

    private static String seconds(double value) {

        if (value > 0 && value < 1) {
            return String.format(Locale.ROOT, "%.0fms", value * 1000);
        }
        return String.format(Locale.ROOT, "%.2fs", value);
    }

    private static String humanBytes(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(Locale.ROOT, "%.2f MB", bytes / 1024.0 / 1024.0);
        }
        return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
