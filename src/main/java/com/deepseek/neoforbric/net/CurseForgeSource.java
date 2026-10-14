package com.deepseek.neoforbric.net;

import com.deepseek.neoforbric.core.ForbricLog;
import com.deepseek.neoforbric.core.JsonLite;
import com.deepseek.neoforbric.core.ModJarInfo;
import com.deepseek.neoforbric.core.VersionMatch;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;


public final class CurseForgeSource implements ModSource {
    private static final int GAME_ID_MINECRAFT = 432;

    private static final int LOADER_NEOFORGE = 6;

    private static final int HASH_ALGO_SHA1 = 1;

    private static final int SEARCH_PAGE_SIZE = 5;

    private static final int FILES_PAGE_SIZE = 50;

    private static final int CDN_PATH_DIVISOR = 1000;

    private static final String CDN_BASE = "https://mediafilez.forgecdn.net/files/";


    private record CfFile(int id, int modId, String fileName, String displayName, String downloadUrl, String sha1,
            long size, long fileDate, int releaseType) {}

    private final Http http;
    private final String apiBase;
    private final String label;
    private final String apiKey;
    private final String gameVersion;

    public CurseForgeSource(Http http, String gameVersion, String apiBase, String label, String apiKey) {
        this.http = http;
        this.gameVersion = gameVersion;
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.label = label;
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.trim();
    }

    @Override
    public String name() {
        return label;
    }

    public boolean hasApiKey() {
        return apiKey != null;
    }

    @Override
    public QueryResult<TranspileTarget> find(ModJarInfo jar, boolean fallbackToLatest, String knownSha1,
            boolean strictSearch) {
        long fingerprint;
        try {
            fingerprint = Fingerprint.of(jar.path());
        } catch (IOException e) {
            ForbricLog.error("CurseForge fingerprint failed: " + jar.path().getFileName(), e);
            return QueryResult.temporary("cannot read the original file: " + e);
        }
        ForbricLog.debug("  CurseForge fingerprint = %d", fingerprint);

        int modId = -1;
        MatchKind howFound = null;
        QueryResult<?> uncertainty = null;
        QueryResult<CfFile> exact = fileByFingerprint(fingerprint);
        if (exact.hasValue()) {
            modId = exact.value().modId();
            howFound = MatchKind.FINGERPRINT;
            ForbricLog.debug("  fingerprint match: project %d, file %s", modId, exact.value().fileName());
        } else if (exact.uncertain()) {
            uncertainty = exact;
            ForbricLog.warn("  fingerprint lookup could not be answered (%s), result stays undecided",
                    exact.describe());
        }
        if (modId < 0) {
            String slug = !jar.modId().isEmpty() ? jar.modId() : jar.displayName();
            ForbricLog.warn("  fingerprint miss, falling back to slug search: %s", slug);
            QueryResult<Integer> bySlug = modIdBySlug(slug);
            if (bySlug.hasValue()) {
                modId = bySlug.value();
                howFound = MatchKind.EXACT_NAME;
            } else if (bySlug.uncertain()) {
                uncertainty = bySlug;
            }
        }
        if (modId < 0) {
            if (uncertainty != null) {
                return QueryResult.failureOf(uncertainty);
            }
            ForbricLog.info("  %s: no matching project", label);
            return QueryResult.notFound("no project named " + jar.modId() + " on " + label);
        }


        ProjectRef ref = new ProjectRef(String.valueOf(modId), jar.modId(), displayTitle(jar, modId), label);
        QueryResult<List<CfFile>> listed = listNeoForgeFiles(modId);
        if (!listed.ok()) {
            return QueryResult.failureOf(listed);
        }
        List<CfFile> files = listed.value();
        ForbricLog.info("  project %s (%s + NeoForge): %d files", ref.describe(), gameVersion, files.size());
        if (files.isEmpty()) {
            return QueryResult.notFound("project " + modId + " has no NeoForge file for " + gameVersion);
        }

        String reference = jar.version();
        Optional<CfFile> same = pickSameVersion(files, reference);
        CfFile chosen;
        boolean sameVersion;
        if (same.isPresent()) {
            chosen = same.get();
            sameVersion = true;
        } else if (fallbackToLatest) {
            chosen = pickLatest(files).orElse(null);
            sameVersion = false;
            if (chosen == null) {
                return QueryResult.notFound("project " + modId + " has no usable NeoForge file");
            }
            ForbricLog.info("  no file matching %s, using latest", reference);
        } else {
            ForbricLog.info("  no file matching %s, keeping original file", reference);
            return QueryResult.notFound("project " + modId + " has no file matching " + reference);
        }

        TranspileTarget target = toTarget(ref, chosen, sameVersion, reference, howFound);
        ForbricLog.info("  selected: %s (%s)", target.describe(), howFound.label());
        return QueryResult.ok(target);
    }

