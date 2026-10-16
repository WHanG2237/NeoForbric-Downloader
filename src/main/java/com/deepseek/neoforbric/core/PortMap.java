package com.deepseek.neoforbric.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;


public final class PortMap {

    public static final String FILE_NAME = "ports.properties";

    private static final String LEGACY_FILE_NAME = "neoforbric-ports.properties";

    private static final String RESOURCE = "/neoforbric-ports.properties";
    private static final String MODRINTH_PREFIX = "modrinth:";
    private static final String MODRINTH_SHORT = "mr:";
    private static final String CURSEFORGE_PREFIX = "curseforge:";
    private static final String CURSEFORGE_SHORT = "cf:";
    private static final String ANY_PREFIX = "any:";

    private static final String NO_MODID_CHECK = "*";


    public record Port(String modId, List<Target> targets, String expectedModId) {}


    public enum Source {
        ANY, MODRINTH, CURSEFORGE;

        public boolean supported() {
            return this != CURSEFORGE;
        }
    }


    public record Unsupported(String modId, String project) {}


    public record Target(String project, Source source) {}

    private final Map<String, Port> ports;
    private final String origin;
    private final boolean unreadable;

    private PortMap(Map<String, Port> ports, String origin) {
        this(ports, origin, false);
    }

    private PortMap(Map<String, Port> ports, String origin, boolean unreadable) {
        this.ports = ports;
        this.origin = origin;
        this.unreadable = unreadable;
    }

    public boolean unreadable() {
        return unreadable;
    }


    public static PortMap load(Path gameDir) {
        Path configDir = ForbricConfig.configDir(gameDir);
        Path file = configDir.resolve(FILE_NAME);
        Path legacy = gameDir.resolve("config").resolve(LEGACY_FILE_NAME);
        Properties builtin = readResource();
        try {
            Files.createDirectories(configDir);
            if (!Files.exists(file)) {
                writeInitial(file, legacy, builtin);
            }
            Properties props = new Properties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            ForbricConfig.removeLegacyLeftover(legacy, file, "port map");
            Map<String, Port> parsed = parse(props);
            if (parsed.isEmpty()) {


                ForbricLog.warn("port map has 0 entries, the whitelist is DISABLED (built-in table has %d): %s",
                        builtin.size(), file);
            }
            return new PortMap(parsed, file.getFileName().toString());
        } catch (IOException e) {
            ForbricLog.error("port map read failed, the built-in table is used instead: " + file, e);
            return new PortMap(parse(builtin), "built-in", true);
        }
    }


    public Optional<Port> find(String modId) {
        if (modId == null || modId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(ports.get(modId.trim().toLowerCase(Locale.ROOT)));
    }

    public boolean isEmpty() {
        return ports.isEmpty();
    }

    public int size() {
        return ports.size();
    }


    public String origin() {
        return origin;
    }


    public List<Port> all() {
        return List.copyOf(ports.values());
    }


    public List<Unsupported> unsupportedTargets() {
        List<Unsupported> result = new ArrayList<>();
        for (Port port : ports.values()) {
            for (Target target : port.targets()) {
                if (!target.source().supported()) {
                    result.add(new Unsupported(port.modId(), target.project()));
                }
            }
        }
        return result;
    }


    public static Map<String, Port> parse(Properties props) {
        Map<String, Port> result = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            String modId = key.trim();
            String raw = props.getProperty(key, "").trim();
            if (modId.isEmpty() || modId.startsWith("#") || raw.isEmpty()) {
                continue;
            }
            String expectedModId = modId;
            int at = raw.indexOf('@');
            if (at >= 0) {
                String declared = raw.substring(at + 1).trim();
                raw = raw.substring(0, at).trim();

                expectedModId = declared.isEmpty() || NO_MODID_CHECK.equals(declared) ? null : declared;
            }
            List<Target> targets = new ArrayList<>();
            for (String part : raw.split(",")) {
                String spec = part.trim();
                if (spec.isEmpty()) {
                    continue;
                }
                Source source = sourceOf(spec);
                spec = stripPrefix(spec);
                if (!spec.isEmpty()) {
                    targets.add(new Target(spec, source));
                }
            }
            if (targets.isEmpty()) {
                ForbricLog.warn("port map entry %s has no project, ignored", modId);
                continue;
            }
            result.put(modId.toLowerCase(Locale.ROOT), new Port(modId, List.copyOf(targets), expectedModId));
        }
        return result;
    }


