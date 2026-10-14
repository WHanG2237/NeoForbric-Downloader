package com.deepseek.neoforbric.core;

import java.util.Locale;


public final class Timing {
    private Timing() {}


    public static String elapsed(long startNanos) {
        return seconds(secondsOf(startNanos));
    }


    public static String seconds(double value) {
        return String.format(Locale.ROOT, "%.1f s", value);
    }


    public static double secondsOf(long startNanos) {

        return Math.max(0.001, (System.nanoTime() - startNanos) / 1_000_000_000.0);
    }


    public static String speed(long bytes, long startNanos) {
        double kb = bytes / 1024.0;
        return String.format(Locale.ROOT, "%.0f KB/s", kb / secondsOf(startNanos));
    }


    public static String elapsedWithSpeed(long bytes, long startNanos) {
        return "took " + elapsed(startNanos) + ", avg " + speed(bytes, startNanos);
    }

}
