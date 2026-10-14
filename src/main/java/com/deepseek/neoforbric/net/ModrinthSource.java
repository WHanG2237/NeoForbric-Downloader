package com.deepseek.neoforbric.net;

import com.deepseek.neoforbric.core.ForbricLog;
import com.deepseek.neoforbric.core.Hashes;
import com.deepseek.neoforbric.core.JsonLite;
import com.deepseek.neoforbric.core.VersionMatch;
import com.deepseek.neoforbric.core.ModJarInfo;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;


public final class ModrinthSource implements ModSource {
    public static final String LOADER_NEOFORGE = "neoforge";

    public record SearchHit(String projectId, MatchKind kind) {}

    private final Http http;
    private final String gameVersion;
    private final String apiBase;
    private final String label;

    public ModrinthSource(Http http, String gameVersion, String apiBase, String label) {
        this.http = http;
        this.gameVersion = gameVersion;
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.label = label;
    }

    @Override
    public String name() {
        return label;
    }


    @Override
    public QueryResult<TranspileTarget> find(ModJarInfo jar, boolean fallbackToLatest, String knownSha1,
            boolean strictSearch) {
        String sha1 = knownSha1;
        if (sha1 == null || sha1.isBlank()) {
            try {
                sha1 = Hashes.sha1(jar.path());
            } catch (IOException e) {
                ForbricLog.error("SHA1 failed: " + jar.path().getFileName(), e);
                return QueryResult.temporary("cannot read the original file: " + e);
            }
        }
        ForbricLog.debug("  SHA1 = %s", sha1);

        String projectId = null;
        String referenceVersion = jar.version();
        MatchKind howFound = null;
        QueryResult<?> uncertainty = null;

        QueryResult<VersionCandidate> byHash = versionByHash(sha1);
        if (byHash.hasValue()) {
            VersionCandidate sourceFile = byHash.value();
            projectId = sourceFile.projectId();
            referenceVersion = sourceFile.versionNumber();
            howFound = MatchKind.HASH;
            ForbricLog.debug("  SHA1 lookup hit: project %s, file version %s", projectId, referenceVersion);
        } else if (byHash.uncertain()) {
            uncertainty = byHash;
            ForbricLog.warn("  SHA1 lookup could not be answered (%s), result stays undecided", byHash.describe());
        }
        if (projectId == null) {
            String query = !jar.modId().isEmpty() ? jar.modId() : jar.displayName();
            ForbricLog.warn("  SHA1 lookup miss, falling back to keyword search: %s", query);
            QueryResult<SearchHit> searched = searchProjectId(query, !strictSearch);
            if (searched.hasValue()) {
                projectId = searched.value().projectId();
                howFound = searched.value().kind();
            } else if (searched.uncertain()) {
                uncertainty = searched;
            }
        }
        if (projectId == null || projectId.isEmpty()) {
            if (uncertainty != null) {
                return QueryResult.failureOf(uncertainty);
            }
            ForbricLog.info("  %s: no matching project (modid=%s)", label, jar.modId());
            return QueryResult.notFound("no project named " + jar.modId() + " on " + label);
        }


        ProjectRef project = new ProjectRef(projectId, projectId, displayTitle(jar, projectId), label);
        QueryResult<List<VersionCandidate>> listed = listVersions(projectId, LOADER_NEOFORGE);
        if (!listed.ok()) {
            return QueryResult.failureOf(listed);
        }
        List<VersionCandidate> candidates = listed.value();
        ForbricLog.info("  project %s (%s + NeoForge): %d versions", project.describe(), gameVersion, candidates.size());
        if (candidates.isEmpty()) {
            return QueryResult.notFound("project " + projectId + " has no NeoForge build for " + gameVersion);
        }

        Optional<VersionCandidate> sameVersion = pickSameVersion(candidates, referenceVersion);
        VersionCandidate chosen;
        boolean same;
        if (sameVersion.isPresent()) {
            chosen = sameVersion.get();
            same = true;
        } else if (fallbackToLatest) {
            Optional<VersionCandidate> latest = pickSameOrLatest(candidates, null);
            if (latest.isEmpty()) {
                return QueryResult.notFound("project " + projectId + " has no usable NeoForge build");
            }
            chosen = latest.get();
            same = false;
            ForbricLog.info("  no build matching %s, using latest", referenceVersion);
        } else {
            ForbricLog.info("  no build matching %s, keeping original file", referenceVersion);
            return QueryResult.notFound("project " + projectId + " has no build matching " + referenceVersion);
        }

        TranspileTarget target = new TranspileTarget(project, chosen.versionNumber(), chosen.fileName(),
                chosen.downloadUrl(), chosen.sha1(), chosen.size(), same, referenceVersion, howFound);
        ForbricLog.info("  selected: %s (%s)", target.describe(), howFound.label());
        return QueryResult.ok(target);
    }

