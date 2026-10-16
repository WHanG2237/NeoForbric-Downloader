package com.deepseek.neoforbric.core;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;


public final class ForbricConfig {

    public static final String DIR_NAME = "neoforbric";

    public static final String FILE_NAME = "config.properties";

    private static final String LEGACY_FILE_NAME = "neoforbric.properties";


    private static final String[][] RENAMED_TIMEOUT_KEYS = {
        {"http.downloadTimeoutMs", "http.downloadTimeoutSeconds"},
        {"http.stallTimeoutMs", "http.stallTimeoutSeconds"},
        {"http.totalDeadlineMs", "http.totalDeadlineSeconds"},
    };


    private static final String[][] REMOVED_KEYS = {
        {"strict.failFast", "removed in 0.0.7: an unresolvable mod is now kept and reported by NeoForge itself", ""},
        {"source.curseforge.enable", "removed in 0.0.10: CurseForge access was removed", ""},
        {"source.curseforge.api", "removed in 0.0.10: CurseForge access was removed", ""},
        {"source.curseforge.apiKey", "removed in 0.0.10: CurseForge access was removed", "secret"},
    };

    private static final String TEMPLATE = """
            # ModRelay configuration (modid and folder names stay "neoforbric")
            # Changes take effect after restarting the game.
            # This folder also holds ports.properties (whitelist) and blacklist.properties.

            # Master switch. false = ModRelay does nothing at all.
            enable=true

            # --- Fabric mods ---
            # Download Sinytra Connector and Forgified Fabric API so Fabric mods can be loaded.
            fabric.enable=true
            # Project names to look up for those two downloads (comma separated, first hit wins).
            fabric.connector.projects=connector,sinytra-connector
            fabric.api.projects=forgified-fabric-api

            # --- Automatic restart ---
            # Restart the game when a download can only take effect on the next launch.
            restart.enable=true
            # Restarts allowed per chain.
            restart.maxAttempts=1
            # Minimum seconds between two automatic restarts.
            restart.cooldownSeconds=120
            # Milliseconds to wait before the new process counts as started.
            restart.confirmMs=1500
            # Keep the old process alive until the game exits, so launchers keep one session.
            restart.keepAlive=true

            # --- Forge mods ---
            # Replace Forge mods with the NeoForge build of the same version.
            forge.enable=true
            # Keep the original file name when replacing (launchers identify mods by name).
            forge.keepFileName=true
            # Move the replaced file to mods/.neoforbric/backup/ instead of deleting it.
            forge.backup=true

            # --- Network ---
            # Connection timeout for one request, in milliseconds (0 = unlimited).
            http.connectTimeoutMs=8000
            # Read timeout for API queries, in milliseconds (0 = unlimited).
            http.readTimeoutMs=20000
            # Timeout for one download request, in seconds (0 = unlimited).
            http.downloadTimeoutSeconds=600
            # Attempts per source.
            http.retries=3
            # File processing tasks running at once (1-128). One at a time is the default: this connection
            # gets slower, not faster, when two large files are fetched together.
            http.parallelism=1
            # Seconds without a single byte before the download is cut and resumed (0 = off).
            http.stallTimeoutSeconds=45
            # Seconds allowed for one file in total, retries included (0 = unlimited).
            http.totalDeadlineSeconds=600
            # Days to keep unfinished downloads for.
            http.resumeKeepDays=3
            # Size limit for unfinished downloads, in MB (0 = unlimited).
            http.resumeCacheMaxMb=500
            # Download eligible large files using parallel ranges. Off by default: ranges add requests and
            # only help when the server really serves each range at full speed. Ranges now follow the same
            # address order as a single stream, so switching this on no longer pins the download to the
            # slowest address.
            http.segmented.enable=false
            # Maximum ranges per file (2-8).
            http.segmented.maxSegments=2
            # Only files at least this large, in MB, are split into ranges.
            http.segmented.thresholdMb=16
            # Smallest range, in MB; the file is split into fewer ranges when it would be smaller.
            http.segmented.minSegmentMb=4
            # Download requests allowed at the same time across all files, probes included (2-64).
            http.maxActiveRequests=4
            # Download requests allowed at the same time towards one host (1-32).
            http.maxRequestsPerHost=4
            # Days a "no build found" result is trusted before the sources are asked again.
            cache.notFoundDays=7

            # --- Sources ---
            # Try official alternate addresses (for example an alternate official CDN) when one fails.
            mirror.enable=true
            # Third-party endpoints are separate switches and are off by default.
            mirror.thirdparty.modrinth.enable=false
            mirror.thirdparty.github.enable=false
            # Modrinth official API is the default and only built-in source.
            source.modrinth.official.enable=true
            # Any absolute http/https address here is used as your own endpoint. Addresses of services this
            # build removed (CurseForge) are refused in every slot. The community MCIM mirror counts as
            # third-party here too: it is refused in this slot unless mirror.thirdparty.modrinth.enable and
            # source.modrinth.mirror.enable are both on, and it is then labelled a third-party mirror, never
            # official. The check covers the addresses written in this file; a mod file is downloaded from the
            # address the API reports and HTTP redirects are followed, so a proxy can still point those
            # elsewhere. Unknown proxies are not vouched for.
            source.modrinth.official.api=https://api.modrinth.com/v2
            # A third-party Modrinth endpoint; only used when both this and the switch above are on.
            # The default is the community MCIM mirror. It stays off by default: this project's
            # registration with that service is still being confirmed, and its terms are not
            # confirmed here. Addresses of services this build removed (CurseForge) are refused.
            source.modrinth.mirror.enable=false
            source.modrinth.mirror.api=https://mod.mcimirror.top/modrinth/v2

            # --- Search, dependencies, duplicates ---
            # Search the repositories when the port map and the same-version build both missed.
            transpile.crawl.enable=true
            # true = accept only exact names and name prefixes; false = also take the best hit.
            transpile.crawl.strict=true
            # Install missing required dependencies of replaced or newly installed mods.
            dependencies.autoInstall=true
            # Dependency recursion depth (1 = direct dependencies only).
            dependencies.maxDepth=2
            # Remove duplicate jars of the same modid, keeping one of them (Forge first).
            duplicates.removeRedundant=true

            # --- Debug ---
            # Show one live progress row per active file during startup.
            progress.files=true
            # Record task counters and periodic file throughput in the instance log.
            debug.progress=false
            # Milliseconds the loading screen stays visible.
            startup.stallMs=1200
            # Mods hidden from the mod list, by modid. Built-in rules always apply.
            hide.mods=connector,connectorextras,fabric_api
            # ... by modid prefix.
            hide.prefixes=connector,connectorextras,fabric_api,fabric_,fabric_convention_tags
            # ... by jar file name prefix.
            hide.files=connector-,connectorextras-,forgified-fabric-api,fabric-
            # ... by display name.
            hide.names=Sinytra Connector,Connector Extras,Forgified Fabric API
            """;