    @Override
    public QueryResult<TranspileTarget> latest(String projectIdOrSlug, String loader) {
        Optional<Integer> direct = !projectIdOrSlug.isBlank() && projectIdOrSlug.chars().allMatch(Character::isDigit)
                ? Optional.of(Integer.parseInt(projectIdOrSlug))
                : Optional.empty();
        QueryResult<Integer> resolved = direct.isPresent()
                ? QueryResult.ok(direct.get())
                : modIdBySlug(projectIdOrSlug);
        if (!resolved.hasValue()) {
            return QueryResult.failureOf(resolved);
        }

        ProjectRef ref = new ProjectRef(String.valueOf(resolved.value()), projectIdOrSlug, projectIdOrSlug, label);
        QueryResult<List<CfFile>> listed = listNeoForgeFiles(resolved.value());
        if (!listed.ok()) {
            return QueryResult.failureOf(listed);
        }
        Optional<CfFile> chosen = pickLatest(listed.value());
        if (chosen.isEmpty()) {
            return QueryResult.notFound("project " + projectIdOrSlug + " has no NeoForge file for " + gameVersion);
        }
        return QueryResult.ok(toTarget(ref, chosen.get(), false, null, MatchKind.LATEST));
    }


    @Override
    public QueryResult<TranspileTarget> forModId(String modId, String displayName, boolean strict) {
        if (modId == null || modId.isBlank()) {
            return QueryResult.notFound("no modid to look up");
        }
        QueryResult<Integer> id = modIdBySlug(modId);
        if (!id.hasValue() && !id.uncertain() && displayName != null && !displayName.isBlank()
                && !displayName.equalsIgnoreCase(modId)) {
            ForbricLog.info("  %s: no project named %s, retrying with display name %s", label, modId, displayName);
            id = modIdBySlug(displayName);
        }
        if (!id.hasValue()) {
            if (id.uncertain()) {
                return QueryResult.failureOf(id);
            }
            ForbricLog.info("  %s: no project named %s", label, modId);
            return QueryResult.notFound("no project named " + modId + " on " + label);
        }
        ProjectRef ref = new ProjectRef(String.valueOf(id.value()), modId, modId, label);
        QueryResult<List<CfFile>> listed = listNeoForgeFiles(id.value());
        if (!listed.ok()) {
            return QueryResult.failureOf(listed);
        }
        ForbricLog.info("  project %s (modid %s, %s + NeoForge): %d files", ref.describe(), modId, gameVersion,
                listed.value().size());
        Optional<CfFile> chosen = pickLatest(listed.value());
        if (chosen.isEmpty()) {
            return QueryResult.notFound("project " + id.value() + " has no NeoForge file for " + gameVersion);
        }
        return QueryResult.ok(toTarget(ref, chosen.get(), false, null, MatchKind.MODID_LOOKUP));
    }

    private TranspileTarget toTarget(ProjectRef ref, CfFile file, boolean sameVersion, String reference,
            MatchKind howFound) {
        return new TranspileTarget(ref, versionOf(file), file.fileName(), downloadUrl(file), file.sha1(), file.size(),
                sameVersion, reference, howFound);
    }


    private QueryResult<CfFile> fileByFingerprint(long fingerprint) {
        String body = "{\"fingerprints\":[" + fingerprint + "]}";
        QueryResult<String> response = http.postJson(apiBase + "/fingerprints", body,
                "CurseForge fingerprint lookup", headers());
        if (!response.hasValue()) {
            return QueryResult.failureOf(response);
        }
        Object parsed = JsonLite.parseQuietly(response.value(), "fingerprints");
        if (parsed == null) {
            return QueryResult.temporary("fingerprint response could not be parsed");
        }
        Map<String, Object> data = JsonLite.asObject(JsonLite.asObject(parsed).get("data"));
        for (Object item : JsonLite.asArray(data.get("exactMatches"))) {
            Map<String, Object> match = JsonLite.asObject(item);
            Optional<CfFile> file = parseFile(JsonLite.asObject(match.get("file")));
            if (file.isPresent()) {
                return QueryResult.ok(file.get());
            }
        }
        return QueryResult.notFound("fingerprint is not attached to any CurseForge file");
    }