    static Source sourceOf(String spec) {
        if (startsWith(spec, ANY_PREFIX)) {
            return Source.ANY;
        }
        if (startsWith(spec, CURSEFORGE_PREFIX) || startsWith(spec, CURSEFORGE_SHORT)) {
            return Source.CURSEFORGE;
        }
        if (startsWith(spec, MODRINTH_PREFIX) || startsWith(spec, MODRINTH_SHORT)) {
            return Source.MODRINTH;
        }
        return isAllDigits(spec) ? Source.CURSEFORGE : Source.MODRINTH;
    }


    static String stripPrefix(String spec) {
        for (String prefix : List.of(ANY_PREFIX, CURSEFORGE_PREFIX, CURSEFORGE_SHORT, MODRINTH_PREFIX,
                MODRINTH_SHORT)) {
            if (startsWith(spec, prefix)) {
                return spec.substring(prefix.length()).trim();
            }
        }
        return spec.trim();
    }

    private static boolean startsWith(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean isAllDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }


    private record LegacyEntries(boolean readable, List<String> entries) {}


    private static void writeInitial(Path file, Path legacy, Properties builtin) throws IOException {
        boolean hasLegacy = legacy != null && Files.isRegularFile(legacy);
        LegacyEntries old = legacyEntries(legacy);
        StringBuilder sb = new StringBuilder(renderTemplate(builtin));
        for (String entry : old.entries()) {
            sb.append(entry).append('\n');
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, sb.toString(), StandardCharsets.UTF_8);
        if (!verifyWritten(temp, old.entries())) {
            Files.deleteIfExists(temp);
            throw new IOException("the new port map file did not verify, the old one was left alone");
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        if (!hasLegacy) {
            ForbricLog.info("wrote default port map: %s", file);
            return;
        }
        if (!old.readable()) {
            ForbricLog.warn("the old port map file could not be read, keeping it for recovery: %s", legacy);
            return;
        }
        ForbricLog.info("port map moved to %s, %d entr(ies) kept from %s", file, old.entries().size(), legacy);
        ForbricConfig.removeLegacyFile(legacy, "port map");
    }


    private static boolean verifyWritten(Path temp, List<String> expected) {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(temp, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            ForbricLog.warn("new port map file is unreadable, discarded: %s", e.toString());
            return false;
        }
        Map<String, Port> written = parse(props);
        for (String entry : expected) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String modId = entry.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            if (!written.containsKey(modId)) {
                ForbricLog.warn("new port map file lost %s, discarded", modId);
                return false;
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
            ForbricLog.warn("old port map unreadable, built-in table will be used: %s", e.toString());
            entries.clear();
            return new LegacyEntries(false, entries);
        }
        return new LegacyEntries(true, entries);
    }


    private static String renderTemplate(Properties builtin) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(ForbricLog.TAG).append(" (formerly NeoForbric) port map (whitelist)."
                + " Changes take effect after restarting the game.\n");
        sb.append("#\n");
        sb.append("# One entry per line: <source modid> = <target>[,<target>...][@<expected modid>]\n");
        sb.append("#   target: a Modrinth project id or slug; mr: / modrinth: are accepted too, and\n");
        sb.append("#           any: searches the whole source chain.\n");
        sb.append("#   A bare number is a CurseForge project id. CurseForge access was removed in\n");
        sb.append("#   0.0.10, so such a target is skipped with a warning: replace it with a Modrinth\n");
        sb.append("#   id or slug, or delete the target. cf: and curseforge: are skipped the same way.\n");
        sb.append("#   Targets are tried in order, first hit wins.\n");
        sb.append("#   Without @, the expected modid is the source modid on the left of the equals sign.\n");
        sb.append("#\n");
        sb.append("# A hit downloads the latest build of that project for this game version and replaces\n");
        sb.append("# the original file, keeping its name.\n");
        sb.append("# Deleting a line only removes the forced port: that mod goes through the normal pipeline\n");
        sb.append("# again. To leave a mod completely alone, list it in blacklist.properties instead.\n");
        sb.append("# Delete this whole file to restore the built-in default table.\n");
        sb.append("\n");
        List<String> keys = new ArrayList<>(builtin.stringPropertyNames());
        keys.sort(String::compareToIgnoreCase);
        for (String key : keys) {
            sb.append(key).append('=').append(builtin.getProperty(key, "").trim()).append('\n');
        }
        return sb.toString();
    }

    private static Properties readResource() {
        Properties props = new Properties();
        try (InputStream in = PortMap.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            ForbricLog.warn("built-in port map unreadable: %s", e.toString());
        }
        return props;
    }
}