    private final Properties props = new Properties();
    private final Path file;
    private final boolean readFailed;

    private ForbricConfig(Properties props, Path file, boolean readFailed) {
        this.props.putAll(props);
        this.file = file;
        this.readFailed = readFailed;
    }

    public boolean readFailed() {
        return readFailed;
    }

    public Path file() {
        return file;
    }

    public static ForbricConfig load(Path gameDir) {
        Path configDir = configDir(gameDir);
        Path file = configDir.resolve(FILE_NAME);
        Path legacy = gameDir.resolve("config").resolve(LEGACY_FILE_NAME);
        Properties props = new Properties();
        boolean readFailed = false;
        try {
            Files.createDirectories(configDir);
            if (!Files.exists(file)) {
                writeInitial(file, legacy);
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            removeLegacyLeftover(legacy, file, "config");
            migrateLegacyKeys(file, props);
            List<String> missing = new ArrayList<>();
            for (String key : templateKeys()) {
                if (!props.containsKey(key)) {
                    missing.add(key);
                }
            }
            if (!missing.isEmpty()) {
                ForbricLog.info("config is older than this build, %d new key(s) use built-in defaults: %s",
                        missing.size(), missing);
            }
        } catch (IOException e) {
            readFailed = true;
            ForbricLog.error("config read failed, nothing will be changed this round: " + file, e);
        }
        return new ForbricConfig(props, file, readFailed);
    }


    public static Path configDir(Path gameDir) {
        return gameDir.resolve("config").resolve(DIR_NAME);
    }


    private static void writeInitial(Path file, Path legacy) throws IOException {
        boolean hasLegacy = legacy != null && Files.isRegularFile(legacy);
        LegacyValues old = readRawValues(legacy);
        String content = applyValues(TEMPLATE, old.values());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        if (!verifyWritten(temp, old.values())) {
            Files.deleteIfExists(temp);
            throw new IOException("the new config file did not verify, the old one was left alone");
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        if (!hasLegacy) {
            ForbricLog.info("wrote default config: %s", file);
            return;
        }
        if (!old.readable()) {
            ForbricLog.warn("the old config file could not be read, keeping it for recovery: %s", legacy);
            return;
        }
        ForbricLog.info("config moved to %s, %d value(s) kept from %s", file, old.values().size(), legacy);
        removeLegacyFile(legacy, "config");
    }


    private static boolean verifyWritten(Path temp, Map<String, String> expected) {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(temp, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            ForbricLog.warn("new config file is unreadable, discarded: %s", e.toString());
            return false;
        }
        List<String> keys = templateKeys();
        for (String key : keys) {
            if (!props.containsKey(key)) {
                ForbricLog.warn("new config file is missing %s, discarded", key);
                return false;
            }
        }
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            if (!keys.contains(entry.getKey())) {
                continue;
            }
            if (!entry.getValue().equals(props.getProperty(entry.getKey()))) {
                ForbricLog.warn("new config file lost the value of %s, discarded", entry.getKey());
                return false;
            }
        }
        return true;
    }


    private record LegacyValues(boolean readable, Map<String, String> values) {}


    static void removeLegacyFile(Path legacy, String what) {
        try {
            Files.deleteIfExists(legacy);
            ForbricLog.info("old %s file removed: %s", what, legacy);
        } catch (IOException e) {
            ForbricLog.warn("old %s file could not be removed: %s", what, e.toString());
        }
    }


    static void removeLegacyLeftover(Path legacy, Path current, String what) {
        if (legacy == null || !Files.isRegularFile(legacy) || !Files.isRegularFile(current)) {
            return;
        }
        ForbricLog.info("%s file found in both the old and the new location, keeping %s", what, current);
        removeLegacyFile(legacy, what);
    }


    private static LegacyValues readRawValues(Path legacy) {
        Map<String, String> values = new LinkedHashMap<>();
        if (legacy == null || !Files.isRegularFile(legacy)) {
            return new LegacyValues(true, values);
        }
        try {
            List<String> renamed = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            for (String line : Files.readAllLines(legacy, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                String raw = trimmed.substring(eq + 1).trim();
                String target = renamedKey(key);
                if (target != null) {
                    int millis = parseIntOr(raw, -1);
                    if (millis >= 0) {
                        values.put(target, String.valueOf(millisToSeconds(millis)));
                        renamed.add(key + " -> " + target);
                    }
                    continue;
                }
                if (isRemovedKey(key)) {
                    dropped.add(key);
                    continue;
                }
                values.put(key, raw);
            }
            if (!renamed.isEmpty()) {
                ForbricLog.info("config timeouts migrated to seconds: %s", renamed);
            }
            if (!dropped.isEmpty()) {
                ForbricLog.info("old config keys this build no longer uses, dropped: %s", dropped);
            }
        } catch (IOException | RuntimeException e) {
            ForbricLog.warn("old config file unreadable, built-in defaults will be used: %s", e.toString());
            return new LegacyValues(false, values);
        }
        return new LegacyValues(true, values);
    }


    private static String renamedKey(String key) {
        for (String[] pair : RENAMED_TIMEOUT_KEYS) {
            if (key.equals(pair[0])) {
                return pair[1];
            }
        }
        return null;
    }


    private static boolean isRemovedKey(String key) {
        for (String[] pair : REMOVED_KEYS) {
            if (key.equals(pair[0])) {
                return true;
            }
        }
        return false;
    }


    private static String applyValues(String template, Map<String, String> values) {
        String[] lines = template.split("\\R", -1);
        if (values.isEmpty()) {
            return String.join("\n", lines);
        }
        List<String> out = new ArrayList<>(lines.length);
        for (String line : lines) {
            String trimmed = line.trim();
            int eq = trimmed.startsWith("#") ? -1 : trimmed.indexOf('=');
            String value = eq > 0 ? values.get(trimmed.substring(0, eq).trim()) : null;
            out.add(value == null ? trimmed : trimmed.substring(0, eq + 1) + value);
        }
        return String.join("\n", out);
    }


    private static void migrateLegacyKeys(Path file, Properties props) {
        List<String> migrated = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            boolean changed = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                String raw = trimmed.substring(eq + 1).trim();
                for (String[] pair : RENAMED_TIMEOUT_KEYS) {
                    if (!key.equals(pair[0]) || props.containsKey(pair[1])) {
                        continue;
                    }
                    int millis = parseIntOr(raw, -1);
                    if (millis < 0) {
                        continue;
                    }
                    lines.set(i, pair[1] + "=" + millisToSeconds(millis));
                    migrated.add(pair[0] + " -> " + pair[1]);
                    changed = true;
                }
                for (String[] pair : REMOVED_KEYS) {
                    if (!key.equals(pair[0])) {
                        continue;
                    }
                    String comment = pair.length > 2 && "secret".equals(pair[2])
                            ? "# " + pair[0] + "=  # " + pair[1] + "; the stored value was dropped, not copied"
                            : "# " + pair[0] + "=" + raw + "  # " + pair[1];
                    lines.set(i, comment);
                    removed.add(pair[0]);
                    changed = true;
                }
            }
            if (!changed) {
                return;
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, lines, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            props.clear();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            if (!migrated.isEmpty()) {
                ForbricLog.info("config timeouts migrated to seconds: %s", migrated);
            }
            if (!removed.isEmpty()) {
                ForbricLog.info("config keys this build no longer uses, ignored and commented out: %s."
                        + " CurseForge access was removed in 0.0.10, so nothing is queried or downloaded there any"
                        + " more; a stored CurseForge key was dropped instead of being copied. The rest of the file"
                        + " was not changed.", removed);
            }
        } catch (IOException | RuntimeException e) {
            ForbricLog.warn("config migration skipped: %s", e.toString());
        }
    }


    public static int millisToSeconds(int millis) {
        if (millis <= 0) {
            return 0;
        }
        return (millis + 999) / 1000;
    }

    private static int parseIntOr(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }


    private static List<String> templateKeys() {
        List<String> keys = new ArrayList<>();
        for (String line : TEMPLATE.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                keys.add(trimmed.substring(0, eq).trim());
            }
        }
        return keys;
    }


    public static List<String> templateKeyList() {
        return List.copyOf(templateKeys());
    }

    public boolean getBool(String key, boolean fallback) {
        String raw = props.getProperty(key);
        return raw == null ? fallback : Boolean.parseBoolean(raw.trim());
    }

    public int getInt(String key, int fallback) {
        String raw = props.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            ForbricLog.warn("config %s=%s is not an integer, using default %d", key, raw, fallback);
            return fallback;
        }
    }


    public int getSecondsAsMillis(String key, int fallbackSeconds) {
        int seconds = Math.max(0, getInt(key, fallbackSeconds));
        long millis = Math.min(seconds, 86_400L) * 1000L;
        return (int) millis;
    }

    public String get(String key, String fallback) {
        String raw = props.getProperty(key);
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }

    public List<String> getList(String key, List<String> fallback) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        List<String> result = new ArrayList<>();
        for (String part : Arrays.asList(raw.split(","))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result.isEmpty() ? fallback : result;
    }
}