    @Override
    public QueryResult<TranspileTarget> latest(String projectIdOrSlug, String loader) {
        QueryResult<List<VersionCandidate>> listed = listVersions(projectIdOrSlug, loader);
        if (!listed.ok()) {
            return QueryResult.failureOf(listed);
        }
        if (listed.value().isEmpty()) {
            return QueryResult.notFound("project " + projectIdOrSlug + " has no " + loader + " build for " + gameVersion);
        }
        Optional<VersionCandidate> chosen = pickSameOrLatest(listed.value(), null);
        if (chosen.isEmpty()) {
            return QueryResult.notFound("project " + projectIdOrSlug + " has no usable " + loader + " build");
        }

        ProjectRef project = new ProjectRef(projectIdOrSlug, projectIdOrSlug, projectIdOrSlug, label);
        VersionCandidate version = chosen.get();
        return QueryResult.ok(new TranspileTarget(project, version.versionNumber(), version.fileName(),
                version.downloadUrl(), version.sha1(), version.size(), false, null, MatchKind.LATEST));
    }


    public QueryResult<VersionCandidate> versionByHash(String sha1) {
        String url = apiBase + "/version_file/" + sha1 + "?algorithm=sha1";
        QueryResult<String> body = http.fetchString(url, label + " SHA1 lookup", true, false);
        if (!body.hasValue()) {
            return QueryResult.failureOf(body);
        }
        Object parsed = JsonLite.parseQuietly(body.value(), "version_file");
        if (parsed == null) {
            return QueryResult.temporary("sha1 lookup response could not be parsed");
        }
        Optional<VersionCandidate> candidate = VersionCandidate.fromJson(JsonLite.asObject(parsed));
        return candidate.map(QueryResult::ok)
                .orElseGet(() -> QueryResult.notFound("sha1 is not attached to any file"));
    }


    public QueryResult<VersionCandidate> versionById(String versionId) {
        String url = apiBase + "/version/" + encode(versionId);
        QueryResult<String> body = http.fetchString(url, label + " version " + versionId, false, false);
        if (!body.hasValue()) {
            return QueryResult.failureOf(body);
        }
        Object parsed = JsonLite.parseQuietly(body.value(), "version");
        if (parsed == null) {
            return QueryResult.temporary("version response could not be parsed");
        }
        Optional<VersionCandidate> candidate = VersionCandidate.fromJson(JsonLite.asObject(parsed));
        return candidate.map(QueryResult::ok)
                .orElseGet(() -> QueryResult.notFound("version " + versionId + " has no downloadable file"));
    }


    public QueryResult<List<VersionCandidate>> listVersions(String projectId, String loader) {
        String url = apiBase + "/project/" + encode(projectId) + "/version"
                + "?game_versions=" + encode("[\"" + gameVersion + "\"]")
                + "&loaders=" + encode("[\"" + loader + "\"]");
        QueryResult<String> body = http.fetchString(url, label + " version list", false, false);
        if (!body.hasValue()) {
            return QueryResult.failureOf(body);
        }
        Object parsed = JsonLite.parseQuietly(body.value(), "version list");
        if (parsed == null) {
            return QueryResult.temporary("version list response could not be parsed");
        }
        List<VersionCandidate> result = new ArrayList<>();
        for (Object item : JsonLite.asArray(parsed)) {
            VersionCandidate.fromJson(JsonLite.asObject(item)).ifPresent(result::add);
        }
        return QueryResult.ok(result);
    }


    public QueryResult<ProjectRef> projectBySlug(String slug) {
        String url = apiBase + "/project/" + encode(slug);
        QueryResult<String> body = http.fetchString(url, label + " project " + slug, false, false);
        if (!body.hasValue()) {
            return QueryResult.failureOf(body);
        }
        Object parsed = JsonLite.parseQuietly(body.value(), "project");
        if (parsed == null) {
            return QueryResult.temporary("project response could not be parsed");
        }
        Map<String, Object> json = JsonLite.asObject(parsed);
        String id = JsonLite.str(json, "id", "");
        if (id.isEmpty()) {
            return QueryResult.notFound("project " + slug + " has no id");
        }
        return QueryResult.ok(new ProjectRef(id, JsonLite.str(json, "slug", slug),
                JsonLite.str(json, "title", slug), label));
    }


    public QueryResult<SearchHit> searchProjectId(String query) {
        return searchProjectId(query, true);
    }


    public QueryResult<SearchHit> searchProjectId(String query, boolean allowTopHit) {
        String facets = "[[\"project_type:mod\"],[\"versions:" + gameVersion + "\"],[\"categories:" + LOADER_NEOFORGE + "\"]]";
        String url = apiBase + "/search?limit=5&index=relevance&query=" + encode(query) + "&facets=" + encode(facets);
        QueryResult<String> body = http.fetchString(url, label + " search " + query, false, false);
        if (!body.hasValue()) {
            return QueryResult.failureOf(body);
        }
        Object parsed = JsonLite.parseQuietly(body.value(), "search");
        if (parsed == null) {
            return QueryResult.temporary("search response could not be parsed");
        }
        Map<String, Object> json = JsonLite.asObject(parsed);
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Object item : JsonLite.asArray(json.get("hits"))) {
            hits.add(JsonLite.asObject(item));
        }
        if (hits.isEmpty()) {
            return QueryResult.notFound("no project named " + query + " on " + label);
        }

