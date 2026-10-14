package com.deepseek.neoforbric.body;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


public final class HideList {

    public static final List<String> DEFAULT_IDS = List.of("connector", "connectorextras", "fabric_api");

    public static final List<String> DEFAULT_PREFIXES = List.of(
            "connector", "connectorextras", "fabric_api", "fabric_", "fabric_convention_tags");

    public static final List<String> DEFAULT_NAMES = List.of("Sinytra Connector", "Connector Extras", "Forgified Fabric API");

    public static final List<String> DEFAULT_FILE_PREFIXES = List.of(
            "connector-", "connectorextras-", "forgified-fabric-api", "fabric-");

    private static final String FILE_NAME = "hide.properties";

    private static volatile Set<String> ids;
    private static volatile Set<String> prefixes;
    private static volatile Set<String> names;
    private static volatile Set<String> filePrefixes;

    private HideList() {}

    public static Set<String> ids() {
        resolve();
        return ids;
    }

    public static Set<String> prefixes() {
        resolve();
        return prefixes;
    }

    public static Set<String> names() {
        resolve();
        return names;
    }

    public static Set<String> filePrefixes() {
        resolve();
        return filePrefixes;
    }


    public static boolean matchesFile(String fileName) {
        if (fileName == null) {
            return false;
        }
        resolve();
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        for (String prefix : filePrefixes) {
            if (lower.startsWith(prefix.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }


    public static boolean matches(String modId, String displayName, String fileName) {
        resolve();
        if (matchesFile(fileName)) {
            return true;
        }
        if (modId != null) {
            String lower = modId.toLowerCase(java.util.Locale.ROOT);
            for (String hidden : ids) {
                if (hidden.equalsIgnoreCase(modId)) {
                    return true;
                }
            }
            for (String prefix : prefixes) {
                String p = prefix.toLowerCase(java.util.Locale.ROOT);
                if (lower.equals(p) || lower.startsWith(p + "_")) {
                    return true;
                }
            }
        }
        if (displayName != null) {
            String trimmed = displayName.trim();
            for (String hidden : names) {
                if (hidden.equalsIgnoreCase(trimmed)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isHidden(net.neoforged.fml.ModContainer container) {
        try {
            String fileName = owningFileName(container);
            if (matches(container.getModId(), container.getModInfo().getDisplayName(), fileName)) {
                return true;
            }
            return belongsToHiddenJar(container);
        } catch (Throwable t) {
            return false;
        }
    }

    public static int hiddenCount() {
        int hidden = 0;
        try {
            for (net.neoforged.fml.ModContainer container : net.neoforged.fml.ModList.get().getSortedMods()) {
                if (isHidden(container)) {
                    hidden++;
                }
            }
        } catch (Throwable t) {
            return 0;
        }
        return hidden;
    }

    private static String owningFileName(net.neoforged.fml.ModContainer container) {
        try {
            var owningFile = container.getModInfo().getOwningFile();
            return owningFile == null || owningFile.getFile() == null ? null : owningFile.getFile().getFileName();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean belongsToHiddenJar(net.neoforged.fml.ModContainer container) {
        try {
            var owningFile = container.getModInfo().getOwningFile();
            if (owningFile == null || owningFile.getFile() == null) {
                return false;
            }
            var file = owningFile.getFile();
            int guard = 0;
            while (file != null && guard++ < 16) {
                if (matchesFile(file.getFileName())) {
                    return true;
                }
                var attributes = file.getDiscoveryAttributes();
                file = attributes == null ? null : attributes.parent();
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

    private static void resolve() {        if (ids != null && prefixes != null && names != null && filePrefixes != null) {
            return;
        }
        synchronized (HideList.class) {
            if (ids != null && prefixes != null && names != null && filePrefixes != null) {
                return;
            }
            List<String> loadedIds = DEFAULT_IDS;
            List<String> loadedPrefixes = DEFAULT_PREFIXES;
            List<String> loadedNames = DEFAULT_NAMES;
            List<String> loadedFiles = DEFAULT_FILE_PREFIXES;
            Path file = locateFile();
            if (file != null && Files.isRegularFile(file)) {
                try {
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        int eq = line.indexOf('=');
                        if (eq <= 0) {
                            continue;
                        }
                        String key = line.substring(0, eq).trim();
                        List<String> values = split(line.substring(eq + 1));
                        if (values.isEmpty()) {
                            continue;
                        }
                        switch (key) {
                            case "ids" -> loadedIds = values;
                            case "prefixes" -> loadedPrefixes = values;
                            case "names" -> loadedNames = values;
                            case "files" -> loadedFiles = values;
                            default -> {

                            }
                        }
                    }
                } catch (IOException e) {
                    BodyLog.warn("hide list read failed, using defaults: %s", e.toString());
                }
            }
            ids = union(DEFAULT_IDS, loadedIds);
            prefixes = union(DEFAULT_PREFIXES, loadedPrefixes);
            names = union(DEFAULT_NAMES, loadedNames);
            filePrefixes = union(DEFAULT_FILE_PREFIXES, loadedFiles);
        }
    }


    private static Set<String> union(List<String> defaults, List<String> fromFile) {
        Set<String> result = new LinkedHashSet<>(defaults);
        if (fromFile != null) {
            for (String item : fromFile) {
                if (item != null && !item.isBlank()) {
                    result.add(item.trim());
                }
            }
        }
        return result;
    }

    private static List<String> split(String raw) {
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static Path locateFile() {
        try {
            Path gameDir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
            return gameDir == null ? null : gameDir.resolve("mods").resolve(".neoforbric").resolve(FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }
}
