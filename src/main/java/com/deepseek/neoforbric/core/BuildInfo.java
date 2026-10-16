package com.deepseek.neoforbric.core;

import java.util.Properties;


public record BuildInfo(String version, String displayName, String homepage, String gameVersion,
        String connectorVersion) {
    private static final String RESOURCE = "/neoforbric-build.properties";
    private static final String FALLBACK_VERSION = "unknown";
    private static final String FALLBACK_DISPLAY_NAME = "ModRelay";
    private static final String FALLBACK_HOMEPAGE = "https://github.com/WHanG2237/NeoForbric-Downloader";
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
                props.getProperty("display_name", FALLBACK_DISPLAY_NAME),
                props.getProperty("homepage", FALLBACK_HOMEPAGE),
                props.getProperty("game_version", FALLBACK_GAME_VERSION),
                props.getProperty("connector_version", FALLBACK_CONNECTOR_VERSION));
    }


    public static String name() {
        return load().displayName();
    }


    public String userAgent() {
        return displayName + "/" + version + " (+" + homepage + ")";
    }
}