    private QueryResult<Integer> modIdBySlug(String slug) {
        String url = apiBase + "/mods/search?gameId=" + GAME_ID_MINECRAFT + "&slug=" + encodeQuery(slug)
                + "&pageSize=" + SEARCH_PAGE_SIZE;
        QueryResult<String> response = http.fetchString(url, label + " search " + slug, false, false, headers());
        if (!response.hasValue()) {
            return QueryResult.failureOf(response);
        }
        Object parsed = JsonLite.parseQuietly(response.value(), "cf search");
        if (parsed == null) {
            return QueryResult.temporary("search response could not be parsed");
        }
        Map<String, Object> root = JsonLite.asObject(parsed);
        for (Object item : JsonLite.asArray(root.get("data"))) {
            Map<String, Object> mod = JsonLite.asObject(item);
            String foundSlug = JsonLite.str(mod, "slug", "");
            if (foundSlug.equalsIgnoreCase(slug)) {
                return QueryResult.ok((int) asDouble(mod.get("id")));
            }
        }
        return QueryResult.notFound("no CurseForge project with the slug " + slug);
    }


    private static String displayTitle(ModJarInfo jar, int modId) {
        if (!jar.displayName().isEmpty()) {
            return jar.displayName();
        }
        return !jar.modId().isEmpty() ? jar.modId() : String.valueOf(modId);
    }

    private QueryResult<List<CfFile>> listNeoForgeFiles(int modId) {
        String url = apiBase + "/mods/" + modId + "/files?gameVersion=" + encodeQuery(gameVersion)
                + "&modLoaderType=" + LOADER_NEOFORGE + "&pageSize=" + FILES_PAGE_SIZE;
        QueryResult<String> response = http.fetchString(url, label + " file list", false, false, headers());
        if (!response.hasValue()) {
            return QueryResult.failureOf(response);
        }
        Object parsed = JsonLite.parseQuietly(response.value(), "cf files");
        if (parsed == null) {
            return QueryResult.temporary("file list response could not be parsed");
        }
        Map<String, Object> root = JsonLite.asObject(parsed);
        List<CfFile> files = new ArrayList<>();
        for (Object item : JsonLite.asArray(root.get("data"))) {
            parseFile(JsonLite.asObject(item)).ifPresent(files::add);
        }
        return QueryResult.ok(files);
    }

    private static Optional<CfFile> parseFile(Map<String, Object> json) {
        if (json.isEmpty()) {
            return Optional.empty();
        }
        String sha1 = null;
        for (Object item : JsonLite.asArray(json.get("hashes"))) {
            Map<String, Object> hash = JsonLite.asObject(item);
            if ((int) asDouble(hash.get("algo")) == HASH_ALGO_SHA1) {
                sha1 = JsonLite.str(hash, "value", null);
            }
        }
        return Optional.of(new CfFile(
                (int) asDouble(json.get("id")),
                (int) asDouble(json.get("modId")),
                JsonLite.str(json, "fileName", ""),
                JsonLite.str(json, "displayName", ""),
                JsonLite.str(json, "downloadUrl", null),
                sha1,
                (long) asDouble(json.get("fileLength")),
                (long) asDouble(json.get("fileDate")),
                (int) asDouble(json.get("releaseType"))));
    }


    static Optional<CfFile> pickSameVersion(List<CfFile> files, String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        for (CfFile file : files) {
            String haystack = file.fileName() + " " + file.displayName();
            if (VersionMatch.containsVersion(haystack, reference)) {
                return Optional.of(file);
            }
        }
        return Optional.empty();
    }


    static Optional<CfFile> pickLatest(List<CfFile> files) {
        return files.stream().max(Comparator
                .comparingInt((CfFile f) -> f.releaseType() == 1 ? 1 : 0)
                .thenComparingLong(CfFile::fileDate));
    }

    private String downloadUrl(CfFile file) {
        if (file.downloadUrl() != null && !file.downloadUrl().isBlank()) {
            return file.downloadUrl();
        }

        return CDN_BASE + (file.id() / CDN_PATH_DIVISOR) + "/" + (file.id() % CDN_PATH_DIVISOR) + "/"
                + encodePath(file.fileName());
    }

    private Map<String, String> headers() {
        return apiKey == null ? Map.of() : Map.of("x-api-key", apiKey);
    }

    private static String versionOf(CfFile file) {
        String name = file.fileName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static double asDouble(Object value) {
        return value instanceof Double d ? d : 0d;
    }

    private static String encodeQuery(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }


    static String encodePath(String value) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean unreserved = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved) {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }
}
