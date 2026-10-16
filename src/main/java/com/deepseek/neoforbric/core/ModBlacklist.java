package com.deepseek.neoforbric.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


public final class ModBlacklist {

    public static final String FILE_NAME = "blacklist.properties";

    private static final String LEGACY_FILE_NAME = "neoforbric-blacklist.properties";

    private static final String ORIGIN_UNREADABLE = "unreadable";

    private static final String ORIGIN_PARSED = "parsed";


    private final Map<String, String> entries;
    private final String origin;

    private ModBlacklist(Map<String, String> entries, String origin) {
        this.entries = entries;
        this.origin = origin;
    }


    public static ModBlacklist load(Path gameDir) {
        Path configDir = ForbricConfig.configDir(gameDir);
        Path file = configDir.resolve(FILE_NAME);
        Path legacy = gameDir.resolve("config").resolve(LEGACY_FILE_NAME);
        try {
            Files.createDirectories(configDir);
            if (!Files.exists(file)) {
                writeInitial(file, legacy);
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            ForbricConfig.removeLegacyLeftover(legacy, file, "blacklist");
            return parse(lines, file.getFileName().toString());
        } catch (IOException e) {
            ForbricLog.error("blacklist read failed, nothing will be changed this round: " + file, e);
            return new ModBlacklist(Map.of(), ORIGIN_UNREADABLE);
        }
    }


    public boolean unreadable() {
        return ORIGIN_UNREADABLE.equals(origin);
    }


    public boolean contains(String modId) {
        if (modId == null || modId.isBlank()) {
            return false;
        }
        return entries.containsKey(modId.trim().toLowerCase(Locale.ROOT));
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }


    public String origin() {
        return origin;
    }


    public List<String> all() {
        return List.copyOf(entries.values());
    }


    public static ModBlacklist parse(List<String> lines) {
        return parse(lines, ORIGIN_PARSED);
    }

    private static ModBlacklist parse(List<String> lines, String origin) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int equals = line.indexOf('=');
            String left = equals < 0 ? line : line.substring(0, equals);
            for (String part : left.split("[,\\s]+")) {
                String modId = part.trim();
                if (!modId.isEmpty()) {

                    entries.putIfAbsent(modId.toLowerCase(Locale.ROOT), modId);
                }
            }
        }
        return new ModBlacklist(entries, origin);
    }


    private record LegacyEntries(boolean readable, List<String> entries) {}


    private static void writeInitial(Path file, Path legacy) throws IOException {
        boolean hasLegacy = legacy != null && Files.isRegularFile(legacy);
        LegacyEntries old = legacyEntries(legacy);
        StringBuilder sb = new StringBuilder(renderTemplate());
        for (String entry : old.entries()) {
            sb.append(entry).append('\n');
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, sb.toString(), StandardCharsets.UTF_8);
        if (!verifyWritten(temp, old.entries())) {
            Files.deleteIfExists(temp);
            throw new IOException("the new blacklist file did not verify, the old one was left alone");
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        if (!hasLegacy) {
            ForbricLog.info("wrote default blacklist: %s", file);
            return;
        }
        if (!old.readable()) {
            ForbricLog.warn("the old blacklist file could not be read, keeping it for recovery: %s", legacy);
            return;
        }
        ForbricLog.info("blacklist moved to %s, %d line(s) kept from %s", file, old.entries().size(), legacy);
        ForbricConfig.removeLegacyFile(legacy, "blacklist");
    }


    private static boolean verifyWritten(Path temp, List<String> expected) {
        List<String> lines;
        try {
            lines = Files.readAllLines(temp, StandardCharsets.UTF_8);
        } catch (IOException e) {
            ForbricLog.warn("new blacklist file is unreadable, discarded: %s", e.toString());
            return false;
        }
        ModBlacklist written = parse(lines, ORIGIN_PARSED);
        for (String entry : expected) {
            String id = entry.trim();
            int equals = id.indexOf('=');
            if (equals >= 0) {
                id = id.substring(0, equals).trim();
            }
            for (String part : id.split("[,\\s]+")) {
                String modId = part.trim();
                if (!modId.isEmpty() && !written.contains(modId)) {
                    ForbricLog.warn("new blacklist file lost %s, discarded", modId);
                    return false;
                }
            }
        }
        return true;
    }


    private static LegacyEntries legacyEntries(Path legacy) {
        List<String> entries = new ArrayList<>();
        if (legacy == null || !Files.isRegularFile(legacy)) {
            return new LegacyEntries(true, entries);
        }
        try {
            for (String line : Files.readAllLines(legacy, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("!")) {
                    entries.add(trimmed);
                }
            }
        } catch (IOException | RuntimeException e) {
            ForbricLog.warn("old blacklist unreadable, built-in default (empty) will be used: %s", e.toString());
            entries.clear();
            return new LegacyEntries(false, entries);
        }
        return new LegacyEntries(true, entries);
    }


    private static String renderTemplate() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(ForbricLog.TAG).append(" (formerly NeoForbric) blacklist. Changes take"
                + " effect after restarting the game.\n");
        sb.append("#\n");
        sb.append("# One modid per line; `modid = note` also works (the note is for humans only).\n");
        sb.append("# Case insensitive; blank lines and lines starting with # or ! are ignored.\n");
        sb.append("#\n");
        sb.append("# A listed mod is left completely alone: not replaced, not searched for, not deleted,\n");
        sb.append("# not auto-filled, not enabled, not hidden. The blacklist wins over the port map.\n");
        sb.append("# Sinytra Connector and Forgified Fabric API are still downloaded when they are needed.\n");
        sb.append("#\n");
        sb.append("# Delete a line to handle that mod again; delete this whole file to restore the built-in\n");
        sb.append("# default (which is empty).\n");
        sb.append("#\n");
        sb.append("# Example (remove the leading # to activate):\n");
        sb.append("# sodium = I update it myself, leave it alone\n");
        return sb.toString();
    }
}