        for (Map<String, Object> hit : hits) {
            String slug = JsonLite.str(hit, "slug", "");
            String projectId = JsonLite.str(hit, "project_id", "");
            if (!projectId.isEmpty()
                    && (slug.equalsIgnoreCase(query) || JsonLite.str(hit, "title", "").equalsIgnoreCase(query))) {
                return QueryResult.ok(new SearchHit(projectId, MatchKind.EXACT_NAME));
            }
        }

        String prefix = query.toLowerCase(Locale.ROOT) + "-";
        for (Map<String, Object> hit : hits) {
            String slug = JsonLite.str(hit, "slug", "").toLowerCase(Locale.ROOT);
            String projectId = JsonLite.str(hit, "project_id", "");
            if (slug.startsWith(prefix) && !projectId.isEmpty()) {
                ForbricLog.info("  search %s: matched project %s by name prefix", query, slug);
                return QueryResult.ok(new SearchHit(projectId, MatchKind.PREFIX));
            }
        }
        if (!allowTopHit) {
            return QueryResult.notFound("no project exactly named " + query + " on " + label);
        }

        for (Map<String, Object> hit : hits) {
            String projectId = JsonLite.str(hit, "project_id", "");
            if (!projectId.isEmpty()) {
                ForbricLog.warn("  search %s: no exact match, using top hit %s", query, projectId);
                return QueryResult.ok(new SearchHit(projectId, MatchKind.TOP_HIT));
            }
        }
        return QueryResult.notFound("no project named " + query + " on " + label);
    }


    @Override
    public QueryResult<TranspileTarget> forModId(String modId, String displayName, boolean strict) {
        if (modId == null || modId.isBlank()) {
            return QueryResult.notFound("no modid to look up");
        }
        QueryResult<SearchHit> projectId = searchProjectId(modId, !strict);
        if (!projectId.hasValue() && !projectId.uncertain() && displayName != null && !displayName.isBlank()
                && !displayName.equalsIgnoreCase(modId)) {
            ForbricLog.info("  %s: no project named %s, retrying with display name %s", label, modId, displayName);
            projectId = searchProjectId(displayName, !strict);
        }
        if (!projectId.hasValue()) {
            if (projectId.uncertain()) {
                return QueryResult.failureOf(projectId);
            }
            ForbricLog.info("  %s: no project named %s", label, modId);
            return QueryResult.notFound("no project named " + modId + " on " + label);
        }
        QueryResult<List<VersionCandidate>> listed = listVersions(projectId.value().projectId(), LOADER_NEOFORGE);
        if (!listed.ok()) {
            return QueryResult.failureOf(listed);
        }
        ForbricLog.info("  project %s (modid %s, %s + NeoForge): %d versions", projectId.value().projectId(), modId,
                gameVersion, listed.value().size());
        Optional<VersionCandidate> chosen = pickSameOrLatest(listed.value(), null);
        if (chosen.isEmpty()) {
            return QueryResult.notFound("project " + projectId.value().projectId() + " has no NeoForge build for "
                    + gameVersion);
        }
        ProjectRef project = new ProjectRef(projectId.value().projectId(), modId, modId, label);
        VersionCandidate version = chosen.get();
        TranspileTarget target = new TranspileTarget(project, version.versionNumber(), version.fileName(),
                version.downloadUrl(), version.sha1(), version.size(), false, null, MatchKind.MODID_LOOKUP);
        ForbricLog.info("  selected: %s (%s)", target.describe(), projectId.value().kind().label());
        return QueryResult.ok(target);
    }


    public Optional<VersionCandidate> pickSameVersion(List<VersionCandidate> candidates, String referenceVersion) {
        if (candidates == null || candidates.isEmpty() || referenceVersion == null || referenceVersion.isBlank()) {
            return Optional.empty();
        }
        for (VersionCandidate candidate : candidates) {
            if (candidate.versionNumber().equalsIgnoreCase(referenceVersion.trim())) {
                return Optional.of(candidate);
            }
        }
        for (VersionCandidate candidate : candidates) {
            if (VersionMatch.same(candidate.versionNumber(), referenceVersion)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }


    public Optional<VersionCandidate> pickSameOrLatest(List<VersionCandidate> candidates, String referenceVersion) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        Optional<VersionCandidate> same = pickSameVersion(candidates, referenceVersion);
        if (same.isPresent()) {
            return same;
        }
        return candidates.stream().max(Comparator
                .comparingInt((VersionCandidate c) -> typeRank(c.versionType()))
                .thenComparing(VersionCandidate::datePublished));
    }


    private static int typeRank(String versionType) {
        return switch (versionType == null ? "" : versionType) {
            case "release" -> 3;
            case "beta" -> 2;
            case "alpha" -> 1;
            default -> 0;
        };
    }


    private static String displayTitle(ModJarInfo jar, String projectId) {
        if (!jar.displayName().isEmpty()) {
            return jar.displayName();
        }
        return !jar.modId().isEmpty() ? jar.modId() : projectId;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
