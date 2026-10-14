package com.deepseek.neoforbric.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


public final class HiddenMods {

    public static final List<String> DEFAULTS = List.of("connector", "connectorextras", "fabric_api");


    public static final List<String> DEFAULT_PREFIXES = List.of(
            "connector", "connectorextras", "fabric_api", "fabric_", "fabric_convention_tags");


    public static final List<String> DEFAULT_NAMES = List.of("Sinytra Connector", "Connector Extras", "Forgified Fabric API");


    public static final List<String> DEFAULT_FILE_PREFIXES = List.of(
            "connector-", "connectorextras-", "forgified-fabric-api", "fabric-");

    private HiddenMods() {}


    public static List<String> merge(List<String> defaults, List<String> configured) {
        Set<String> result = new LinkedHashSet<>(defaults);
        if (configured != null) {
            for (String item : configured) {
                if (item != null && !item.isBlank()) {
                    result.add(item.trim());
                }
            }
        }
        return List.copyOf(result);
    }
}
