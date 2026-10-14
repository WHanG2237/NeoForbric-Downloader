package com.deepseek.neoforbric.core;


public enum ModKind {

    NEOFORGE("NeoForge"),

    FORGE("Forge"),

    FABRIC("Fabric"),

    QUILT("Quilt"),

    LITELOADER("LiteLoader"),

    UNKNOWN("unknown");

    private final String display;

    ModKind(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }
}
