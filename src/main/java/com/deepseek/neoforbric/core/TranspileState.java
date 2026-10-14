package com.deepseek.neoforbric.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;


public final class TranspileState {

    private static final int MAX_ENTRIES = 2000;

    public static final String FAILED_FORGE = "failed-forge";

    public static final String KEPT_FABRIC = "kept-fabric";

    public static final String REPLACED_FORGE = "replaced-forge";

    public static final String REPLACED_FABRIC = "replaced-fabric";

    private static final String ENV_KEY = "env";


    public record Decision(String action, String detail, long decidedAtMillis, String envHash, boolean legacy) {

        public boolean negative() {
            return action.startsWith(FAILED_FORGE) || action.startsWith(KEPT_FABRIC);
        }

        public boolean replaced() {
            return action.startsWith(REPLACED_FORGE) || action.startsWith(REPLACED_FABRIC);
        }

        public String describe() {
            return detail == null || detail.isBlank() ? action : action + ": " + detail;
        }

        public boolean expired(long ttlMillis, long nowMillis, String currentEnv) {
            if (!negative()) {
                return false;
            }
            if (legacy || decidedAtMillis <= 0) {
                return true;
            }
            if (envHash == null || envHash.isBlank() || !envHash.equals(currentEnv)) {
                return true;
            }
            return ttlMillis > 0 && nowMillis - decidedAtMillis > ttlMillis;
        }
    }

    private final Path file;
    private final Map<String, Decision> decisions = new LinkedHashMap<>();
    private final String envHash;
    private final long negativeTtlMillis;
    private boolean dirty;

    private TranspileState(Path file, String envHash, long negativeTtlMillis) {
        this.file = file;
        this.envHash = envHash;
        this.negativeTtlMillis = negativeTtlMillis;
    }

    public static TranspileState load(Path workDir, String envHash, long negativeTtlMillis) {
        Path file = workDir.resolve("state.properties");
        TranspileState state = new TranspileState(file, envHash, negativeTtlMillis);
        if (!Files.exists(file)) {
            return state;
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            ForbricLog.warn("state read failed, will re-decide: %s", e.toString());
            return state;
        }
        String storedEnv = props.getProperty(ENV_KEY, "");
        int legacy = 0;
        int stale = 0;
        long now = System.currentTimeMillis();
        for (String key : props.stringPropertyNames()) {
            if (ENV_KEY.equals(key)) {
                continue;
            }
            Decision decision = parse(props.getProperty(key));
            if (decision == null) {
                continue;
            }
            if (decision.legacy()) {
                legacy++;
            }
            if (decision.expired(negativeTtlMillis, now, envHash)) {
                stale++;
                state.dirty = true;
                continue;
            }
            state.decisions.put(key.toLowerCase(java.util.Locale.ROOT), decision);
        }
        if (!storedEnv.equals(envHash)) {
            ForbricLog.info("decision memory was written for a different setup, negative results re-checked "
                    + "(stored env %s, current %s)", storedEnv.isBlank() ? "none" : storedEnv, envHash);
        }
        if (legacy > 0) {
            ForbricLog.info("decision memory from an older build: %d entr(ies) will be decided again", legacy);
        }
        ForbricLog.debug("state loaded: %d usable, %d expired entr(ies)", state.decisions.size(), stale);
        return state;
    }


    private static Decision parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split("\\|", 4);
        if (parts.length < 2 || !isDigits(parts[1])) {
            String action = parts[0].trim();
            if (action.isEmpty()) {
                return null;
            }
            String detail = "";
            int colon = action.indexOf(':');
            if (colon > 0) {
                detail = action.substring(colon + 1).trim();
                action = action.substring(0, colon).trim();
            }
            return new Decision(action, detail, 0, "", true);
        }
        long decidedAt = parseLong(parts[1]);
        String env = parts.length > 2 ? parts[2].trim() : "";
        String detail = parts.length > 3 ? parts[3].trim() : "";
        return new Decision(parts[0].trim(), detail, decidedAt, env, false);
    }


    public Decision decisionOf(String sha1) {
        return sha1 == null ? null : decisions.get(sha1.toLowerCase(java.util.Locale.ROOT));
    }


    public void remember(String sha1, String action, String detail) {
        if (sha1 == null || sha1.isBlank()) {
            return;
        }
        decisions.put(sha1.toLowerCase(java.util.Locale.ROOT),
                new Decision(action, sanitize(detail), System.currentTimeMillis(), envHash, false));
        dirty = true;
    }

    public void save() {
        if (!dirty) {
            return;
        }
        long now = System.currentTimeMillis();
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, Decision> entry : decisions.entrySet()) {
            if (entry.getValue().expired(negativeTtlMillis, now, envHash)) {
                stale.add(entry.getKey());
            }
        }
        for (String key : stale) {
            decisions.remove(key);
        }
        if (decisions.size() > MAX_ENTRIES) {
            ForbricLog.warn("state over %d entries, cleared", MAX_ENTRIES);
            decisions.clear();
        }
        Properties props = new Properties();
        props.setProperty(ENV_KEY, envHash);
        decisions.forEach((key, decision) -> props.setProperty(key, render(decision)));
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                props.store(writer, "NeoForbric translation memory. Delete this file to decide everything again.");
            }
        } catch (IOException e) {
            ForbricLog.warn("state write failed: %s", e.toString());
        }
    }


    private static String render(Decision decision) {
        return decision.action() + "|" + decision.decidedAtMillis() + "|" + decision.envHash() + "|"
                + sanitize(decision.detail());
    }


    private static String sanitize(String detail) {
        return detail == null ? "" : detail.replace('|', '/').replace('\n', ' ').replace('\r', ' ').trim();
    }


    private static boolean isDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }


    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
