package com.deepseek.neoforbric.body.client;

import com.deepseek.neoforbric.body.BodyLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;


final class BodyConfigFile {

    private static final List<String> PORTS_HEADER = List.of(
            "# NeoForbric port map (whitelist). Changes take effect after restarting the game.",
            "#",
            "# One entry per line: <source modid> = <target>[,<target>...][@<expected modid>]",
            "#   target: a bare number is a CurseForge project id, anything else is a Modrinth",
            "#           id or slug; cf: / mr: pick the source, any: searches the whole chain.",
            "#   Targets are tried in order, first hit wins.",
            "#   Without @, the expected modid is the source modid on the left of the equals sign.",
            "#",
            "# Deleting a line only removes the forced port: that mod goes through the normal pipeline",
            "# again. To leave a mod completely alone, list it in blacklist.properties instead.",
            "# Delete this whole file to restore the built-in default table.",
            "");


    private static final List<String> BLACKLIST_HEADER = List.of(
            "# NeoForbric blacklist. Changes take effect after restarting the game.",
            "#",
            "# One modid per line; `modid = note` also works (the note is for humans only).",
            "# Case insensitive; blank lines and lines starting with # or ! are ignored.",
            "#",
            "# A listed mod is left completely alone: not replaced, not searched for, not deleted,",
            "# not auto-filled, not enabled, not hidden. The blacklist wins over the port map.",
            "# Sinytra Connector and Forgified Fabric API are still downloaded when they are needed.",
            "");


    record EntryList(boolean readable, List<String> entries) {}

    private final Path file;
    private final List<String> lines = new ArrayList<>();
    private final Map<String, String> values = new LinkedHashMap<>();
    private final boolean existed;
    private final boolean readFailed;

    private BodyConfigFile(Path file, boolean existed, boolean readFailed) {
        this.file = file;
        this.existed = existed;
        this.readFailed = readFailed;
    }


    static BodyConfigFile load(Path file) {
        boolean existed = Files.isRegularFile(file);
        List<String> lines = new ArrayList<>();
        boolean failed = false;
        if (existed) {
            try {
                lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                failed = true;
                BodyLog.error("config file could not be read, saving is disabled: %s (%s)", file, e);
            }
        }
        BodyConfigFile config = new BodyConfigFile(file, existed, failed);
        config.lines.addAll(lines);
        if (failed) {
            return config;
        }
        for (String line : config.lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                config.values.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            }
        }
        return config;
    }

    boolean readFailed() {
        return readFailed;
    }

    boolean getBool(String key, boolean fallback) {
        String value = values.get(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    int getInt(String key, int fallback) {
        String value = values.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }


    String getRaw(String key, String fallback) {
        String value = values.get(key);
        return value == null ? fallback : value;
    }


    void set(String key, Object value) {
        values.put(key, value instanceof Boolean bool ? Boolean.toString(bool) : String.valueOf(value));
    }


    void save() throws IOException {
        if (readFailed) {
            throw new IOException("config file " + file + " could not be read; refusing to overwrite it");
        }
        if (!existed && lines.isEmpty()) {
            throw new IOException("config file " + file + " was never written; refusing to write an empty one");
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            Pattern pattern = Pattern.compile("^\\s*" + Pattern.quote(key) + "\\s*=");
            int index = -1;
            int duplicates = 0;
            for (int i = 0; i < lines.size(); i++) {
                if (!pattern.matcher(lines.get(i)).find()) {
                    continue;
                }
                if (index < 0) {
                    index = i;
                    continue;
                }
                lines.set(i, "# " + lines.get(i).trim() + "   # duplicate, disabled by NeoForbric");
                duplicates++;
            }
            String newLine = key + "=" + entry.getValue();
            if (index >= 0) {
                lines.set(index, newLine);
            } else {
                lines.add(newLine);
            }
            if (duplicates > 0) {
                BodyLog.warn("%s was defined %d extra time(s); the later lines are commented out so the saved "
                        + "value is the one that takes effect", key, duplicates);
            }
        }
        writeAtomically(file, lines);
    }


    private static void writeAtomically(Path file, List<String> lines) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }


    static EntryList readEntries(Path file) {
        return readEntryLines(file, true);
    }


    static EntryList readBlacklist(Path file) {
        return readEntryLines(file, false);
    }

    private static EntryList readEntryLines(Path file, boolean requireEquals) {
        List<String> entries = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return new EntryList(true, entries);
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    continue;
                }
                if (requireEquals && !trimmed.contains("=")) {
                    continue;
                }
                entries.add(trimmed);
            }
        } catch (IOException e) {
            BodyLog.error("could not read %s, saving is disabled for it: %s", file, e);
            return new EntryList(false, List.of());
        }
        return new EntryList(true, entries);
    }


    static void saveEntries(Path file, List<String> entries) throws IOException {
        saveEntryLines(file, entries, true);
    }


    static void saveBlacklist(Path file, List<String> entries) throws IOException {
        saveEntryLines(file, entries, false);
    }

    private static void saveEntryLines(Path file, List<String> entries, boolean requireEquals) throws IOException {
        List<String> header = new ArrayList<>();
        if (Files.isRegularFile(file)) {
            List<String> existing;
            try {
                existing = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IOException("file " + file + " could not be read; refusing to overwrite it", e);
            }
            for (String line : existing) {
                String trimmed = line.trim();
                boolean isEntry = !trimmed.isEmpty() && !trimmed.startsWith("#")
                        && (!requireEquals || trimmed.contains("="));
                if (isEntry) {
                    break;
                }
                header.add(line);
            }
        }
        if (header.isEmpty()) {
            header.addAll(requireEquals ? PORTS_HEADER : BLACKLIST_HEADER);
        }
        List<String> out = new ArrayList<>(header);
        entries.stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#") && (!requireEquals || line.contains("=")))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(out::add);
        writeAtomically(file, out);
    }


    static boolean isValidEntry(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        int eq = trimmed.indexOf('=');
        if (eq <= 0 || eq >= trimmed.length() - 1) {
            return false;
        }
        String key = trimmed.substring(0, eq).trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty() || key.contains(" ")) {
            return false;
        }
        String value = trimmed.substring(eq + 1).trim();
        if (value.isEmpty()) {
            return false;
        }
        for (String part : value.split(",", -1)) {
            String spec = part.trim();
            if (spec.isEmpty() || spec.contains(" ")) {
                return false;
            }
        }
        return true;
    }


    static boolean isValidBlacklistEntry(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        int eq = trimmed.indexOf('=');
        String id = (eq >= 0 ? trimmed.substring(0, eq) : trimmed).trim();
        return !id.isEmpty() && id.indexOf(' ') < 0;
    }
}
