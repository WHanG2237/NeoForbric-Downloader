package com.deepseek.neoforbric.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;


public final class StartupTimer {
    private StartupTimer() {}


    public static Instant jvmStart() {
        try {
            return ProcessHandle.current().info().startInstant().orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }


    public static double sinceJvmStartSeconds() {
        Instant start = jvmStart();
        if (start == null) {
            return -1;
        }
        return Duration.between(start, Instant.now()).toNanos() / 1_000_000_000.0;
    }


    public static String sinceJvmStart() {
        double seconds = sinceJvmStartSeconds();
        return seconds < 0 ? "unknown" : String.format(Locale.ROOT, "%.1f s", seconds);
    }
}
