package com.deepseek.neoforbric.core;

import java.util.Properties;


public record BuildInfo(String version, String gameVersion, String connectorVersion) {
    private static final String RESOURCE = "/neoforbric-build.properties";
    private static final String FALLBACK_VERSION = "unknown";
    private static final String FALLBACK_GAME_VERSION = "unknown";
    private static final String FALLBACK_CONNECTOR_VERSION = "unknown";

    private static volatile BuildInfo cached;


    public static BuildInfo load() {
        BuildInfo local = cached;
        if (local != null) {
            return local;
        }
        synchronized (BuildInfo.class) {
            if (cached == null) {
                cached = read();
            }
            return cached;
        }
    }

    private static BuildInfo read() {
        Properties props = new Properties();
        try (var in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                props.load(in);
            }
        } catch (Exception ignored) {

        }
        return new BuildInfo(
                props.getProperty("version", FALLBACK_VERSION),
                props.getProperty("game_version", FALLBACK_GAME_VERSION),
                props.getProperty("connector_version", FALLBACK_CONNECTOR_VERSION));
    }


    public String userAgent() {
        return "NeoForbric/" + version + " (NeoForge " + gameVersion + " transpiler)";
    }
}
