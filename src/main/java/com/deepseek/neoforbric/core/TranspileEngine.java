package com.deepseek.neoforbric.core;

import com.deepseek.neoforbric.net.Http;
import com.deepseek.neoforbric.net.MatchKind;
import com.deepseek.neoforbric.net.Mirrors;
import com.deepseek.neoforbric.net.ModSource;
import com.deepseek.neoforbric.net.ModrinthSource;
import com.deepseek.neoforbric.net.QueryResult;
import com.deepseek.neoforbric.net.RefusedSource;
import com.deepseek.neoforbric.net.RequestLimiter;
import com.deepseek.neoforbric.net.SegmentedDownload;
import com.deepseek.neoforbric.net.TranspileTarget;
import com.deepseek.neoforbric.net.UnavailableSource;
import com.deepseek.neoforbric.net.VersionCandidate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.neoforged.fml.ModLoadingIssue;


public final class TranspileEngine {

    private static final String MODRINTH_OFFICIAL = "https://api.modrinth.com/v2";

    private static final String MODRINTH_MIRROR = "https://" + Mirrors.MCIM_HOST + "/modrinth/v2";


    public static final String WORK_DIR = ".neoforbric";


    public static final String FABRIC_API_MOD_ID = "fabric_api";


    public record Result(int jarCount, int neoForgeCount, int forgeCount, int fabricCount, int multiLoaderCount,
            int unknownCount, int blacklistedCount, int forgeReplaced, int forgeSameVersion, int forgeFailed,
            int fabricReplaced, int fabricKept, boolean connectorReady, boolean fabricApiReady,
            boolean connectorDownloaded, boolean fabricApiDownloaded, boolean connectorEnabled,
            boolean fabricApiEnabled, int serviceLayerChanged, List<String> notes, List<ModLoadingIssue> errors) {


        public boolean restartNeeded() {
            return connectorDownloaded || connectorEnabled || serviceLayerChanged > 0 || fabricReplaced > 0;
        }

        public String summary() {

            StringBuilder sb = new StringBuilder();
            sb.append("scan=%d neoForge=%d forge=%d fabric=%d multiLoader=%d other=%d"
                    .formatted(jarCount, neoForgeCount, forgeCount, fabricCount, multiLoaderCount, unknownCount));
            if (blacklistedCount > 0) {


                sb.append(" blacklisted=%d".formatted(blacklistedCount));
            }
            if (forgeCount > 0) {
                sb.append(" forgeReplaced=%d sameVersion=%d failed=%d"
                        .formatted(forgeReplaced, forgeSameVersion, forgeFailed));
            }
            if (fabricCount > 0) {
                sb.append(" fabricReplaced=%d fabricKept=%d".formatted(fabricReplaced, fabricKept));
            }
            return sb.toString();
        }
    }


    private record Prepared(ModJarInfo jar, TranspileTarget target, Path staging, double elapsedSeconds,
            String originalSha1, boolean serviceLayerChanged, List<String> providedModIds) {}


    private record InstallResult(Path originalPath, Path installedPath, String originalFileName, String originalSha1,
            List<String> providedModIds, Path backupPath, Path rollbackLeftover) {}


    private record Kept(ModJarInfo jar, String reason, String elapsed) {}


    private record Pending(ModJarInfo jar, String sha1, Future<Object> future) {}


    private record Failure(ModJarInfo jar, String reason, boolean permanent, boolean hard) {
        Failure(ModJarInfo jar, String reason, boolean permanent) {
            this(jar, reason, permanent, true);
        }
    }

    private enum Mode {
        FORGE("Forge"),
        FABRIC("Fabric");

        private final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    private final ForbricConfig config;
    private final Path gameDir;
    private final Path modsDir;
    private final Path workDir;
    private final ProgressReporter progress;

    private volatile ProgressStage activeStage;

    private final List<String> notes = new ArrayList<>();

    private final List<ModLoadingIssue> errors = new CopyOnWriteArrayList<>();

    private final List<String> reportEntries = new ArrayList<>();

    private final Http http;

    private final List<ModSource> sources;

    private final ComponentQuery componentQuery;

    private final ModrinthSource modrinthForDependencies;
    private final ExecutorService pool;
    private final String buildVersion;

    private final String gameVersion;

    private final AtomicBoolean stalled = new AtomicBoolean();

    private int serviceLayerChanged;

    private Path connectorJar;

    private final PortMap portMap;

    private final ModBlacklist blacklist;

    private final boolean crawlEnabled;

    private final boolean crawlStrict;

    private final boolean autoDependencies;

    private final int dependencyDepth;

    private final boolean removeRedundant;

    private final List<Path> installedThisRound = new ArrayList<>();

    private final Map<Path, InstallResult> applied = new LinkedHashMap<>();

    private final FileInstaller installer;

    private final InstallJournal journal;

    private int dependenciesInstalled;

    public TranspileEngine(ForbricConfig config, Path gameDir, Path modsDir, String gameVersion, String buildVersion,
            ProgressReporter progress) {
        this.config = config;
        this.gameDir = gameDir;
        this.modsDir = modsDir;
        this.workDir = modsDir.resolve(WORK_DIR);
        this.buildVersion = buildVersion;
        this.gameVersion = gameVersion;
        this.progress = progress == null ? ProgressReporter.NOOP : progress;
        this.journal = new InstallJournal(workDir);
        this.installer = new FileInstaller(modsDir, workDir, this.journal);
        this.portMap = PortMap.load(gameDir);


        this.blacklist = ModBlacklist.load(gameDir);
        this.crawlEnabled = config.getBool("transpile.crawl.enable", true);
        this.crawlStrict = config.getBool("transpile.crawl.strict", true);
        this.autoDependencies = config.getBool("dependencies.autoInstall", true);
        this.dependencyDepth = Math.max(1, config.getInt("dependencies.maxDepth", 2));
        this.removeRedundant = config.getBool("duplicates.removeRedundant", true);


        int maxActiveRequests = Math.min(64, Math.max(2, config.getInt("http.maxActiveRequests", 4)));
        int maxRequestsPerHost = Math.min(32, Math.max(1, config.getInt("http.maxRequestsPerHost", 4)));
        boolean segmentedEnabled = config.getBool("http.segmented.enable", false);
        int maxSegments = Math.min(8, Math.max(2, config.getInt("http.segmented.maxSegments", 2)));
        int thresholdMb = Math.max(1, config.getInt("http.segmented.thresholdMb", 16));
        int minSegmentMb = Math.max(1, config.getInt("http.segmented.minSegmentMb", 4));
        SegmentedDownload.Settings segmentedSettings = new SegmentedDownload.Settings(segmentedEnabled, maxSegments,
                thresholdMb * 1024L * 1024L, minSegmentMb * 1024L * 1024L);
        ForbricLog.debug("http budget: %d request(s) in total, %d per host; segmented download %s"
                + " (max %d ranges, from %d MB, min %d MB per range)", maxActiveRequests, maxRequestsPerHost,
                segmentedEnabled ? "on" : "off", maxSegments, thresholdMb, minSegmentMb);
        this.http = new Http(config.getInt("http.connectTimeoutMs", 8000),
                config.getInt("http.readTimeoutMs", 20000),
                config.getSecondsAsMillis("http.downloadTimeoutSeconds", 600), config.getInt("http.retries", 3),
                mirrorPolicy(), this::progressMessage,
                config.getSecondsAsMillis("http.stallTimeoutSeconds", 45),
                config.getSecondsAsMillis("http.totalDeadlineSeconds", 600),
                segmentedSettings, new RequestLimiter(maxActiveRequests, maxRequestsPerHost),
                config.getBool("debug.progress", false));
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, ForbricLog.TAG + "-Download-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };


        int parallelism = Math.min(128, Math.max(1, config.getInt("http.parallelism", 1)));
        if (parallelism != config.getInt("http.parallelism", 1)) {
            ForbricLog.warn("http.parallelism=%d is out of range, using %d",
                    config.getInt("http.parallelism", 1), parallelism);
        }
        this.pool = Executors.newFixedThreadPool(parallelism, factory);
        ForbricLog.info("download settings: files=%d, segmented=%s, segments=%d, globalRequests=%d, hostRequests=%d",
                parallelism, segmentedEnabled, maxSegments, maxActiveRequests, maxRequestsPerHost);
        boolean thirdPartyAllowed = config.getBool("mirror.thirdparty.modrinth.enable", false)
                && config.getBool("source.modrinth.mirror.enable", false);
        Mirrors.Endpoint officialEndpoint = Mirrors.classify("source.modrinth.official.api", Mirrors.Slot.OFFICIAL,
                config.get("source.modrinth.official.api", MODRINTH_OFFICIAL), thirdPartyAllowed);
        Mirrors.Endpoint userEndpoint = mirrorEndpoint(thirdPartyAllowed);
        this.sources = buildSourceChain(http, gameVersion, officialEndpoint, userEndpoint);
        this.componentQuery = componentQuery(officialEndpoint, userEndpoint);
        this.modrinthForDependencies = componentQuery.allowed()
                ? new ModrinthSource(http, gameVersion, componentQuery.url(), componentQuery.label())
                : null;
        if (!componentQuery.allowed()) {
            ForbricLog.warn("%s", componentQuery.refusal());
        }
    }


    private Mirrors.Policy mirrorPolicy() {
        return new Mirrors.Policy(config.getBool("mirror.enable", true),
                config.getBool("mirror.thirdparty.modrinth.enable", false),
                config.getBool("mirror.thirdparty.github.enable", false));
    }


    private Mirrors.Endpoint mirrorEndpoint(boolean thirdPartyAllowed) {
        if (!config.getBool("source.modrinth.mirror.enable", false)) {
            return null;
        }
        String url = config.get("source.modrinth.mirror.api", MODRINTH_MIRROR).trim();
        if (url.isEmpty()) {
            ForbricLog.warn("source.modrinth.mirror.enable is on but source.modrinth.mirror.api is empty, so only"
                    + " the official Modrinth API is used. Set that key to the endpoint you want, or set"
                    + " source.modrinth.mirror.enable off.");
            return null;
        }
        if (!config.getBool("mirror.thirdparty.modrinth.enable", false)) {
            Mirrors.Endpoint probe = Mirrors.classify("source.modrinth.mirror.api", Mirrors.Slot.MIRROR, url, false);
            if (probe.refused()) {
                ForbricLog.warn("source.modrinth.mirror.enable is on but mirror.thirdparty.modrinth.enable is off, so no"
                        + " third-party Modrinth endpoint is contacted. Turn the third-party switch on to accept that"
                        + " endpoint, or set source.modrinth.mirror.enable off.");
                ForbricLog.warn("%s", probe.refusal());
                return probe;
            }
            ForbricLog.warn("source.modrinth.mirror.enable is on but mirror.thirdparty.modrinth.enable is off, so no"
                    + " third-party Modrinth endpoint is contacted. Turn the third-party switch on to accept that"
                    + " endpoint, or set source.modrinth.mirror.enable off.");
            return null;
        }
        Mirrors.Endpoint endpoint = Mirrors.classify("source.modrinth.mirror.api", Mirrors.Slot.MIRROR, url,
                thirdPartyAllowed);
        if (endpoint.refused()) {
            ForbricLog.warn("%s", endpoint.refusal());
            return endpoint;
        }
        return endpoint;
    }


    private record ComponentQuery(String url, String label, String refusal) {

        boolean allowed() {
            return refusal == null;
        }
    }


    private ComponentQuery componentQuery(Mirrors.Endpoint official, Mirrors.Endpoint userEndpoint) {
        boolean officialEnabled = config.getBool("source.modrinth.official.enable", true);
        if (officialEnabled && official.allowed()) {
            return componentFrom(Mirrors.classify("source.modrinth.official.api", Mirrors.Slot.COMPONENT,
                    MODRINTH_OFFICIAL, false));
        }
        if (userEndpoint != null) {
            return componentFrom(userEndpoint);
        }
        if (officialEnabled) {
            return new ComponentQuery(null, official.label(), "the connector and Forgified Fabric API queries follow"
                    + " the same endpoint policy as the mod sources, so they are refused as well: "
                    + official.refusal());
        }
        return componentFrom(Mirrors.classify("source.modrinth.official.api", Mirrors.Slot.COMPONENT, MODRINTH_OFFICIAL,
                false));
    }


    private static ComponentQuery componentFrom(Mirrors.Endpoint endpoint) {
        return endpoint.allowed()
                ? new ComponentQuery(endpoint.url(), endpoint.label(), null)
                : new ComponentQuery(null, endpoint.label(), endpoint.refusal());
    }


    private List<ModSource> buildSourceChain(Http http, String gameVersion, Mirrors.Endpoint official,
            Mirrors.Endpoint userEndpoint) {
        List<ModSource> chain = new ArrayList<>();
        if (config.getBool("source.modrinth.official.enable", true)) {
            if (official.allowed()) {
                chain.add(new ModrinthSource(http, gameVersion, official.url(), official.label()));
            } else {
                ForbricLog.warn("%s", official.refusal());
                chain.add(new RefusedSource(official.key(), official.refusal()));
            }
        }
        if (userEndpoint != null) {
            if (userEndpoint.allowed()) {
                chain.add(new ModrinthSource(http, gameVersion, userEndpoint.url(), userEndpoint.label()));
            } else {
                chain.add(new RefusedSource(userEndpoint.key(), userEndpoint.refusal()));
            }
        }
        if (chain.isEmpty()) {
            String reason = "no download source is enabled, so nothing was queried and no mod can be replaced this"
                    + " round. Turn source.modrinth.official.enable back on, or enable a mirror endpoint, then try"
                    + " again. This is a configuration problem, not a missing build.";
            ForbricLog.warn("%s", reason);
            chain.add(new UnavailableSource(reason));
        }
        ForbricLog.debug("sources: %s", chain.stream().map(ModSource::name).toList());
        return chain;
    }


    private QueryResult<TranspileTarget> findInChain(ModJarInfo jar, boolean fallbackToLatest, String sha1) {
        QueryResult<TranspileTarget> uncertain = null;
        QueryResult<TranspileTarget> unusable = null;
        boolean anyUsable = false;
        for (int i = 0; i < sources.size(); i++) {
            ModSource source = sources.get(i);
            if (!usable(source)) {
                if (unusable == null) {
                    unusable = unusableResult(source);
                }
                ForbricLog.debug("  %s is refused or unavailable; no request is sent", source.name());
                continue;
            }
            anyUsable = true;
            try {
                QueryResult<TranspileTarget> found = source.find(jar, fallbackToLatest, sha1, crawlStrict);
                if (found.hasValue()) {
                    return found;
                }
                if (found.uncertain() && uncertain == null) {
                    uncertain = found;
                }
            } catch (RuntimeException e) {
                ForbricLog.error("source " + source.name() + " query failed (trying next)", e);
                if (uncertain == null) {
                    uncertain = QueryResult.temporary("source " + source.name() + " threw " + e);
                }
            }
            if (i + 1 < sources.size()) {
                ForbricLog.debug("  %s miss -> %s", source.name(), sources.get(i + 1).name());
            }
        }
        if (uncertain != null) {
            return uncertain;
        }
        if (!anyUsable && unusable != null) {
            return unusable;
        }
        return QueryResult.notFound("no source has a NeoForge build for " + jar.fileName());
    }


    private static boolean usable(ModSource source) {
        return !(source instanceof RefusedSource) && !(source instanceof UnavailableSource);
    }


    private static QueryResult<TranspileTarget> unusableResult(ModSource source) {
        if (source instanceof RefusedSource refused) {
            return QueryResult.restricted(refused.reason());
        }
        if (source instanceof UnavailableSource unavailable) {
            return QueryResult.configError(unavailable.reason());
        }
        return QueryResult.temporary("source " + source.name() + " is not available");
    }


    private QueryResult<TranspileTarget> latestInChain(String projectSpec) {
        List<String> candidates = new ArrayList<>();
        for (String part : projectSpec.split(",")) {
            String slug = part.trim();
            if (!slug.isEmpty()) {
                candidates.add(slug);
            }
        }
        QueryResult<TranspileTarget> uncertain = null;
        QueryResult<TranspileTarget> unusable = null;
        boolean anyUsable = false;
        for (ModSource source : sources) {
            if (!usable(source)) {
                if (unusable == null) {
                    unusable = unusableResult(source);
                }
                ForbricLog.debug("  %s is refused or unavailable; no request is sent", source.name());
                continue;
            }
            anyUsable = true;
            for (String slug : candidates) {
                try {
                    QueryResult<TranspileTarget> found = source.latest(slug, ModrinthSource.LOADER_NEOFORGE);
                    if (found.hasValue()) {
                        return found;
                    }
                    if (found.uncertain() && uncertain == null) {
                        uncertain = found;
                    }
                } catch (RuntimeException e) {
                    ForbricLog.error("source " + source.name() + " query " + slug + " failed (retrying)", e);
                    if (uncertain == null) {
                        uncertain = QueryResult.temporary("source " + source.name() + " threw " + e);
                    }
                }
            }
        }
        if (uncertain != null) {
            return uncertain;
        }
        if (!anyUsable && unusable != null) {
            return unusable;
        }
        return QueryResult.notFound("no NeoForge build for " + projectSpec);
    }


    private QueryResult<TranspileTarget> latestInChain(List<PortMap.Target> targets) {
        QueryResult<TranspileTarget> uncertain = null;
        QueryResult<TranspileTarget> unusable = null;
        boolean anyUsable = false;
        int skipped = 0;
        for (PortMap.Target target : targets) {
            if (!target.source().supported()) {
                skipped++;
                continue;
            }
            for (ModSource source : sources) {
                if (!accepts(source, target.source())) {
                    continue;
                }
                if (!usable(source)) {
                    if (unusable == null) {
                        unusable = unusableResult(source);
                    }
                    continue;
                }
                anyUsable = true;
                try {
                    QueryResult<TranspileTarget> found = source.latest(target.project(), ModrinthSource.LOADER_NEOFORGE);
                    if (found.hasValue()) {
                        return found;
                    }
                    if (found.uncertain() && uncertain == null) {
                        uncertain = found;
                    }
                } catch (RuntimeException e) {
                    ForbricLog.error("source " + source.name() + " query " + target.project() + " failed (trying next)",
                            e);
                    if (uncertain == null) {
                        uncertain = QueryResult.temporary("source " + source.name() + " threw " + e);
                    }
                }
            }
        }
        if (uncertain != null) {
            return uncertain;
        }
        if (!anyUsable && unusable != null) {
            return unusable;
        }
        if (skipped == targets.size()) {
            return QueryResult.notFound("the mapped target(s) name a source this build removed");
        }
        return QueryResult.notFound("no NeoForge build for the mapped project");
    }


    private static boolean accepts(ModSource source, PortMap.Source want) {
        return switch (want) {
            case ANY -> true;
            case MODRINTH -> source instanceof ModrinthSource || source instanceof RefusedSource
                    || source instanceof UnavailableSource;
            case CURSEFORGE -> false;
        };
    }


    private static String downloadLabel(ModJarInfo jar) {
        if (!jar.displayName().isBlank()) {
            return jar.displayName();
        }
        if (!jar.modId().isBlank()) {
            return jar.modId();
        }
        return jar.fileName();
    }


    private QueryResult<TranspileTarget> crawlByModId(String modId, String displayName) {
        QueryResult<TranspileTarget> uncertain = null;
        QueryResult<TranspileTarget> unusable = null;
        boolean anyUsable = false;
        for (ModSource source : sources) {
            if (!usable(source)) {
                if (unusable == null) {
                    unusable = unusableResult(source);
                }
                ForbricLog.debug("  %s is refused or unavailable; no request is sent", source.name());
                continue;
            }
            anyUsable = true;
            try {
                QueryResult<TranspileTarget> found = source.forModId(modId, displayName, crawlStrict);
                if (found.hasValue()) {
                    return found;
                }
                if (found.uncertain() && uncertain == null) {
                    uncertain = found;
                }
            } catch (RuntimeException e) {
                ForbricLog.error("source " + source.name() + " modid lookup " + modId + " failed (trying next)", e);
                if (uncertain == null) {
                    uncertain = QueryResult.temporary("source " + source.name() + " threw " + e);
                }
            }
        }
        if (uncertain != null) {
            return uncertain;
        }
        if (!anyUsable && unusable != null) {
            return unusable;
        }
        return QueryResult.notFound("no project named " + modId + " on any source");
    }


    private QueryResult<TranspileTarget> resolveModId(String modId) {
        Optional<PortMap.Port> mapped = portMap.find(modId);
        if (mapped.isPresent()) {
            QueryResult<TranspileTarget> viaPort = latestInChain(mapped.get().targets());
            if (viaPort.hasValue()) {
                ForbricLog.info("  port map: %s -> %s (%s)", modId, mapped.get().targets().get(0).project(),
                        viaPort.value().versionNumber());
                return QueryResult.ok(viaPort.value().withHowFound(MatchKind.PORT_MAP));
            }
        }
        if (!crawlEnabled) {
            return QueryResult.notFound("crawling is disabled (transpile.crawl.enable=false)");
        }
        return crawlByModId(modId, null);
    }


    private record DuplicateReview(int removedRedundant, List<String> keptDuplicates, int nativeConflicts,
            List<ModJarInfo> inventory) {}


    private record OriginalSnapshot(String sha1, long size, ModKind kind, List<String> ids,
            Map<String, String> versions) {}


    private DuplicateReview resolveNativeOverlaps(Set<String> blacklistedFiles,
            Map<String, DuplicateJars.Conflict> translatedDuplicates, Set<String> previouslyCovered) {
        List<ModJarInfo> active = ModJarScanner.scan(modsDir);
        DuplicateJars.Plan current = DuplicateJars.plan(active, TranspileEngine::sha1Of);
        Map<String, OriginalSnapshot> snapshots = new LinkedHashMap<>();
        for (DuplicateJars.NativeOverlap candidate : current.nativeOverlaps().values()) {
            snapshots.put(candidate.jar().fileName(), snapshotOf(candidate.jar()));
        }
        List<String> kept = new ArrayList<>();
        int removed = 0;
        Set<String> stillCovered = new LinkedHashSet<>();
        for (DuplicateJars.NativeOverlap overlap : current.nativeOverlaps().values()) {
            ModJarInfo jar = overlap.jar();
            stillCovered.add(jar.fileName());
            if (blacklistedFiles.contains(jar.fileName())) {
                continue;
            }
            List<ModJarInfo> present = new ArrayList<>();
            for (ModJarInfo provider : overlap.providers()) {
                if (!translatedDuplicates.containsKey(provider.fileName())) {
                    present.add(provider);
                }
            }
            List<ModJarInfo> providers = new ArrayList<>();
            List<ModJarInfo> protectedProviders = new ArrayList<>();
            for (ModJarInfo provider : present) {
                if (isProtectedProvider(provider, blacklistedFiles)) {
                    protectedProviders.add(provider);
                } else {
                    providers.add(provider);
                }
            }
            List<String> reasons = new ArrayList<>(2);
            if (!removeRedundant) {
                reasons.add("duplicates.removeRedundant=false keeps every duplicate");
            }
            if (reasons.isEmpty() && present.isEmpty()) {
                reasons.add("no native NeoForge file that provides " + overlap.describeIds() + " is in mods/ now");
            }
            if (reasons.isEmpty() && !overlap.uncoveredIds().isEmpty()) {
                reasons.add("it also provides " + String.join("/", overlap.uncoveredIds())
                        + ", which no native NeoForge file provides");
            }
            if (reasons.isEmpty()) {
                List<String> missing = idsWithoutProvider(overlap.sharedIds(), providers);
                if (!missing.isEmpty()) {
                    List<String> blocked = idsWithProvider(missing, protectedProviders);
                    if (!blocked.isEmpty() && blocked.size() == missing.size()) {
                        reasons.add("the native file(s) that provide " + String.join("/", blocked)
                                + " (also) provide a modid in the user blacklist ("
                                + describeFileNames(protectedProviders) + ")");
                    } else {
                        reasons.add("no native NeoForge file that is in mods/ now provides "
                                + String.join("/", missing));
                    }
                }
            }
            if (reasons.isEmpty()) {
                reasons.addAll(versionReasons(active, overlap, providers));
            }
            if (!reasons.isEmpty()) {
                kept.add(jar.fileName());
                ForbricLog.warn("  duplicate not resolved: %s [%s %s, id=%s] at %s", jar.fileName(),
                        jar.kind().display(), versionText(jar), idText(jar), jar.path());
                ForbricLog.warn("    %s", String.join("; ", reasons));
                ForbricLog.warn("    the same modid is provided by %s", describeProviders(present, overlap));
                report(jar.fileName(), "unresolved-duplicate", "kept: " + String.join("; ", reasons)
                        + "; same modid provided by " + describeProviders(present, overlap)
                        + "; file at " + jar.path(), false);
                continue;
            }
            if (removeRedundantOverlap(overlap, providers, snapshots.get(jar.fileName()))) {
                removed++;
            } else {
                kept.add(jar.fileName());
            }
        }
        for (String name : previouslyCovered) {
            if (stillCovered.contains(name) || blacklistedFiles.contains(name)) {
                continue;
            }
            Path path = modsDir.resolve(name);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            ModJarInfo jar = inspectQuietly(path);
            if (jar.kind() != ModKind.FORGE && jar.kind() != ModKind.FABRIC && jar.kind() != ModKind.QUILT) {
                continue;
            }
            List<String> orphaned = new ArrayList<>();
            for (String id : jar.allModIds()) {
                if (id == null || id.isBlank()) {
                    continue;
                }
                if (!current.nativeModIds().contains(id.toLowerCase(java.util.Locale.ROOT))) {
                    orphaned.add(id);
                }
            }
            String reason = "the native NeoForge file(s) that provided "
                    + (orphaned.isEmpty() ? idText(jar) : String.join("/", orphaned))
                    + " are no longer in mods/; nothing was moved for this file";
            kept.add(name);
            ForbricLog.warn("  duplicate not resolved: %s [%s %s, id=%s] at %s", name, jar.kind().display(),
                    versionText(jar), idText(jar), path);
            ForbricLog.warn("    %s", reason);
            report(name, "unresolved-duplicate", "kept: " + reason + "; file at " + path, false);
        }
        List<ModJarInfo> inventory = ModJarScanner.scan(modsDir);
        return new DuplicateReview(removed, List.copyOf(kept), reportNativeVersionConflicts(inventory),
                List.copyOf(inventory));
    }


    private boolean isProtectedProvider(ModJarInfo provider, Set<String> blacklistedFiles) {
        if (blacklistedFiles.contains(provider.fileName())) {
            return true;
        }
        for (String id : provider.allModIds()) {
            if (blacklist.contains(id)) {
                return true;
            }
        }
        return false;
    }


    private static List<String> idsWithoutProvider(List<String> ids, List<ModJarInfo> providers) {
        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            boolean found = false;
            for (ModJarInfo provider : providers) {
                if (provider.provides(id)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                missing.add(id);
            }
        }
        return missing;
    }


    private static List<String> idsWithProvider(List<String> ids, List<ModJarInfo> providers) {
        List<String> covered = new ArrayList<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            for (ModJarInfo provider : providers) {
                if (provider.provides(id)) {
                    covered.add(id);
                    break;
                }
            }
        }
        return covered;
    }


    private static String describeFileNames(List<ModJarInfo> jars) {
        StringBuilder sb = new StringBuilder();
        for (ModJarInfo jar : jars) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(jar.fileName());
        }
        return sb.length() == 0 ? "no file" : sb.toString();
    }


    private List<String> versionReasons(List<ModJarInfo> jars, DuplicateJars.NativeOverlap overlap,
            List<ModJarInfo> providers) {
        List<String> reasons = new ArrayList<>();
        for (ModJarInfo other : jars) {
            if (other.fileName().equals(overlap.jar().fileName())) {
                continue;
            }
            for (DependencyRequirement requirement : ModDependencies.required(other.path())) {
                String id = requirement.modId();
                if (id == null || id.isBlank() || !overlap.jar().provides(id) || !requirement.hasVersionRange()) {
                    continue;
                }
                boolean satisfied = false;
                boolean unknown = false;
                for (ModJarInfo provider : providers) {
                    if (!provider.provides(id)) {
                        continue;
                    }
                    String installed = provider.providedVersion(id);
                    if (installed == null || installed.isBlank()) {
                        unknown = true;
                        continue;
                    }
                    VersionRange.Verdict verdict = VersionRange.check(installed, requirement.versionRange());
                    if (verdict == VersionRange.Verdict.SATISFIED) {
                        satisfied = true;
                        break;
                    }
                    if (verdict == VersionRange.Verdict.UNKNOWN) {
                        unknown = true;
                    }
                }
                if (!satisfied) {
                    reasons.add(other.fileName() + " requires " + id + " " + requirement.versionRange()
                            + (unknown ? ", and the version of the native file that provides " + id
                                    + " could not be confirmed"
                                    : ", which the native file that provides " + id + " does not satisfy"));
                    if (reasons.size() >= 2) {
                        return reasons;
                    }
                }
            }
        }
        return reasons;
    }


    private String describeProviders(List<ModJarInfo> providers, DuplicateJars.NativeOverlap overlap) {
        if (providers.isEmpty()) {
            return overlap.describeProviders();
        }
        StringBuilder sb = new StringBuilder();
        for (ModJarInfo provider : providers) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(provider.fileName()).append(" [").append(provider.kind().display()).append(' ')
                    .append(providerVersionText(provider, overlap.sharedIds())).append(", id=")
                    .append(idText(provider)).append("] at ").append(provider.path());
        }
        return sb.toString();
    }


    private static String providerVersionText(ModJarInfo provider, List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (!provider.provides(id)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            String version = provider.providedVersion(id);
            sb.append(id).append('=').append(version == null || version.isBlank() ? "unknown" : version);
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }


    private static OriginalSnapshot snapshotOf(ModJarInfo jar) {
        String sha1 = sha1Of(jar);
        return new OriginalSnapshot(sha1 == null ? "" : sha1, jar.size(), jar.kind(), List.copyOf(jar.allModIds()),
                Map.copyOf(jar.providedVersions()));
    }


    private static String originalProblem(Path path, OriginalSnapshot snapshot) {
        if (snapshot == null) {
            return "the file was not captured when the redundancy check ran, so it is kept";
        }
        if (snapshot.sha1().isBlank()) {
            return "the file could not be read when the redundancy check ran, so it is kept";
        }
        try {
            if (!Files.isRegularFile(path)) {
                return "the file left mods/ before it could be moved";
            }
            ModJarInfo current = ModJarScanner.inspect(path);
            if (current.kind() != snapshot.kind()) {
                return "the file is now read as " + current.kind().display() + " instead of "
                        + snapshot.kind().display();
            }
            List<String> added = idsMissing(current.allModIds(), snapshot.ids());
            if (!added.isEmpty()) {
                return "the file now also provides " + String.join("/", added)
                        + ", which the redundancy check did not cover";
            }
            List<String> gone = idsMissing(snapshot.ids(), current.allModIds());
            if (!gone.isEmpty()) {
                return "the file no longer provides " + String.join("/", gone);
            }
            for (String id : snapshot.ids()) {
                String before = versionOf(snapshot.versions(), id);
                String now = current.providedVersion(id);
                if (before == null ? now != null : !before.equals(now)) {
                    return "the version reported for " + id + " changed (was "
                            + (before == null || before.isBlank() ? "unknown" : before) + ", now "
                            + (now == null || now.isBlank() ? "unknown" : now) + ")";
                }
            }
            if (Files.size(path) != snapshot.size() || !snapshot.sha1().equals(Hashes.sha1(path))) {
                return "the file changed after the redundancy check";
            }
            return null;
        } catch (IOException e) {
            return "the file could not be checked again (" + e + ")";
        }
    }


    private static List<String> idsMissing(List<String> wanted, List<String> have) {
        List<String> result = new ArrayList<>();
        for (String id : wanted) {
            if (id == null || id.isBlank()) {
                continue;
            }
            boolean found = false;
            for (String other : have) {
                if (other != null && other.equalsIgnoreCase(id)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                result.add(id);
            }
        }
        return result;
    }


    private static String versionOf(Map<String, String> versions, String id) {
        for (Map.Entry<String, String> entry : versions.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(id)) {
                return entry.getValue();
            }
        }
        return null;
    }


    private String removalProblem(ModJarInfo jar, DuplicateJars.NativeOverlap overlap, List<ModJarInfo> providers,
            OriginalSnapshot snapshot) {
        String original = originalProblem(jar.path(), snapshot);
        if (original != null) {
            return original;
        }
        return providerProblem(overlap, providers);
    }


    private String providerProblem(DuplicateJars.NativeOverlap overlap, List<ModJarInfo> providers) {
        try {
            for (ModJarInfo provider : providers) {
                if (!Files.isRegularFile(provider.path())) {
                    return provider.fileName() + " is no longer in mods/";
                }
                ModJarInfo fresh = ModJarScanner.inspect(provider.path());
                if (fresh.kind() != ModKind.NEOFORGE) {
                    return provider.fileName() + " is not a readable NeoForge file any more";
                }
                for (String id : overlap.sharedIds()) {
                    if (!provider.provides(id)) {
                        continue;
                    }
                    if (!fresh.provides(id)) {
                        return provider.fileName() + " no longer provides " + id;
                    }
                    String before = provider.providedVersion(id);
                    String now = fresh.providedVersion(id);
                    if (before == null ? now != null : !before.equals(now)) {
                        return provider.fileName() + " now reports the version of " + id + " as "
                                + (now == null || now.isBlank() ? "unknown" : now);
                    }
                }
            }
            return null;
        } catch (IOException e) {
            return "the native files could not be checked again (" + e + ")";
        }
    }


    private boolean removeRedundantOverlap(DuplicateJars.NativeOverlap overlap, List<ModJarInfo> providers,
            OriginalSnapshot snapshot) {
        ModJarInfo jar = overlap.jar();
        Path loser = jar.path();
        if (!Files.isRegularFile(loser)) {
            ForbricLog.info("  %s is no longer in mods/, nothing to remove", jar.fileName());
            return true;
        }
        Path backup = workDir.resolve("backup").resolve(jar.fileName());
        InstallJournal.Entry transaction;
        try {
            Files.createDirectories(backup.getParent());
            transaction = journal.open(InstallJournal.Kind.REMOVE, loser, null, null, backup, null,
                    snapshot == null ? "" : snapshot.sha1(), "");
        } catch (IOException e) {
            ForbricLog.error("  cannot remove the redundant duplicate " + jar.fileName(), e);
            notes.add("redundant duplicate kept: " + jar.fileName() + " (" + e + ")");
            report(jar.fileName(), "unresolved-duplicate", "kept: the removal could not be recorded (" + e + ")", false);
            return false;
        }
        String problem = removalProblem(jar, overlap, providers, snapshot);
        if (problem != null) {
            journal.close(transaction);
            ForbricLog.warn("  duplicate not resolved: %s: %s", jar.fileName(), problem);
            notes.add("redundant duplicate kept: " + jar.fileName() + " (" + problem + ")");
            report(jar.fileName(), "unresolved-duplicate", "kept: " + problem, false);
            return false;
        }
        try {
            Files.move(loser, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            if (Files.isRegularFile(loser)) {
                journal.close(transaction);
            } else {
                ForbricLog.warn("  %s left mods/ but the removal could not be confirmed: %s", jar.fileName(), e);
            }
            ForbricLog.error("  cannot remove the redundant duplicate " + jar.fileName(), e);
            notes.add("redundant duplicate kept: " + jar.fileName() + " (" + e + ")");
            report(jar.fileName(), "unresolved-duplicate", "kept: the file could not be moved to the backup ("
                    + e + ")", false);
            return false;
        }
        boolean gone = !Files.exists(loser);
        boolean stored = Files.isRegularFile(backup);
        if (!gone || !stored) {
            ForbricLog.error("  the redundant duplicate %s was moved but the result could not be confirmed "
                    + "(in mods/=%s, backup exists=%s)", jar.fileName(), !gone, stored);
            notes.add("redundant duplicate state unclear: " + jar.fileName() + " (in mods/=" + !gone
                    + ", backup=" + backup + ")");
            report(jar.fileName(), "unresolved-duplicate", "state unclear after moving: in mods/=" + !gone
                    + ", backup=" + backup, false);
            return false;
        }
        journal.close(transaction);
        ForbricLog.info("  removed the redundant duplicate %s [%s %s, id=%s] at %s", jar.fileName(),
                jar.kind().display(), versionText(jar), idText(jar), loser);
        ForbricLog.info("    every modid it provides (%s) is provided by %s", overlap.describeIds(),
                describeProviders(providers, overlap));
        ForbricLog.info("    the file is kept at %s", backup);
        report(jar.fileName(), "removed-redundant", "every modid it provides (" + overlap.describeIds()
                + ") is provided by " + describeProviders(providers, overlap) + "; moved to " + backup, false);
        return true;
    }


    private int reportNativeVersionConflicts(List<ModJarInfo> active) {
        Map<String, List<ModJarInfo>> byId = new LinkedHashMap<>();
        for (ModJarInfo jar : active) {
            if (jar.kind() != ModKind.NEOFORGE) {
                continue;
            }
            for (String id : jar.allModIds()) {
                if (id == null || id.isBlank()) {
                    continue;
                }
                List<ModJarInfo> members = byId.computeIfAbsent(id.toLowerCase(java.util.Locale.ROOT),
                        key -> new ArrayList<>());
                if (!containsFile(members, jar.fileName())) {
                    members.add(jar);
                }
            }
        }
        Map<String, ModJarInfo> conflicted = new LinkedHashMap<>();
        Map<String, List<String>> idsOf = new LinkedHashMap<>();
        Map<String, List<String>> othersOf = new LinkedHashMap<>();
        Map<String, List<String>> unknownOf = new LinkedHashMap<>();
        for (Map.Entry<String, List<ModJarInfo>> entry : byId.entrySet()) {
            List<ModJarInfo> members = entry.getValue();
            if (members.size() < 2) {
                continue;
            }
            Map<String, String> hashOf = new LinkedHashMap<>();
            Map<String, List<ModJarInfo>> byHash = new LinkedHashMap<>();
            List<ModJarInfo> unreadable = new ArrayList<>();
            for (ModJarInfo member : members) {
                String hash = sha1Of(member);
                hashOf.put(member.fileName(), hash);
                if (hash == null || hash.isBlank()) {
                    unreadable.add(member);
                    continue;
                }
                byHash.computeIfAbsent(hash, key -> new ArrayList<>()).add(member);
            }
            if (!unreadable.isEmpty()) {
                for (ModJarInfo member : unreadable) {
                    List<String> ids = unknownOf.computeIfAbsent(member.fileName(), key -> new ArrayList<>());
                    if (!ids.contains(entry.getKey())) {
                        ids.add(entry.getKey());
                    }
                }
                continue;
            }
            if (byHash.size() < 2) {
                continue;
            }
            for (ModJarInfo member : members) {
                String ownHash = hashOf.get(member.fileName());
                for (ModJarInfo other : members) {
                    if (other.fileName().equals(member.fileName())
                            || (ownHash != null && ownHash.equals(hashOf.get(other.fileName())))) {
                        continue;
                    }
                    conflicted.putIfAbsent(member.fileName(), member);
                    List<String> ids = idsOf.computeIfAbsent(member.fileName(), key -> new ArrayList<>());
                    if (!ids.contains(entry.getKey())) {
                        ids.add(entry.getKey());
                    }
                    List<String> others = othersOf.computeIfAbsent(member.fileName(), key -> new ArrayList<>());
                    String text = other.fileName() + " [NeoForge " + versionText(other) + ", id=" + idText(other)
                            + "] at " + other.path();
                    if (!others.contains(text)) {
                        others.add(text);
                    }
                }
            }
        }
        for (Map.Entry<String, ModJarInfo> entry : conflicted.entrySet()) {
            ModJarInfo member = entry.getValue();
            List<String> ids = idsOf.get(member.fileName());
            List<String> others = othersOf.get(member.fileName());
            ForbricLog.warn("  duplicate modid %s on two different NeoForge files: %s [NeoForge %s, id=%s] at %s",
                    String.join("/", ids), member.fileName(), versionText(member), idText(member), member.path());
            ForbricLog.warn("    the other file is %s", String.join("; ", others));
            notes.add("two NeoForge files provide " + String.join("/", ids) + ": " + member.fileName() + " and "
                    + String.join(", ", others) + " (both kept)");
            report(member.fileName(), "native-version-conflict", "another NeoForge file provides "
                    + String.join("/", ids) + ": " + String.join("; ", others)
                    + "; both files were kept, choose one by hand", false);
        }
        for (Map.Entry<String, List<String>> entry : unknownOf.entrySet()) {
            List<String> ids = entry.getValue();
            ForbricLog.warn("  cannot read %s, so it is not known whether it differs from the other NeoForge "
                    + "file that provides %s", entry.getKey(), String.join("/", ids));
            report(entry.getKey(), "native-version-unknown", "the file could not be read, so it is not known whether "
                    + "it differs from the other NeoForge file that provides " + String.join("/", ids)
                    + "; both files were kept", false);
        }
        return conflicted.size();
    }

    private static String versionText(ModJarInfo jar) {
        return jar.version() == null || jar.version().isBlank() ? "unknown version" : jar.version();
    }


    private static String idText(ModJarInfo jar) {
        return jar.allModIds().isEmpty() ? "no modid" : String.join("/", jar.allModIds());
    }


    private static ModJarInfo jarNamed(List<ModJarInfo> jars, String fileName) {
        for (ModJarInfo jar : jars) {
            if (jar.fileName().equals(fileName)) {
                return jar;
            }
        }
        return null;
    }


    private static boolean containsFile(List<ModJarInfo> jars, String fileName) {
        for (ModJarInfo jar : jars) {
            if (jar.fileName().equals(fileName)) {
                return true;
            }
        }
        return false;
    }


    private void resolveDuplicates(Map<String, DuplicateJars.Conflict> duplicates,
            Map<String, OriginalSnapshot> snapshots) {
        for (Map.Entry<String, DuplicateJars.Conflict> entry : duplicates.entrySet()) {
            DuplicateJars.Conflict conflict = entry.getValue();
            ModJarInfo winner = conflict.winner();
            String loserName = entry.getKey();
            InstallResult install = applied.get(winner.path());
            Path winnerPath = install == null ? winner.path() : install.installedPath();


            boolean winnerReady = winner.kind() == ModKind.NEOFORGE || install != null;
            if (!winnerReady) {
                ForbricLog.warn("  duplicate kept: %s (its twin %s was not replaced this round)",
                        loserName, winner.fileName());
                notes.add("duplicate kept: " + loserName + " (" + winner.fileName() + " was not replaced)");
                report(loserName, "kept-duplicate", "twin " + winner.fileName() + " was not replaced", false);
                continue;
            }


            if (!winnerProvides(winnerPath, conflict)) {
                ForbricLog.warn("  duplicate kept: %s (%s no longer provides modid %s)",
                        loserName, winnerPath.getFileName(), conflict.describeIds());
                notes.add("duplicate kept: " + loserName + " (" + winnerPath.getFileName()
                        + " no longer provides modid " + conflict.describeIds() + ")");
                report(loserName, "kept-duplicate", "twin " + winnerPath.getFileName() + " changed modid", false);
                continue;
            }
            Path loser = modsDir.resolve(loserName);
            if (!Files.isRegularFile(loser)) {
                continue;
            }
            if (!conflict.uncoveredModIds().isEmpty()) {
                ForbricLog.warn("  %s also provides %s, which %s does not provide; that modid has no file left",
                        loserName, String.join("/", conflict.uncoveredModIds()), winnerPath.getFileName());
                notes.add("duplicate removed with an uncovered modid: " + loserName + " also provided "
                        + String.join("/", conflict.uncoveredModIds()));
            }
            OriginalSnapshot snapshot = snapshots.get(loserName);
            String problem = originalProblem(loser, snapshot);
            if (problem != null) {
                ForbricLog.warn("  duplicate kept: %s (%s)", loserName, problem);
                notes.add("duplicate kept: " + loserName + " (" + problem + ")");
                report(loserName, "unresolved-duplicate", "kept: " + problem, false);
                continue;
            }
            Path backup = workDir.resolve("backup").resolve(loserName);
            InstallJournal.Entry transaction;
            try {
                Files.createDirectories(backup.getParent());
                transaction = journal.open(InstallJournal.Kind.REMOVE, loser, null, null, backup, null,
                        snapshot.sha1(), "");
            } catch (IOException e) {
                ForbricLog.error("  cannot remove duplicate " + loserName, e);
                notes.add("duplicate removal failed: " + loserName + " (" + e + ")");
                continue;
            }
            try {
                Files.move(loser, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                journal.close(transaction);
                ForbricLog.info("  removed duplicate %s (same modid %s as %s, backed up)", loserName,
                        conflict.describeIds(), winnerPath.getFileName());
                report(loserName, "removed-duplicate", "same modid " + conflict.describeIds() + " as "
                        + winnerPath.getFileName(), false);
            } catch (IOException e) {
                if (Files.isRegularFile(loser)) {
                    journal.close(transaction);
                } else {
                    ForbricLog.warn("  %s is no longer in mods/, its installation record is kept so the next "
                            + "start can resolve it", loserName);
                    notes.add("duplicate removal left an unfinished record: " + loserName);
                }
                ForbricLog.error("  cannot remove duplicate " + loserName, e);
                notes.add("duplicate removal failed: " + loserName + " (" + e + ")");
            }
        }
    }


    private static boolean winnerProvides(Path winnerPath, DuplicateJars.Conflict conflict) {
        List<String> required = conflict.requiredIds();
        if (required.isEmpty()) {
            return true;
        }
        try {
            ModJarInfo current = ModJarScanner.inspect(winnerPath);
            if (conflict.satisfiedBy(current.allModIds())) {
                return true;
            }
            ForbricLog.warn("  %s reports %s, not %s", winnerPath.getFileName(),
                    current.allModIds().isEmpty() ? "no modid" : String.join("/", current.allModIds()),
                    String.join("/", required));
            return false;
        } catch (IOException e) {
            ForbricLog.warn("  cannot re-read %s to confirm its modid: %s", winnerPath.getFileName(), e.toString());
            return false;
        }
    }


    private record DepTask(Path jar, int depth) {}


    private record DepInstall(Path jar, boolean installed, String version, String refusal) {}


    private record DependencyConstraint(String modId, DependencyRequirement requirement, String requiredBy) {

        String describe(String version) {
            return requiredBy + " requires " + requirement.versionRange() + " (side " + requirement.side()
                    + ") but the candidate version is "
                    + (version == null || version.isBlank() ? "unknown" : version);
        }


        String outcome(VersionRange.Verdict verdict, String version) {
            String state = switch (verdict) {
                case SATISFIED -> "satisfied";
                case NOT_SATISFIED -> "not satisfied";
                case UNKNOWN -> "unverified";
            };
            return requiredBy + " needs " + requirement.versionRange() + " -> " + state
                    + " (candidate " + (version == null || version.isBlank() ? "unknown" : version) + ")";
        }
    }


    private record DependencyVerdict(Boolean satisfied, String detail, List<String> outcomes) {

        static DependencyVerdict of(VersionRange.Verdict verdict, DependencyConstraint constraint, String version) {
            return new DependencyVerdict(verdict == VersionRange.Verdict.SATISFIED ? Boolean.TRUE : null,
                    constraint.describe(version), List.of(constraint.outcome(verdict, version)));
        }

        String label() {
            if (Boolean.TRUE.equals(satisfied)) {
                return "version range verified";
            }
            return satisfied == null ? "version range unverified" : "version range not satisfied";
        }

        String summary() {
            return outcomes.isEmpty() ? "" : " (" + label() + ": " + String.join("; ", outcomes) + ")";
        }
    }


    private void installMissingDependencies() {
        if (installedThisRound.isEmpty()) {
            return;
        }
        Map<String, ModJarInfo> present = new HashMap<>();
        for (ModJarInfo jar : ModJarScanner.scan(modsDir)) {
            for (String id : jar.allModIds()) {
                present.putIfAbsent(id.toLowerCase(Locale.ROOT), jar);
            }
        }
        Deque<DepTask> queue = new ArrayDeque<>();
        Set<Path> seen = new LinkedHashSet<>();
        List<Path> installedOrder = new ArrayList<>(installedThisRound);
        installedOrder.sort(java.util.Comparator.comparing(path -> path.getFileName().toString()));
        for (Path jar : installedOrder) {
            if (seen.add(jar)) {
                queue.add(new DepTask(jar, 0));
            }
        }
        Set<String> attempted = new HashSet<>();
        Map<String, List<DependencyConstraint>> constraints = new HashMap<>();
        Map<String, String> resolvedVersions = new HashMap<>();
        Map<String, String> refusalReasons = new HashMap<>();
        ProgressStage stage = beginStage("dependencies", "Installing dependencies", queue.size());
        while (!queue.isEmpty()) {
            List<DepTask> wave = new ArrayList<>();
            while (!queue.isEmpty()) {
                wave.add(queue.poll());
            }
            for (DepTask task : wave) {
                String parent = task.jar().getFileName().toString();
                if (task.depth() >= dependencyDepth || !Files.isRegularFile(task.jar())) {
                    continue;
                }
                for (DependencyRequirement requirement : ModDependencies.required(task.jar())) {
                    String key = requirement.modId().toLowerCase(Locale.ROOT);
                    List<DependencyConstraint> known = constraints.get(key);
                    if (known == null) {
                        known = new ArrayList<>();
                        constraints.put(key, known);
                    }
                    DependencyConstraint constraint = new DependencyConstraint(requirement.modId(), requirement,
                            parent);
                    if (!known.contains(constraint)) {
                        known.add(constraint);
                    }
                }
            }
            for (DepTask task : wave) {
            String requiredBy = task.jar().getFileName().toString();
            if (task.depth() < dependencyDepth && Files.isRegularFile(task.jar())) {
                for (DependencyRequirement requirement : ModDependencies.required(task.jar())) {
                    String depId = requirement.modId();
                    String key = depId.toLowerCase(Locale.ROOT);
                    List<DependencyConstraint> known = constraints.get(key);
                    if (known == null) {
                        known = new ArrayList<>();
                        constraints.put(key, known);
                    }
                    ModJarInfo already = present.get(key);
                    if (already != null) {
                        checkDependencyVersion(already.fileName(), already.version(), requirement, requiredBy);
                        continue;
                    }
                    String refused = refusalReasons.get(key);
                    if (refused != null) {
                        reportRefusedConstraint(requirement, requiredBy, resolvedVersions.get(key), refused);
                        continue;
                    }
                    if (!attempted.add(key)) {
                        String knownVersion = resolvedVersions.get(key);
                        if (knownVersion != null) {
                            checkDependencyVersion(depId, knownVersion, requirement, requiredBy);
                        }
                        continue;
                    }
                    if (blacklist.contains(depId)) {


                        ForbricLog.warn("  dependency %s is blacklisted, NOT installing (required by %s)",
                                depId, requiredBy);
                        notes.add("blacklisted dependency not installed: " + depId
                                + " (required by " + requiredBy + ")");
                        report(requiredBy, "blacklisted-dependency", depId, false);
                        continue;
                    }
                    progress.message(screen("Dependency " + depId));
                    QueryResult<TranspileTarget> target = resolveModId(depId);
                    if (!target.hasValue()) {


                        ForbricLog.warn("  missing dependency %s (required by %s): %s", depId, requiredBy,
                                target.describe());
                        notes.add("missing dependency: " + depId + " (required by " + requiredBy + "): "
                                + target.describe());
                        report(requiredBy, target.uncertain() ? "dependency-unknown" : "missing-dependency", depId,
                                false);
                        continue;
                    }
                    DepInstall installed = installDependency(depId, target.value(), requiredBy, known);
                    if (installed == null) {
                        continue;
                    }
                    if (installed.version() != null) {
                        resolvedVersions.put(key, installed.version());
                    }
                    if (installed.refusal() != null) {
                        refusalReasons.put(key, installed.refusal());
                    }
                    if (installed.installed()) {
                        dependenciesInstalled++;
                    }
                    if (installed.jar() == null) {
                        continue;
                    }
                    try {
                        present.put(key, ModJarScanner.inspect(installed.jar()));
                    } catch (IOException e) {
                        ForbricLog.warn("  cannot re-read the new dependency %s: %s", depId, e.toString());
                    }
                    queue.add(new DepTask(installed.jar(), task.depth() + 1));
                    stage.expectMore(1);
                }
            }
            stage.done(task.jar().toString() + ":" + task.depth(), screen("Dependencies checked for " + requiredBy));
            }
        }
        endStage(stage);
    }


    private DepInstall installDependency(String modId, TranspileTarget target, String requiredBy,
            List<DependencyConstraint> constraints) {
        Path staging = workDir.resolve("downloads").resolve("dep-" + modId + ".jar.new");
        try {
            Files.createDirectories(staging.getParent());
        } catch (IOException e) {
            ForbricLog.error("dependency staging dir failed: " + staging.getParent(), e);
            notes.add("dependency staging failed: " + modId);
            return null;
        }
        ForbricLog.info("  installing dependency %s: %s (%s)", modId, target.fileName(), target.project().source());
        if (!http.download(target.downloadUrl(), staging, modId, target.sha1(),
                target.versionNumber(), target.size(), downloadScope("dep-" + modId, modId))) {
            notes.add("dependency download failed: " + modId + " (required by " + requiredBy + ")");
            return null;
        }
        String expected = expectedDependencyModId(modId);
        String candidateVersion = null;
        DependencyVerdict verdict = null;
        try {
            ModJarInfo check = ModJarScanner.inspect(staging);
            candidateVersion = check.version();
            if (check.kind() != ModKind.NEOFORGE) {


                ForbricLog.warn("  dependency %s: downloaded file is %s, not a NeoForge mod -> aborting",
                        modId, check.kind().display());
                Files.deleteIfExists(staging);
                notes.add("dependency is not a NeoForge mod: " + modId);
                return null;
            }
            if (expected != null && !check.provides(expected)) {
                ForbricLog.warn("  dependency %s: project provides %s -> aborting", modId,
                        check.allModIds().isEmpty() ? "no modid" : String.join("/", check.allModIds()));
                Files.deleteIfExists(staging);
                notes.add("dependency modid mismatch: wanted " + expected + ", got "
                        + (check.allModIds().isEmpty() ? "none" : String.join("/", check.allModIds())));
                return null;
            }
            verdict = checkConstraints(constraints, candidateVersion);
            if (Boolean.FALSE.equals(verdict.satisfied())) {
                ForbricLog.warn("  dependency %s: %s -> not installing it (%s)", modId, verdict.detail(),
                        String.join("; ", verdict.outcomes()));
                Files.deleteIfExists(staging);
                notes.add("dependency version rejected: " + modId + " (" + verdict.detail() + "; "
                        + String.join("; ", verdict.outcomes()) + ")");
                report(target.fileName(), "dependency-version-mismatch",
                        verdict.detail() + verdict.summary(), false);
                return new DepInstall(null, false, candidateVersion, verdict.detail());
            }
            if (verdict.satisfied() == null) {
                ForbricLog.warn("  dependency %s: %s -> installing it unverified (%s)", modId, verdict.detail(),
                        String.join("; ", verdict.outcomes()));
                notes.add("dependency version unverified: " + modId + " (" + verdict.detail() + "; "
                        + String.join("; ", verdict.outcomes()) + ")");
            }
        } catch (IOException e) {
            ForbricLog.error("  dependency verify failed: " + modId, e);
            notes.add("dependency verify failed: " + modId + " (" + e + ")");
            return null;
        }

        FileInstaller.Outcome outcome = installer.installNew(staging, target.fileName());
        if (!outcome.ok()) {
            ForbricLog.error("  dependency %s was not installed: %s", modId, outcome.detail());
            notes.add("dependency not installed: " + modId + " (" + outcome.detail() + ")");
            report(target.fileName(), "dependency-conflict", outcome.detail(), false);
            try {
                Files.deleteIfExists(staging);
            } catch (IOException e) {
                ForbricLog.debug("  staging cleanup failed: %s", e.toString());
            }
            return null;
        }
        Path dest = outcome.installedPath();
        if (outcome.reused()) {
            ForbricLog.info("  dependency %s already present as %s with the same content, kept it", modId,
                    dest.getFileName());
            return new DepInstall(dest, false, candidateVersion, null);
        }
        if (ServiceEntries.entersServiceLayer(dest)) {
            serviceLayerChanged++;
        }
        ForbricLog.info("  installed dependency %s: %s (%s, %s)", modId, target.fileName(),
                target.versionNumber(), verdict == null ? "version range not checked" : verdict.label());
        report(target.fileName(), "dependency", modId + " required by " + requiredBy
                + (verdict == null ? "" : constraintSummary(verdict)), false);
        RunStats.dependency(modId, target.versionNumber(), target.project().source(), requiredBy);
        return new DepInstall(dest, true, candidateVersion, null);
    }


    private void checkDependencyVersion(String fileName, String version, DependencyRequirement requirement,
            String requiredBy) {
        if (!requirement.hasVersionRange()) {
            return;
        }
        VersionRange.Verdict verdict = VersionRange.check(version, requirement.versionRange());
        if (verdict == VersionRange.Verdict.SATISFIED) {
            ForbricLog.debug("  dependency %s %s satisfies %s required by %s", requirement.modId(),
                    orUnknown(version), requirement.versionRange(), requiredBy);
            return;
        }
        if (verdict == VersionRange.Verdict.UNKNOWN) {
            ForbricLog.debug("  cannot compare dependency %s %s with %s required by %s", requirement.modId(),
                    orUnknown(version), requirement.versionRange(), requiredBy);
            return;
        }
        ForbricLog.warn("  dependency %s is installed as %s but %s requires %s, not replacing it automatically",
                requirement.modId(), orUnknown(version), requiredBy, requirement.versionRange());
        notes.add("dependency version mismatch: " + requirement.modId() + " is " + orUnknown(version)
                + ", " + requiredBy + " requires " + requirement.versionRange() + " (left as it is)");
        report(fileName, "dependency-version-mismatch",
                requirement.versionRange() + " required by " + requiredBy, false);
    }


    private void reportRefusedConstraint(DependencyRequirement requirement, String requiredBy, String version,
            String refusal) {
        if (!requirement.hasVersionRange()) {
            return;
        }
        ForbricLog.warn("  dependency %s was not installed for %s: %s", requirement.modId(), requiredBy, refusal);
        notes.add("dependency version mismatch: " + requirement.modId() + " is " + orUnknown(version)
                + ", " + requiredBy + " requires " + requirement.versionRange() + " (not installed: " + refusal
                + ")");
        report(requirement.modId(), "dependency-version-mismatch",
                requirement.versionRange() + " required by " + requiredBy + ", candidate " + orUnknown(version)
                        + " was refused", false);
    }


    private static String constraintSummary(DependencyVerdict verdict) {
        return verdict.summary();
    }


    private static DependencyVerdict checkConstraints(List<DependencyConstraint> constraints, String version) {
        String unsatisfied = null;
        String unknown = null;
        List<String> outcomes = new ArrayList<>();
        for (DependencyConstraint constraint : constraints) {
            if (!constraint.requirement().hasVersionRange()) {
                continue;
            }
            VersionRange.Verdict verdict = VersionRange.check(version,
                    constraint.requirement().versionRange());
            outcomes.add(constraint.outcome(verdict, version));
            if (verdict == VersionRange.Verdict.NOT_SATISFIED) {
                if (unsatisfied == null) {
                    unsatisfied = constraint.describe(version);
                }
            } else if (verdict == VersionRange.Verdict.UNKNOWN && unknown == null) {
                unknown = constraint.describe(version);
            }
        }
        if (unsatisfied != null) {
            return new DependencyVerdict(Boolean.FALSE, unsatisfied, List.copyOf(outcomes));
        }
        if (unknown != null) {
            return new DependencyVerdict(null, unknown, List.copyOf(outcomes));
        }
        return new DependencyVerdict(Boolean.TRUE, "", List.copyOf(outcomes));
    }


    private String expectedDependencyModId(String modId) {
        Optional<PortMap.Port> mapped = portMap.find(modId);
        if (mapped.isPresent()) {
            return mapped.get().expectedModId();
        }
        return modId;
    }


    public Result run() {
        try {
            return runRound();
        } finally {
            shutdownPool();
        }
    }


    private void shutdownPool() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    private Result abortRound(String what) {
        return abortRound("aborted: unreadable " + what + " file",
                ForbricLog.TAG + " could not read its " + what + " file, so no mod was touched this round. "
                        + "Fix or delete that file and start the game again.",
                ForbricLog.TAG + " could not read its " + what + " file; no mod was touched.");
    }


    private Result abortRound(String note, String message, String gameError) {
        ForbricLog.error("%s", message);
        notes.add(note);
        addError(null, message);
        Result result = new Result(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false, false, false, false, false, 0,
                List.copyOf(notes), List.copyOf(errors));
        writeReport(result);
        RunStats.writeSummary();
        ForbricLog.gameError("%s", gameError);
        return result;
    }


    private String decisionEnv() {
        StringBuilder sb = new StringBuilder();
        sb.append("decisions-v5\n");
        sb.append("gameVersion=").append(gameVersion).append('\n');
        sb.append("enable=").append(config.getBool("enable", true)).append('\n');
        sb.append("forge.enable=").append(config.getBool("forge.enable", true)).append('\n');
        sb.append("fabric.enable=").append(config.getBool("fabric.enable", true)).append('\n');
        sb.append("mirror.enable=").append(config.getBool("mirror.enable", true)).append('\n');
        sb.append("mirror.thirdparty.modrinth.enable=")
                .append(config.getBool("mirror.thirdparty.modrinth.enable", false)).append('\n');
        sb.append("mirror.thirdparty.github.enable=")
                .append(config.getBool("mirror.thirdparty.github.enable", false)).append('\n');
        sb.append("source.modrinth.mirror.enable=").append(config.getBool("source.modrinth.mirror.enable", false))
                .append('\n');
        sb.append("source.modrinth.mirror.api=").append(Mirrors.identityOf(config.get("source.modrinth.mirror.api",
                MODRINTH_MIRROR)))
                .append('\n');
        sb.append("source.modrinth.official.enable=").append(config.getBool("source.modrinth.official.enable", true))
                .append('\n');
        sb.append("source.modrinth.official.api=")
                .append(Mirrors.identityOf(config.get("source.modrinth.official.api", MODRINTH_OFFICIAL)))
                .append('\n');
        sb.append("fabric.connector.projects=")
                .append(config.get("fabric.connector.projects", "connector,sinytra-connector")).append('\n');
        sb.append("fabric.api.projects=").append(config.get("fabric.api.projects", "forgified-fabric-api"))
                .append('\n');
        sb.append("transpile.crawl.enable=").append(crawlEnabled).append('\n');
        sb.append("transpile.crawl.strict=").append(crawlStrict).append('\n');
        sb.append("dependencies.autoInstall=").append(autoDependencies).append('\n');
        sb.append("dependencies.maxDepth=").append(dependencyDepth).append('\n');
        sb.append("duplicates.removeRedundant=").append(removeRedundant).append('\n');
        sb.append("cache.notFoundDays=").append(config.getInt("cache.notFoundDays", 7)).append('\n');
        sb.append("portMap=").append(portMap.origin()).append(':').append(portMap.size()).append('\n');
        List<String> entries = new ArrayList<>();
        for (PortMap.Port port : portMap.all()) {
            entries.add(port.modId() + '=' + port.targets() + '@' + port.expectedModId());
        }
        entries.sort(String.CASE_INSENSITIVE_ORDER);
        for (String entry : entries) {
            sb.append("port=").append(entry).append('\n');
        }
        return Hashes.sha256(sb.toString()).substring(0, 16);
    }


    private long negativeTtlMillis() {
        return Math.max(1, config.getInt("cache.notFoundDays", 7)) * 86_400_000L;
    }


    private Result runRound() {
        long runStart = System.nanoTime();
        RunStats.begin();
        if (config.readFailed()) {
            return abortRound("config");
        }
        if (blacklist.unreadable()) {
            return abortRound("blacklist");
        }
        if (!portMap.isEmpty()) {
            ForbricLog.debug("port map: %d entries (%s)", portMap.size(), portMap.origin());
            List<PortMap.Unsupported> unsupported = portMap.unsupportedTargets();
            if (!unsupported.isEmpty()) {
                List<String> listed = new ArrayList<>();
                for (PortMap.Unsupported entry : unsupported) {
                    listed.add(entry.modId() + " -> " + entry.project());
                }
                ForbricLog.warn("port map targets that name a source this build removed, they are skipped and no"
                        + " request is sent for them: %s. CurseForge access was removed in 0.0.10 and a numeric"
                        + " CurseForge project id is never read as a Modrinth id. Replace them with Modrinth"
                        + " project ids or slugs, or delete those targets. Your ports.properties was not"
                        + " changed.", listed);
                notes.add("port map has " + unsupported.size() + " target(s) that name a removed source");
            }
        } else if (portMap.unreadable()) {
            ForbricLog.warn("port map unreadable, the built-in table is used");
            notes.add("port map unreadable, the built-in table was used");
        }
        if (!blacklist.isEmpty()) {
            ForbricLog.debug("blacklist: %d entries (%s)", blacklist.size(), blacklist.origin());
        }
        InstallJournal.Recovery recovery = journal.recover();
        for (String note : recovery.notes()) {
            ForbricLog.info("  %s", note);
            notes.add(note);
        }
        if (recovery.blocked()) {
            for (String problem : recovery.problems()) {
                ForbricLog.error("  %s", problem);
            }
            return abortRound("aborted: an unfinished file replacement could not be recovered",
                    ForbricLog.TAG + " found an unfinished file replacement in mods/ and could not put it back safely, "
                            + "so no mod was touched this round. " + String.join(" | ", recovery.problems()),
                    ForbricLog.TAG + " could not recover an unfinished file replacement; no mod was touched.");
        }
        cleanDownloadCache();
        long scanStart = System.nanoTime();
        List<ModJarInfo> jars = ModJarScanner.scan(modsDir);
        long scanNanos = System.nanoTime() - scanStart;
        int neo = 0;
        int forge = 0;
        int fabric = 0;
        int multi = 0;
        int unknown = 0;
        List<ModJarInfo> forgeJars = new ArrayList<>();
        List<ModJarInfo> fabricJars = new ArrayList<>();
        for (ModJarInfo jar : jars) {
            if (jar.multiLoader()) {
                multi++;
            }
            switch (jar.kind()) {
                case NEOFORGE -> neo++;
                case FORGE -> {
                    forge++;
                    forgeJars.add(jar);
                }
                case FABRIC, QUILT -> {
                    fabric++;
                    fabricJars.add(jar);
                }
                default -> unknown++;
            }
        }


        Set<String> blacklistedFiles = new LinkedHashSet<>();
        for (ModJarInfo jar : jars) {
            if (!blacklist.contains(jar.modId())) {
                continue;
            }
            blacklistedFiles.add(jar.fileName());
            ForbricLog.info("  blacklisted, skipped: %s (%s)", jar.fileName(), jar.modId());
            report(jar, "blacklisted", "listed in " + blacklist.origin(), false);
        }
        if (!blacklistedFiles.isEmpty()) {

            ForbricLog.info("blacklist: %d mod(s) skipped (%s)", blacklistedFiles.size(), blacklist.origin());
        }


        int plainNeoForge = 0;
        for (ModJarInfo jar : jars) {
            if (blacklistedFiles.contains(jar.fileName())) {

                continue;
            }
            if (jar.kind() == ModKind.NEOFORGE && !jar.multiLoader()) {
                plainNeoForge++;
                continue;
            }
            ForbricLog.debug("  %s", jar.describe());
        }
        if (plainNeoForge > 0) {
            ForbricLog.debug("  (+%d plain NeoForge mod(s), nothing to do)", plainNeoForge);
        }
        if (multi > 0) {
            ForbricLog.debug("multi-loader jars: %d (decision memory on)", multi);
        }


        ForbricLog.game("Scanned %d mod jar(s): %d NeoForge, %d Forge, %d Fabric/Quilt, %d other%s",
                jars.size(), neo, forge, fabric, unknown,
                blacklistedFiles.isEmpty() ? "" : ", " + blacklistedFiles.size() + " blacklisted");


        DuplicateJars.Plan dupPlan = DuplicateJars.plan(jars, TranspileEngine::sha1Of);
        Map<String, DuplicateJars.Conflict> duplicates = new LinkedHashMap<>();
        Map<String, OriginalSnapshot> loserSnapshots = new LinkedHashMap<>();
        if (removeRedundant) {
            for (Map.Entry<String, DuplicateJars.Conflict> entry : dupPlan.losers().entrySet()) {
                if (blacklistedFiles.contains(entry.getKey())
                        || blacklistedFiles.contains(entry.getValue().winner().fileName())) {


                    ForbricLog.warn("  duplicate not resolved: %s vs %s (blacklisted) -> both kept",
                            entry.getValue().winner().fileName(), entry.getKey());
                    continue;
                }
                duplicates.put(entry.getKey(), entry.getValue());
                ModJarInfo loserJar = jarNamed(jars, entry.getKey());
                loserSnapshots.put(entry.getKey(), loserJar == null ? null : snapshotOf(loserJar));
            }
        }
        List<ModJarInfo> skippedJars = new ArrayList<>();
        for (ModJarInfo jar : dupPlan.skipped()) {
            if (blacklistedFiles.contains(jar.fileName())) {

                continue;
            }
            skippedJars.add(jar);
            ForbricLog.info("  keep %s: modid %s is also provided by a native NeoForge file; the duplicate "
                    + "check runs after the replacements", jar.fileName(), String.join("/", jar.allModIds()));
        }
        Set<String> excluded = new LinkedHashSet<>(skippedJars.stream().map(ModJarInfo::fileName).toList());
        excluded.addAll(blacklistedFiles);
        excluded.addAll(duplicates.keySet());
        forgeJars.removeIf(jar -> excluded.contains(jar.fileName()));
        fabricJars.removeIf(jar -> excluded.contains(jar.fileName()));
        for (Map.Entry<String, DuplicateJars.Conflict> entry : duplicates.entrySet()) {
            ForbricLog.info("  duplicate modid %s: keeping %s, %s will be removed once it is replaced",
                    entry.getValue().describeIds(), entry.getValue().winner().fileName(), entry.getKey());
        }

        TranspileState state = TranspileState.load(workDir, decisionEnv(), negativeTtlMillis());

        boolean needFabric = !fabricJars.isEmpty() && config.getBool("fabric.enable", true);
        boolean needForge = !forgeJars.isEmpty() && config.getBool("forge.enable", true);


        int fabricReplaced = 0;
        List<ModJarInfo> fabricRemaining = new ArrayList<>();
        if (needFabric) {
            int[] counters = transpileFabric(fabricJars, state, fabricRemaining);
            fabricReplaced = counters[0];
        } else if (!fabricJars.isEmpty()) {
            fabricRemaining.addAll(fabricJars);
            ForbricLog.info("fabric mods: %d, but fabric.enable=false, skipped", fabricJars.size());
        }


        int replaced = 0;
        int sameVersion = 0;
        int failed = 0;
        if (needForge) {
            int[] counters = transpileForge(forgeJars, state);
            replaced = counters[0];
            sameVersion = counters[1];
            failed = counters[2];
        } else if (!forgeJars.isEmpty()) {
            ForbricLog.info("forge mods: %d, but forge.enable=false, skipped", forgeJars.size());
        }


        if (autoDependencies) {
            installMissingDependencies();
        }


        resolveDuplicates(duplicates, loserSnapshots);
        DuplicateReview duplicateReview = resolveNativeOverlaps(blacklistedFiles, duplicates,
                dupPlan.nativeOverlaps().keySet());


        List<ModJarInfo> inventory = duplicateReview.inventory();
        int fabricKept = 0;
        List<ModJarInfo> connectorDependents = new ArrayList<>();
        for (ModJarInfo jar : inventory) {
            if (jar.kind() == ModKind.FABRIC || jar.kind() == ModKind.QUILT) {
                fabricKept++;
            }
            if (ModDependencies.needsConnector(jar.path())) {
                connectorDependents.add(jar);
            }
        }
        if (fabricKept != fabricRemaining.size()) {
            ForbricLog.debug("fabric mods still on disk: %d (pipeline kept %d)", fabricKept, fabricRemaining.size());
        }
        if (!connectorDependents.isEmpty()) {
            ForbricLog.info("connector dependents: %d", connectorDependents.size());
            for (ModJarInfo jar : connectorDependents) {
                ForbricLog.info("  %s deps=%s", jar.fileName(), ModDependencies.of(jar.path()));
            }
        }


        boolean[] fabricResult = new boolean[] {false, false, false, false, false, false};
        boolean wantFabricSupport = fabricKept > 0 || !connectorDependents.isEmpty();
        if (wantFabricSupport && config.getBool("fabric.enable", true)) {
            fabricResult = ensureFabricSupport(inventory, fabricKept > 0);
        } else if (!fabricJars.isEmpty() && fabricKept == 0) {
            ForbricLog.info("no connector needed: all fabric mods replaced");
        } else if (config.getBool("fabric.enable", true)) {
            ForbricLog.info("no connector needed: no fabric mods");
        }
        boolean connectorReady = fabricResult[0];
        boolean fabricApiReady = fabricResult[1];
        boolean connectorDownloaded = fabricResult[2];
        boolean fabricApiDownloaded = fabricResult[3];
        boolean connectorEnabled = fabricResult[4];
        boolean fabricApiEnabled = fabricResult[5];

        state.save();

        Result result = new Result(jars.size(), neo, forge, fabric, multi, unknown, blacklistedFiles.size(), replaced,
                sameVersion, failed, fabricReplaced, fabricKept, connectorReady, fabricApiReady, connectorDownloaded,
                fabricApiDownloaded, connectorEnabled, fabricApiEnabled, serviceLayerChanged, List.copyOf(notes),
                List.copyOf(errors));


        RunStats.scanned(scanNanos);
        if (replaced + fabricReplaced > 0) {
            ForbricLog.debug("backup dir: %s", workDir.resolve("backup"));
        }


        if (result.restartNeeded()) {
            ForbricLog.info("restart required: %s", restartReasons(result));
        }
        if (!errors.isEmpty()) {
            ForbricLog.error("failures: %d, originals kept (NeoForge will report the untouched files)", errors.size());
            ForbricLog.gameWarn("%d mod(s) could not be translated and were left untouched; "
                    + "NeoForge will report them as incompatible files", errors.size());
        }


        if (!notes.isEmpty()) {
            ForbricLog.warn("non-fatal failures: %d (originals kept)", notes.size());
            for (String note : notes) {
                ForbricLog.warn("  - %s", note);
            }
        }
        writeReport(result);
        RunStats.writeSummary();


        int changed = replaced + fabricReplaced;
        if (changed > 0) {
            ForbricLog.game("Translated %d mod(s) to NeoForge builds%s", changed,
                    fabricKept > 0 ? " (" + fabricKept + " kept as Fabric)" : "");
        } else {
            ForbricLog.game("Nothing to translate");
        }
        if (duplicateReview.removedRedundant() > 0) {
            ForbricLog.game("Moved %d redundant duplicate mod file(s) to the backup folder",
                    duplicateReview.removedRedundant());
        }
        int duplicateFilesToReview = duplicateReview.keptDuplicates().size() + duplicateReview.nativeConflicts();
        if (duplicateFilesToReview > 0) {
            ForbricLog.gameWarn("%d duplicate mod file(s) need your review: another file provides the same modid; "
                    + "nothing was removed for them, the log lists the full paths", duplicateFilesToReview);
        }
        if (dependenciesInstalled > 0) {
            ForbricLog.game("Installed %d missing dependency mod(s)", dependenciesInstalled);
        }
        if (connectorDownloaded || connectorEnabled) {
            ForbricLog.gameWarn("Sinytra Connector %s; a restart is required to activate it",
                    connectorEnabled ? "enabled" : "downloaded");
        } else if (connectorReady) {
            ForbricLog.game("Sinytra Connector ready");
        }
        if (!errors.isEmpty()) {
            ForbricLog.gameError("Translation failed on %d mod(s); see logs/neoforbric.log", errors.size());
        } else if (!notes.isEmpty()) {
            ForbricLog.gameWarn("%d mod(s) could not be translated (original files kept)", notes.size());
        }
        ForbricLog.game("Translation finished in %s; detailed log: logs/neoforbric.log", Timing.elapsed(runStart));
        return result;
    }


    private static String restartReasons(Result result) {
        StringBuilder why = new StringBuilder();
        if (result.connectorDownloaded() || result.connectorEnabled()) {
            why.append("connector just installed ");
        }
        if (result.serviceLayerChanged() > 0) {
            why.append(result.serviceLayerChanged()).append(" jar(s) gained service layer entries ");
        }
        if (result.fabricReplaced() > 0) {
            why.append(result.fabricReplaced()).append(" fabric mod(s) replaced in place ");
        }
        String text = why.toString().trim();
        return text.isEmpty() ? "unknown" : text;
    }


    private int[] transpileFabric(List<ModJarInfo> fabricJars, TranspileState state, List<ModJarInfo> remaining) {
        ProgressStage stage = beginStage("fabric", "Translating Fabric", fabricJars.size());
        ExecutorCompletionService<Object> completions = new ExecutorCompletionService<>(pool);
        Map<Future<Object>, Pending> pending = new LinkedHashMap<>();
        for (ModJarInfo jar : fabricJars) {
            String sha1 = sha1Of(jar);
            TranspileState.Decision known = state.decisionOf(sha1);


            boolean portMapped = portMap.find(jar.modId()).isPresent();
            if (known != null) {


                if (known.action().startsWith(TranspileState.KEPT_FABRIC)) {
                    if (!portMapped) {
                        ForbricLog.info("  skip %s: memory=%s", jar.fileName(), known.describe());
                        report(jar, "cached", known.describe(), false);
                        remaining.add(jar);
                        stage.skipped(jar.fileName(), screen("Skipped " + jar.fileName()));
                        continue;
                    }
                    ForbricLog.info("  %s: memory=%s but a port is mapped, re-evaluating", jar.fileName(),
                            known.describe());
                } else {
                    ForbricLog.info("  %s: memory=%s but original is back, replacing again",
                            jar.fileName(), known.describe());
                }
            }
            Future<Object> future = completions.submit(() -> prepare(jar, Mode.FABRIC, sha1));
            pending.put(future, new Pending(jar, sha1, future));
        }
        if (!pending.isEmpty()) {

            stall();
        }

        int replaced = 0;
        while (!pending.isEmpty()) {
            Pending item = nextCompleted(completions, pending);
            ModJarInfo jar = item.jar();
            String sha1 = item.sha1();
            Object outcome = await(item.future());
            if (outcome instanceof Prepared prepared) {
                InstallResult installedResult = apply(prepared);
                if (installedResult != null) {
                    replaced++;
                    state.remember(prepared.originalSha1(), TranspileState.REPLACED_FABRIC,
                            prepared.target().versionNumber());
                    reportReplaced(jar, prepared, installedResult, prepared.target().sameVersion());
                    stage.done(jar.fileName(), screen("Translated " + jar.fileName()));
                } else {
                    remaining.add(jar);
                    stage.failed(jar.fileName(), screen("Failed " + jar.fileName()));
                }
            } else if (outcome instanceof Kept kept) {
                ForbricLog.info("  keep fabric %s: %s (%s)", jar.fileName(), kept.reason(),
                        kept.elapsed());
                state.remember(sha1, TranspileState.KEPT_FABRIC, kept.reason());
                remaining.add(jar);
                report(jar, "kept-fabric", kept.reason(), false);
                stage.kept(jar.fileName(), screen("Kept " + jar.fileName()));
            } else {
                remaining.add(jar);
                if (outcome instanceof Failure failure) {
                    if (failure.permanent()) {


                        ForbricLog.info("  keep fabric %s: %s (remembered)",
                                jar.fileName(), failure.reason());
                        state.remember(sha1, TranspileState.KEPT_FABRIC, failure.reason());
                        report(jar, "kept-fabric", failure.reason(), false);
                        stage.kept(jar.fileName(), screen("Kept " + jar.fileName()));
                    } else {
                        ForbricLog.warn("  keep fabric %s: %s (not remembered, retried next launch)",
                                jar.fileName(), failure.reason());
                        notes.add("fabric failed: " + jar.fileName() + " (" + failure.reason() + ")");
                        report(jar, "failed", failure.reason(), false);
                        stage.failed(jar.fileName(), screen("Failed " + jar.fileName()));
                    }
                } else {
                    stage.failed(jar.fileName(), screen("Failed " + jar.fileName()));
                }
            }
        }
        endStage(stage);
        return new int[] {replaced, remaining.size()};
    }


    private int[] transpileForge(List<ModJarInfo> forgeJars, TranspileState state) {
        ProgressStage stage = beginStage("forge", "Translating Forge", forgeJars.size());
        ExecutorCompletionService<Object> completions = new ExecutorCompletionService<>(pool);
        Map<Future<Object>, Pending> pending = new LinkedHashMap<>();
        int replaced = 0;
        int sameVersion = 0;
        int failed = 0;
        for (ModJarInfo jar : forgeJars) {
            String sha1 = sha1Of(jar);
            TranspileState.Decision known = state.decisionOf(sha1);

            boolean portMapped = portMap.find(jar.modId()).isPresent();
            if (known != null) {
                if (known.action().startsWith(TranspileState.FAILED_FORGE) && !portMapped) {


                    String reason = known.detail();
                    failed++;
                    ForbricLog.info("  skip %s: known failure (%s)", jar.fileName(), reason);
                    report(jar, "failed", reason, false);
                    addError(jar, forgeFailureMessage(jar, reason));
                    stage.failed(jar.fileName(), screen("Failed " + jar.fileName()));
                    continue;
                }


                if (!known.action().startsWith(TranspileState.REPLACED_FORGE)) {
                    if (!portMapped) {
                        ForbricLog.info("  skip %s: memory=%s", jar.fileName(), known.describe());
                        report(jar, "cached", known.describe(), false);
                        stage.skipped(jar.fileName(), screen("Skipped " + jar.fileName()));
                        continue;
                    }
                    ForbricLog.info("  %s: memory=%s but a port is mapped, re-evaluating", jar.fileName(),
                            known.describe());
                } else {
                    ForbricLog.info("  %s: memory=%s but original is back, replacing again", jar.fileName(),
                            known.describe());
                }
            }
            Future<Object> future = completions.submit(() -> prepare(jar, Mode.FORGE, sha1));
            pending.put(future, new Pending(jar, sha1, future));
        }
        if (!pending.isEmpty()) {

            stall();
        }

        while (!pending.isEmpty()) {
            Pending item = nextCompleted(completions, pending);
            ModJarInfo jar = item.jar();
            String sha1 = item.sha1();
            Object outcome = await(item.future());
            if (outcome instanceof Prepared prepared) {
                InstallResult installedResult = apply(prepared);
                if (installedResult != null) {
                    replaced++;
                    if (prepared.target().sameVersion()) {
                        sameVersion++;
                    }
                    state.remember(prepared.originalSha1(), TranspileState.REPLACED_FORGE,
                            prepared.target().versionNumber());
                    reportReplaced(jar, prepared, installedResult, prepared.target().sameVersion());
                    stage.done(jar.fileName(), screen("Translated " + jar.fileName()));
                } else {
                    failed++;
                    stage.failed(jar.fileName(), screen("Failed " + jar.fileName()));
                }
            } else {
                failed++;
                if (outcome instanceof Failure failure) {
                    if (failure.permanent()) {


                        state.remember(sha1, TranspileState.FAILED_FORGE, failure.reason());
                    }
                    notes.add("forge failed: " + jar.fileName() + " (" + failure.reason() + ")");
                    report(jar, "failed", failure.reason(), false);
                    if (failure.hard()) {
                        addError(jar, forgeFailureMessage(jar, failure.reason()));
                    } else {

                        ForbricLog.warn("  keeping original %s: %s", jar.fileName(), failure.reason());
                    }
                }
                stage.failed(jar.fileName(), screen("Failed " + jar.fileName()));
            }
        }
        endStage(stage);
        return new int[] {replaced, sameVersion, failed};
    }


    private String forgeFailureMessage(ModJarInfo jar, String reason) {
        return ForbricLog.TAG + " could not find a NeoForge build for " + jar.fileName()
                + " (modid=" + safe(jar.modId()) + ", version=" + safe(jar.version())
                + ") on the official Modrinth API for Minecraft " + gameVersion + ". Reason: " + reason
                + ". The original Forge jar was left untouched. See logs/neoforbric.log for the full trace.";
    }


    private Object prepare(ModJarInfo jar, Mode mode, String sha1) {
        long startedAt = System.nanoTime();
        ForbricLog.info("  process %s (%s)", jar.describe(), mode.label);
        TranspileTarget chosen = null;


        String requiredModId = null;
        boolean portAuthorized = false;
        QueryResult<TranspileTarget> uncertain = null;
        try {

            Optional<PortMap.Port> mapped = portMap.find(jar.modId());
            if (mapped.isPresent()) {
                PortMap.Port port = mapped.get();
                QueryResult<TranspileTarget> viaPort = latestInChain(port.targets());
                if (viaPort.hasValue()) {
                    chosen = viaPort.value().withHowFound(MatchKind.PORT_MAP);
                    requiredModId = port.expectedModId();
                    portAuthorized = true;
                    ForbricLog.info("  port map: %s -> %s (%s)", jar.modId(), port.targets().get(0).project(),
                            chosen.versionNumber());
                } else {
                    if (viaPort.uncertain()) {
                        uncertain = viaPort;
                    }
                    List<PortMap.Unsupported> dropped = new ArrayList<>();
                    for (PortMap.Target target : port.targets()) {
                        if (!target.source().supported()) {
                            dropped.add(new PortMap.Unsupported(jar.modId(), target.project()));
                        }
                    }
                    if (dropped.size() == port.targets().size()) {
                        ForbricLog.warn("  port map: %s -> %s names a source this build removed, no request was"
                                + " sent; falling back to the normal search. Replace the CurseForge id in"
                                + " ports.properties with a Modrinth id or slug (this file is not changed"
                                + " automatically)", jar.modId(), port.targets().get(0).project());
                        notes.add("port map " + jar.modId() + " names a removed source, ignored");
                    } else {
                        ForbricLog.warn("  port map: %s -> %s has no %s NeoForge build, falling back",
                                jar.modId(), port.targets().get(0).project(), gameVersion);
                    }
                }
            }

            if (chosen == null) {
                QueryResult<TranspileTarget> found = findInChain(jar, mode == Mode.FORGE, sha1);
                if (found.hasValue()) {
                    chosen = found.value();
                } else if (found.uncertain()) {
                    uncertain = found;
                }
            }

            if (chosen == null && uncertain == null && crawlEnabled && !jar.modId().isBlank()) {
                ForbricLog.info("  no build found, crawling by modid: %s%s", jar.modId(),
                        crawlStrict ? " (exact / prefix match only)" : "");
                QueryResult<TranspileTarget> crawled = crawlByModId(jar.modId(), jar.displayName());
                if (crawled.hasValue()) {
                    chosen = crawled.value();
                    ForbricLog.info("  crawl hit: %s", chosen.describe());
                } else if (crawled.uncertain()) {
                    uncertain = crawled;
                }
            }
        } catch (RuntimeException e) {


            ForbricLog.error("source query failed: " + jar.fileName(), e);
            return new Failure(jar, "source query error: " + e, false);
        }
        if (chosen == null) {
            if (uncertain != null) {
                return new Failure(jar, "query not answered: " + uncertain.describe(), false, false);
            }
            if (mode == Mode.FABRIC) {
                return new Kept(jar, "no NeoForge build (same-version / port map / modid lookup all missed)",
                        Timing.elapsed(startedAt));
            }
            return new Failure(jar, "no NeoForge build for " + gameVersion, true);
        }
        if (!portAuthorized && chosen.howFound().needsIdentityCheck()) {
            requiredModId = jar.modId();
        }

        Path staging = workDir.resolve("downloads").resolve(jar.fileName() + ".new");
        try {
            Files.createDirectories(staging.getParent());
        } catch (IOException e) {
            ForbricLog.error("staging dir failed: " + staging.getParent(), e);
            return new Failure(jar, "cannot create staging dir", false);
        }

        ForbricLog.info("  downloading %s from %s (%s)", chosen.fileName(), chosen.project().source(),
                chosen.howFound().label());
        if (!http.download(chosen.downloadUrl(), staging, downloadLabel(jar), chosen.sha1(),
                chosen.versionNumber(), chosen.size(), downloadScope(jar.fileName(), downloadLabel(jar)))) {


            return new Failure(jar, "download failed: " + chosen.fileName(), false, false);
        }


        try {
            ModJarInfo check = ModJarScanner.inspect(staging);
            if (check.kind() != ModKind.NEOFORGE) {
                ForbricLog.error("  downloaded file is not a NeoForge mod (%s), aborting", check.kind().display());
                Files.deleteIfExists(staging);
                return new Failure(jar, "downloaded file is not a NeoForge mod", true);
            }


            String gate = identityGate(jar, chosen, check, requiredModId, portAuthorized);
            if (gate != null) {
                ForbricLog.error("  %s", gate);
                Files.deleteIfExists(staging);
                return new Failure(jar, gate, true);
            }

            String newSha1 = com.deepseek.neoforbric.core.Hashes.sha1(staging);
            if (newSha1.equalsIgnoreCase(sha1)) {
                ForbricLog.info("  identical to original (sha1), no replacement");
                Files.deleteIfExists(staging);
                return new Kept(jar, "identical to original", Timing.elapsed(startedAt));
            }
            ForbricLog.info("  verified: %s provides %s", check.describe(),
                    check.allModIds().isEmpty() ? "no modid" : String.join("/", check.allModIds()));
            boolean serviceChange = ServiceEntries.serviceLayerChanged(jar.path(), staging);
            if (serviceChange) {
                ForbricLog.info("  service layer use changed (%s -> %s) -> restart required",
                        ServiceEntries.scan(jar.path()).describe(), ServiceEntries.scan(staging).describe());
            }
            return new Prepared(jar, chosen, staging, Timing.secondsOf(startedAt), sha1, serviceChange,
                    check.allModIds());
        } catch (IOException e) {
            ForbricLog.error("verify downloaded file failed", e);
            return new Failure(jar, "verify downloaded file failed", false);
        }
    }


    private static String identityGate(ModJarInfo jar, TranspileTarget chosen, ModJarInfo downloaded,
            String requiredModId, boolean portAuthorized) {
        if (requiredModId == null || requiredModId.isBlank()) {
            if (portAuthorized) {
                return null;
            }
            if (chosen.howFound().needsIdentityCheck()) {
                return "cannot verify identity: the match came from " + chosen.howFound().label()
                        + ", but the original jar declares no modid to compare against";
            }
            return null;
        }
        if (downloaded.provides(requiredModId)) {
            return null;
        }
        return "modid mismatch: found by " + chosen.howFound().label() + ", the download provides "
                + (downloaded.allModIds().isEmpty() ? "no modid" : String.join("/", downloaded.allModIds()))
                + ", expected " + requiredModId;
    }


    private static String describeReplacement(Prepared prepared, InstallResult install, ModJarInfo installed,
            Path backup) {
        TranspileTarget target = prepared.target();
        String source = target.fileName() == null || target.fileName().isBlank()
                ? "an unnamed source file" : target.fileName();
        return "source " + source + " [" + target.versionNumber() + "] via " + target.howFound().label()
                + "; installed as " + install.installedPath().getFileName() + " [" + installed.kind().display() + " "
                + versionText(installed) + ", id=" + idText(installed) + "] at " + install.installedPath()
                + "; backup " + (backup == null ? "not created" : backup.toString());
    }

    private static ModJarInfo inspectQuietly(Path jar) {
        try {
            return ModJarScanner.inspect(jar);
        } catch (IOException e) {
            return new ModJarInfo(jar, jar.getFileName().toString(), ModKind.UNKNOWN, "", "", "",
                    -1, List.of(), List.of());
        }
    }


    private InstallResult apply(Prepared prepared) {
        ModJarInfo jar = prepared.jar();
        TranspileTarget chosen = prepared.target();
        boolean backup = config.getBool("forge.backup", true);
        boolean keepName = config.getBool("forge.keepFileName", true) || chosen.fileName().isBlank();
        FileInstaller.Outcome outcome = installer.install(
                FileInstaller.Request.replacing(prepared.staging(), jar.path(), chosen.fileName(), keepName, backup));
        for (String step : outcome.steps()) {
            ForbricLog.debug("  %s: %s", jar.fileName(), step);
        }
        if (!outcome.ok()) {
            if (outcome.failedStep() == FileInstaller.Step.CONFLICT) {
                ForbricLog.warn("  %s: another mod in this run already placed a different build at the same "
                        + "target name; the file placed first is kept", jar.fileName());
            }
            ForbricLog.error("replace failed: %s at %s (%s)", jar.fileName(), outcome.failedStep(), outcome.detail());
            notes.add("replace failed: " + jar.fileName() + " (" + outcome.detail() + ")");
            report(jar, "failed", outcome.detail(), false);
            addError(jar, ForbricLog.TAG + " failed to replace " + jar.fileName() + ": " + outcome.detail());
            return null;
        }
        Path installed = outcome.installedPath();
        ModJarInfo installedInfo = inspectQuietly(installed);
        if (outcome.reused()) {
            ForbricLog.info("  reused the build already in mods/ for %s: %s [%s %s, id=%s] at %s",
                    jar.fileName(), installed.getFileName(), installedInfo.kind().display(),
                    versionText(installedInfo), idText(installedInfo), installed);
        } else if (!installed.equals(jar.path())) {
            ForbricLog.info("  renamed: %s -> %s (forge.keepFileName=false)", jar.fileName(),
                    installed.getFileName());
        }
        if (outcome.rollbackLeftover() != null) {
            ForbricLog.warn("  a temporary file is still on disk: %s (it is not an active mod file)",
                    outcome.rollbackLeftover());
            notes.add("temporary file left behind: " + outcome.rollbackLeftover());
        }
        ForbricLog.info("  replaced %s [%s %s, id=%s] at %s", jar.fileName(), jar.kind().display(),
                versionText(jar), idText(jar), jar.path());
        ForbricLog.info("    source returned %s [%s] from %s",
                chosen.fileName() == null || chosen.fileName().isBlank() ? "an unnamed file" : chosen.fileName(),
                chosen.versionNumber(), chosen.project().source());
        ForbricLog.info("    installed as %s [%s %s, id=%s] at %s", installed.getFileName(),
                installedInfo.kind().display(), versionText(installedInfo), idText(installedInfo), installed);
        ForbricLog.info("    backup %s; elapsed %s", outcome.backupPath() == null
                ? (backup ? "not created" : "none (forge.backup=false)") : outcome.backupPath().toString(),
                Timing.seconds(prepared.elapsedSeconds()));

        if (prepared.serviceLayerChanged()) {


            serviceLayerChanged++;
        }
        RunStats.replaced(jar.fileName(), jar.kind().display(), jar.version(), chosen.versionNumber(),
                prepared.elapsedSeconds());
        InstallResult result = new InstallResult(jar.path(), installed, jar.fileName(), prepared.originalSha1(),
                prepared.providedModIds(), outcome.backupPath(), outcome.rollbackLeftover());
        applied.put(jar.path(), result);
        installedThisRound.add(installed);
        return result;
    }


    private boolean[] ensureFabricSupport(List<ModJarInfo> inventory, boolean fabricModsPresent) {
        String connectorProject = config.get("fabric.connector.projects", "connector,sinytra-connector");
        String fabricApiProject = config.get("fabric.api.projects", "forgified-fabric-api");

        ProgressStage stage = beginStage("fabric-support", "Preparing Fabric support", 2);


        boolean connectorInPlace = false;
        boolean connectorReady = false;
        boolean connectorDownloaded = false;
        boolean connectorUnanswered = false;


        boolean connectorEnabled = false;
        String connectorVersion = null;
        QueryResult<TranspileTarget> connectorTarget = QueryResult.notFound("not resolved yet");
        Path installed = ConnectorJar.findEnabled(modsDir, true).orElse(null);
        if (installed != null) {
            connectorInPlace = true;
            connectorReady = true;
            connectorJar = installed;
            connectorVersion = ConnectorJar.versionOf(installed).orElse(null);
            ForbricLog.debug("  connector present: %s (%s)", installed.getFileName(), orUnknown(connectorVersion));
            progress.message("Sinytra Connector already in place");
        } else if (tryEnableDisabledConnector(installed)) {

            connectorInPlace = true;
            connectorReady = true;
            connectorEnabled = true;
            installed = connectorJar;
            connectorVersion = ConnectorJar.versionOf(installed).orElse(null);
            progress.message("Sinytra Connector re-enabled");
        } else {
            connectorTarget = latestInChain(connectorProject);
            if (!connectorTarget.hasValue()) {
                connectorUnanswered = connectorTarget.uncertain();
                ForbricLog.error("no NeoForge build for connector (%s): %s", connectorProject,
                        connectorTarget.describe());
                addError(null, connectorUnanswered
                        ? ForbricLog.TAG + " needs Sinytra Connector but the official Modrinth API could not be queried"
                                + " successfully for " + connectorProject + ". CurseForge access was removed in"
                                + " 0.0.10 and no address is guessed. This is not a statement that the component is"
                                + " missing: try again, and check logs/neoforbric.log."
                        : ForbricLog.TAG + " needs Sinytra Connector but no matching build could be fetched from the"
                                + " official Modrinth API for " + connectorProject + ". CurseForge access was removed"
                                + " in 0.0.10 and no address is guessed. Fabric mods cannot be loaded without it:"
                                + " download Sinytra Connector yourself, put the jar into mods/ and restart.");
            } else {
                connectorVersion = connectorTarget.value().versionNumber();
                ForbricLog.info("  connector target: %s", connectorTarget.value().describe());
            }
        }


        ModJarInfo installedApi = firstWithModId(inventory, FABRIC_API_MOD_ID);
        TranspileTarget fabricApiTarget = null;
        boolean fabricApiInPlace = false;
        boolean fabricApiReady = false;
        boolean fabricApiDownloaded = false;
        boolean fabricApiEnabled = false;
        boolean fabricApiUnanswered = false;
        String fabricApiVersion = null;
        if (!fabricModsPresent) {
            ForbricLog.info("  ffapi not needed: no Fabric mod is left to load");
        } else if (installedApi != null) {
            fabricApiInPlace = true;
            fabricApiReady = true;
            fabricApiVersion = installedApi.version();
            ForbricLog.debug("  ffapi present: %s (%s)",
                    installedApi.fileName(), orUnknown(installedApi.version()));
            progress.message("Forgified Fabric API already in place");
        } else {


            QueryResult<TranspileTarget> fabricApi = QueryResult.notFound("not resolved yet");
            if (connectorVersion != null && !connectorVersion.isBlank()) {
                QueryResult<TranspileTarget> pinned = pinnedDependency(connectorVersion, fabricApiProject);
                if (pinned.hasValue()) {
                    fabricApi = pinned;
                }
            }
            if (!fabricApi.hasValue()) {
                QueryResult<TranspileTarget> latest = latestInChain(fabricApiProject);
                if (latest.hasValue() || !fabricApi.uncertain()) {
                    fabricApi = latest;
                }
            }
            fabricApiTarget = fabricApi.hasValue() ? fabricApi.value() : null;
            if (fabricApiTarget == null) {
                fabricApiUnanswered = fabricApi.uncertain();
                ForbricLog.error("no NeoForge build for ffapi (%s): %s", fabricApiProject, fabricApi.describe());
                addError(null, fabricApiUnanswered
                        ? ForbricLog.TAG + " needs Forgified Fabric API but the official Modrinth API could not be queried"
                                + " successfully for " + fabricApiProject + ". CurseForge access was removed in"
                                + " 0.0.10 and no address is guessed. This is not a statement that the component is"
                                + " missing: try again, and check logs/neoforbric.log."
                        : ForbricLog.TAG + " needs Forgified Fabric API but no matching build could be fetched from the"
                                + " official Modrinth API for " + fabricApiProject + ". CurseForge access was removed"
                                + " in 0.0.10 and no address is guessed: download Forgified Fabric API yourself, put"
                                + " the jar into mods/ and restart.");
            } else {
                fabricApiVersion = fabricApiTarget.versionNumber();
                ForbricLog.info("  ffapi target: %s", fabricApiTarget.describe());

                Path expected = modsDir.resolve(fabricApiTarget.fileName());
                if (Files.isRegularFile(expected)) {
                    if (providesModId(expected, FABRIC_API_MOD_ID)) {
                        fabricApiInPlace = true;
                        fabricApiReady = true;
                        ForbricLog.info("  ffapi present");
                        progress.message("Forgified Fabric API already in place");
                    } else {
                        String detail = "mods/" + expected.getFileName() + " already exists but does not provide "
                                + FABRIC_API_MOD_ID + " (it provides " + jarIdentity(expected)
                                + "); refusing to overwrite it";
                        ForbricLog.error("  %s", detail);
                        notes.add("fabric api conflict: " + detail);
                        addError(null, ForbricLog.TAG + " needs " + FABRIC_API_MOD_ID + " but " + expected.getFileName()
                                + " is a different mod. Move or delete that file.");
                    }
                } else if (tryEnableDisabledFabricApi(fabricApiTarget.versionNumber())) {

                    fabricApiInPlace = true;
                    fabricApiReady = true;
                    fabricApiEnabled = true;
                    progress.message("Forgified Fabric API re-enabled");
                }
            }
        }


        boolean needConnector = connectorTarget.hasValue() && !connectorReady;


        boolean needFabricApi = fabricApiTarget != null && !fabricApiReady;
        boolean connectorDispatched = needConnector;
        boolean fabricApiDispatched = needFabricApi;
        if (needConnector || needFabricApi) {

            stall();
        }
        DownloadOutcome connectorOutcome = DownloadOutcome.NOT_ATTEMPTED;
        DownloadOutcome fabricApiOutcome = DownloadOutcome.NOT_ATTEMPTED;
        if (needConnector && needFabricApi) {
            progress.message("Translating Sinytra Connector + Forgified Fabric API");

            TranspileTarget connectorForDownload = connectorTarget.value();
            TranspileTarget apiTarget = fabricApiTarget;
            Future<DownloadOutcome> connectorFuture = pool.submit(
                    () -> installSupport(stage, "connector", connectorForDownload, "Sinytra Connector", null, true));
            Future<DownloadOutcome> fabricApiFuture = pool.submit(
                    () -> installSupport(stage, "ffapi", apiTarget, "Forgified Fabric API", FABRIC_API_MOD_ID, false));
            connectorOutcome = awaitOr(connectorFuture, DownloadOutcome.FAILED);
            fabricApiOutcome = awaitOr(fabricApiFuture, DownloadOutcome.FAILED);
        } else if (needConnector) {
            progress.message("Translating Sinytra Connector");
            connectorOutcome = installSupport(stage, "connector", connectorTarget.value(), "Sinytra Connector", null, true);
        } else if (needFabricApi) {
            progress.message("Translating Forgified Fabric API");
            fabricApiOutcome = installSupport(stage, "ffapi", fabricApiTarget, "Forgified Fabric API", FABRIC_API_MOD_ID, false);
        }

        if (needConnector) {
            connectorReady = connectorOutcome.ok();
            connectorDownloaded = connectorReady;
            if (!connectorReady) {
                addError(null, ForbricLog.TAG + " failed to download Sinytra Connector. Check the network or logs/neoforbric.log.");
            } else {

                connectorJar = modsDir.resolve(connectorTarget.value().fileName());
            }
        }
        if (needFabricApi) {
            fabricApiReady = fabricApiOutcome.ok();
            fabricApiDownloaded = fabricApiReady;
            if (!fabricApiReady) {
                addError(null, ForbricLog.TAG + " failed to download Forgified Fabric API. Check the network or logs/neoforbric.log.");
            }
        }

        if (!connectorDispatched) {
            if (connectorReady) {
                stage.done("connector", connectorInPlace
                        ? "Sinytra Connector already in place" : "Sinytra Connector ready");
            } else if (connectorUnanswered) {
                stage.failed("connector", "Sinytra Connector is needed but the source could not be queried");
            } else {
                stage.failed("connector", "Sinytra Connector is needed but no build could be fetched");
            }
        }
        if (!fabricApiDispatched) {
            if (!fabricModsPresent) {
                stage.kept("ffapi", "Forgified Fabric API not needed");
            } else if (fabricApiReady) {
                stage.done("ffapi", fabricApiInPlace
                        ? "Forgified Fabric API already in place" : "Forgified Fabric API ready");
            } else if (fabricApiUnanswered) {
                stage.failed("ffapi", "Forgified Fabric API is needed but the source could not be queried");
            } else {
                stage.failed("ffapi", "Forgified Fabric API is needed but no build could be fetched");
            }
        }
        endStage(stage);


        RunStats.fabricSupport(connectorVersion, connectorDownloaded, connectorOutcome.seconds(),
                fabricApiVersion, fabricApiDownloaded,
                fabricApiOutcome.seconds(), connectorEnabled, fabricApiEnabled);

        boolean fabricApiSatisfied = fabricApiReady || !fabricModsPresent;
        if (!connectorReady || !fabricApiSatisfied) {
            ForbricLog.warn("  fabric support missing: fabric mods may fail to load");
        }
        return new boolean[] {connectorReady, fabricApiSatisfied, connectorDownloaded, fabricApiDownloaded,
                connectorEnabled, fabricApiEnabled};
    }


    private DownloadOutcome installComponent(TranspileTarget target, String displayName, String expectedModId,
            boolean connector) {
        long startedAt = System.nanoTime();
        Path staging = workDir.resolve("downloads").resolve(displayName.replace(' ', '-') + ".jar.new");
        boolean ok = false;
        try {
            Files.createDirectories(staging.getParent());
            if (http.download(target.downloadUrl(), staging, displayName, target.sha1(), target.versionNumber(),
                    target.size(), downloadScope(displayName, displayName))) {
                if (!verifyComponent(staging, displayName, expectedModId, connector)) {
                    notes.add(displayName + " failed its identity check, not installed");
                } else {
                    FileInstaller.Outcome outcome = installer.installNew(staging, target.fileName());
                    if (outcome.ok()) {
                        ok = true;
                        if (outcome.reused()) {
                            ForbricLog.info("  %s already in mods with the same content, kept it", displayName);
                        } else {
                            ForbricLog.info("  installed %s as %s", displayName, outcome.installedPath().getFileName());
                        }
                    } else {
                        ForbricLog.error("  %s was not installed: %s", displayName, outcome.detail());
                        notes.add(displayName + " not installed: " + outcome.detail());
                    }
                }
            }
            if (!ok) {
                Files.deleteIfExists(staging);
            }
        } catch (IOException e) {
            ForbricLog.error("  " + displayName + " install failed", e);
            notes.add(displayName + " install failed: " + e);
        }
        return new DownloadOutcome(ok, (System.nanoTime() - startedAt) / 1e9);
    }

    private DownloadOutcome installSupport(ProgressStage stage, String taskId, TranspileTarget target,
            String displayName, String expectedModId, boolean connector) {
        DownloadOutcome outcome = installComponent(target, displayName, expectedModId, connector);
        if (outcome.ok()) {
            stage.done(taskId, displayName + " ready");
        } else {
            stage.failed(taskId, displayName + " is needed but the download failed");
        }
        return outcome;
    }


    private static boolean verifyComponent(Path jar, String displayName, String expectedModId, boolean connector) {
        if (connector) {
            if (ConnectorJar.isConnectorJar(jar)) {
                return true;
            }
            ForbricLog.error("  %s: the downloaded file is not Sinytra Connector -> aborting", displayName);
            return false;
        }
        try {
            ModJarInfo info = ModJarScanner.inspect(jar);
            if (info.kind() != ModKind.NEOFORGE) {
                ForbricLog.error("  %s: the downloaded file is %s, not a NeoForge mod -> aborting", displayName,
                        info.kind().display());
                return false;
            }
            if (expectedModId != null && !info.provides(expectedModId)) {
                ForbricLog.error("  %s: the downloaded file provides %s, expected %s -> aborting", displayName,
                        info.allModIds().isEmpty() ? "no modid" : String.join("/", info.allModIds()), expectedModId);
                return false;
            }
            return true;
        } catch (IOException e) {
            ForbricLog.error("  " + displayName + " identity check failed", e);
            return false;
        }
    }


    private static boolean providesModId(Path jar, String modId) {
        try {
            return ModJarScanner.inspect(jar).provides(modId);
        } catch (IOException e) {
            ForbricLog.warn("  cannot read %s: %s", jar.getFileName(), e.toString());
            return false;
        }
    }


    private static String jarIdentity(Path jar) {
        try {
            List<String> ids = ModJarScanner.providedModIds(jar);
            return ids.isEmpty() ? "no modid" : String.join("/", ids);
        } catch (IOException e) {
            return "unreadable";
        }
    }


    private boolean tryEnableDisabledConnector(Path alreadyFoundEnabled) {
        if (alreadyFoundEnabled != null) {

            return false;
        }
        Path disabled = ConnectorJar.find(modsDir, true)
                .filter(p -> DisabledMods.isDisabledName(p.getFileName().toString()))
                .orElse(null);
        if (disabled == null) {
            return false;
        }
        ForbricLog.info("  disabled connector found: %s", disabled.getFileName());
        Optional<Path> enabled = DisabledMods.enable(disabled);
        if (enabled.isEmpty()) {

            ForbricLog.warn("  enable %s failed, will download instead", disabled.getFileName());
            return false;
        }
        connectorJar = enabled.get();
        ForbricLog.info("  enabled %s -> %s (kept the player's copy)",
                disabled.getFileName(), connectorJar.getFileName());
        return true;
    }


    private boolean tryEnableDisabledFabricApi(String pinnedVersion) {
        for (Path disabled : DisabledMods.listDisabled(modsDir)) {
            String enabledName = DisabledMods.enabledNameOf(disabled.getFileName().toString());
            if (!enabledName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            ModJarInfo info;
            try {
                info = ModJarScanner.inspect(disabled);
            } catch (IOException e) {
                continue;
            }
            if (!FABRIC_API_MOD_ID.equals(info.modId())) {
                continue;
            }
            ForbricLog.info("  disabled ffapi found: %s (%s)",
                    disabled.getFileName(), orUnknown(info.version()));
            if (pinnedVersion != null && !pinnedVersion.isBlank() && !info.version().isBlank()
                    && !VersionMatch.same(info.version(), pinnedVersion)) {
                ForbricLog.warn("  ffapi version %s != required %s, keeping the player's copy",
                        info.version(), pinnedVersion);
            }
            Optional<Path> enabled = DisabledMods.enable(disabled);
            if (enabled.isEmpty()) {
                return false;
            }
            ForbricLog.info("  enabled %s -> %s (kept the player's copy)",
                    disabled.getFileName(), enabled.get().getFileName());
            return true;
        }
        return false;
    }


    private record DownloadOutcome(boolean ok, double seconds) {
        static final DownloadOutcome FAILED = new DownloadOutcome(false, 0);
        static final DownloadOutcome NOT_ATTEMPTED = new DownloadOutcome(false, 0);
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }


    private QueryResult<TranspileTarget> pinnedDependency(String connectorVersion, String projectSlug) {
        if (modrinthForDependencies == null) {
            return QueryResult.restricted(componentQuery.refusal());
        }
        try {
            QueryResult<com.deepseek.neoforbric.net.ProjectRef> project =
                    modrinthForDependencies.projectBySlug(projectSlug);
            if (!project.hasValue()) {
                return QueryResult.failureOf(project);
            }
            String fabricApiProjectId = project.value().id();
            QueryResult<List<VersionCandidate>> connectorVersions =
                    modrinthForDependencies.listVersions("connector", ModrinthSource.LOADER_NEOFORGE);
            if (!connectorVersions.ok()) {
                return QueryResult.failureOf(connectorVersions);
            }
            for (VersionCandidate candidate : connectorVersions.value()) {
                if (!candidate.versionNumber().equalsIgnoreCase(connectorVersion)) {
                    continue;
                }
                for (VersionCandidate.Dependency dependency : candidate.dependencies()) {
                    if (fabricApiProjectId.equals(dependency.projectId()) && "required".equals(dependency.dependencyType())
                            && dependency.versionId() != null) {
                        QueryResult<VersionCandidate> pinned =
                                modrinthForDependencies.versionById(dependency.versionId());
                        if (pinned.hasValue()) {
                            ForbricLog.info("  ffapi pinned by connector %s: %s", connectorVersion,
                                    dependency.versionId());
                            return QueryResult.ok(toTarget(pinned.value()));
                        }
                        if (pinned.uncertain()) {
                            return QueryResult.failureOf(pinned);
                        }
                    }
                }
            }
            ForbricLog.info("  no matching ffapi for connector %s on Modrinth, using latest", connectorVersion);
        } catch (RuntimeException e) {
            ForbricLog.warn("connector dependency query failed, using latest ffapi: %s", e.toString());
            return QueryResult.temporary("connector dependency query failed: " + e);
        }
        return QueryResult.notFound("connector " + connectorVersion + " does not pin a Forgified Fabric API build");
    }


    private TranspileTarget toTarget(VersionCandidate version) {
        return new TranspileTarget(
                new com.deepseek.neoforbric.net.ProjectRef(version.projectId(), version.projectId(), version.name(),
                        "Modrinth official"),
                version.versionNumber(), version.fileName(), version.downloadUrl(), version.sha1(), version.size(),
                false, null, MatchKind.DEPENDENCY);
    }

    private static ModJarInfo firstWithModId(List<ModJarInfo> jars, String modId) {
        for (ModJarInfo jar : jars) {
            if (jar.kind() == ModKind.NEOFORGE && jar.provides(modId)) {
                return jar;
            }
        }
        return null;
    }


    private void cleanDownloadCache() {
        Path downloads = workDir.resolve("downloads");
        if (Files.isDirectory(downloads)) {
            deleteStaleStaging(downloads);
        }
        long keepMillis = Math.max(1, config.getInt("http.resumeKeepDays", 3)) * 86_400_000L;
        long maxBytes = Math.max(0L, config.getInt("http.resumeCacheMaxMb", 500)) * 1024L * 1024L;
        List<Path> resumeFiles = new ArrayList<>();
        collectResumeFiles(downloads, resumeFiles);
        collectResumeFiles(modsDir, resumeFiles);
        expireResumeFiles(resumeFiles, keepMillis, maxBytes);
    }


    private static void collectResumeFiles(Path dir, List<Path> target) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path entry : stream.toList()) {
                if (isResumeFile(entry)) {
                    target.add(entry);
                }
            }
        } catch (IOException e) {
            ForbricLog.warn("resume scan failed (%s): %s", dir, e.toString());
        }
    }


    private static void deleteStaleStaging(Path downloads) {
        List<Path> entries;
        try (var stream = Files.list(downloads)) {
            entries = stream.toList();
        } catch (IOException e) {
            ForbricLog.warn("cache cleanup failed: %s", e.toString());
            return;
        }
        int removed = 0;
        for (Path entry : entries) {
            if (isResumeFile(entry)) {
                continue;
            }


            if (deleteQuietly(entry)) {
                removed++;
            }
        }
        if (removed > 0) {
            ForbricLog.info("cleaned %d failed download(s) in %s", removed, downloads);
        }
    }


    private static void expireResumeFiles(List<Path> resumeFiles, long keepMillis, long maxBytes) {
        if (resumeFiles.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long keptBytes = 0;
        int expired = 0;
        List<Path> survivors = new ArrayList<>();
        for (Path file : resumeFiles) {
            if (now - lastModifiedMillis(file) > keepMillis) {
                if (deleteQuietly(file)) {
                    expired++;
                    continue;
                }
                ForbricLog.warn("stale resume file could not be removed, it still counts: %s", file);
            }
            survivors.add(file);
            keptBytes += sizeOf(file);
        }


        if (maxBytes > 0 && keptBytes > maxBytes) {
            survivors.sort(Comparator.comparingLong(TranspileEngine::lastModifiedMillis));
            for (Path file : survivors) {
                if (keptBytes <= maxBytes) {
                    break;
                }
                long size = sizeOf(file);
                if (!deleteQuietly(file)) {
                    ForbricLog.warn("resume file could not be removed, it still counts: %s", file);
                    continue;
                }
                keptBytes -= size;
                expired++;
            }
        }
        if (expired > 0) {
            ForbricLog.info("dropped %d stale resume file(s), %d left", expired, survivors.size());
        } else {
            ForbricLog.info("keeping %d resume file(s), %.1f MB", survivors.size(), keptBytes / 1024.0 / 1024.0);
        }
    }


    private static boolean isResumeFile(Path path) {
        return path.getFileName().toString().endsWith(Http.RESUME_SUFFIX);
    }

    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {

            return Long.MAX_VALUE;
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    private static boolean deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
            return true;
        } catch (IOException e) {

            return false;
        }
    }


    private void writeReport(Result result) {
        Path report = gameDir.resolve("logs").resolve("neoforbric-report.json");
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"neoforbric\": \"").append(buildVersion).append("\",\n");
        sb.append("  \"time\": \"").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\",\n");
        sb.append("  \"gameDir\": \"").append(escape(gameDir.toString())).append("\",\n");
        sb.append("  \"modsDir\": \"").append(escape(modsDir.toString())).append("\",\n");
        sb.append("  \"summary\": \"").append(escape(result.summary())).append("\",\n");
        sb.append("  \"decisions\": [\n");
        for (int i = 0; i < reportEntries.size(); i++) {
            sb.append("    ").append(reportEntries.get(i));
            sb.append(i + 1 < reportEntries.size() ? ",\n" : "\n");
        }
        sb.append("  ],\n");

        sb.append("  \"notes\": [");
        for (int i = 0; i < result.notes().size(); i++) {
            sb.append(i == 0 ? "\n" : ",\n").append("    \"").append(escape(result.notes().get(i))).append("\"");
        }
        sb.append(result.notes().isEmpty() ? "],\n" : "\n  ],\n");
        sb.append("  \"errors\": [");
        for (int i = 0; i < errors.size(); i++) {
            sb.append(i == 0 ? "\n" : ",\n").append("    \"").append(escape(errors.get(i).translationKey())).append("\"");
        }
        sb.append(errors.isEmpty() ? "]\n" : "\n  ]\n");
        sb.append("}\n");
        try {
            Files.createDirectories(report.getParent());
            Files.writeString(report, sb.toString(), StandardCharsets.UTF_8);

        } catch (IOException e) {
            ForbricLog.warn("report write failed: %s", e.toString());
        }
    }

    private void report(ModJarInfo jar, String action, String detail, boolean sameVersion) {
        reportEntry(jar.fileName(), jar.kind().name(), jar.markerSummary(), safe(jar.modId()), safe(jar.version()),
                action, detail, sameVersion, Map.of());
    }


    private void report(String fileName, String action, String detail, boolean sameVersion) {
        reportEntry(fileName, "-", "-", "", "", action, detail, sameVersion, Map.of());
    }


    private void reportReplaced(ModJarInfo jar, Prepared prepared, InstallResult install, boolean sameVersion) {
        ModJarInfo installed = inspectQuietly(install.installedPath());
        Path backup = install.backupPath();
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("installedFile", install.installedPath().getFileName().toString());
        extra.put("installedKind", installed.kind().name());
        extra.put("installedVersion", versionText(installed));
        extra.put("installedModIds", idText(installed));
        extra.put("installedPath", install.installedPath().toString());
        extra.put("backupPath", backup == null ? "" : backup.toString());
        reportEntry(jar.fileName(), jar.kind().name(), jar.markerSummary(), safe(jar.modId()), safe(jar.version()),
                "replaced", describeReplacement(prepared, install, installed, backup), sameVersion, extra);
    }


    private void reportEntry(String fileName, String kind, String markers, String modId, String version, String action,
            String detail, boolean sameVersion, Map<String, String> extra) {
        StringBuilder sb = new StringBuilder("{\"file\": \"" + escape(fileName) + "\", \"kind\": \"" + kind
                + "\", \"markers\": \"" + markers + "\", \"modId\": \"" + escape(modId)
                + "\", \"version\": \"" + escape(version) + "\", \"action\": \"" + action
                + "\", \"sameVersion\": " + sameVersion + ", \"detail\": \"" + escape(detail) + "\"");
        for (Map.Entry<String, String> entry : extra.entrySet()) {
            sb.append(", \"").append(entry.getKey()).append("\": \"").append(escape(entry.getValue())).append('"');
        }
        sb.append('}');
        reportEntries.add(sb.toString());
    }


    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String sha1Of(ModJarInfo jar) {
        if (!Files.isRegularFile(jar.path()) || !Files.isReadable(jar.path())) {
            ForbricLog.warn("sha1 skipped, the file cannot be read any more: %s", jar.fileName());
            return null;
        }
        try {
            return Hashes.sha1(jar.path());
        } catch (IOException e) {
            ForbricLog.warn("sha1 failed (%s): %s", jar.fileName(), e.toString());
            return null;
        }
    }


    private Pending nextCompleted(ExecutorCompletionService<Object> completions, Map<Future<Object>, Pending> pending) {
        try {
            Pending finished = pending.remove(completions.take());
            if (finished != null) {
                return finished;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (Future<Object> future : pending.keySet()) {
            future.cancel(true);
        }
        return pending.remove(pending.keySet().iterator().next());
    }

    private Object await(Future<?> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Failure(null, "task interrupted", false);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            ForbricLog.error("background task failed", cause);
            return new Failure(null, String.valueOf(cause), false);
        } catch (java.util.concurrent.CancellationException e) {
            return new Failure(null, "task cancelled", false);
        }
    }


    private <T> T awaitOr(Future<T> future, T fallback) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            ForbricLog.error("background task failed", cause);
            return fallback;
        }
    }

    private void addError(ModJarInfo jar, String englishMessage) {
        ModLoadingIssue issue = ModLoadingIssue.error(englishMessage);
        if (jar != null) {
            issue = issue.withAffectedPath(jar.path());
        }
        errors.add(issue);
    }


    private ProgressStage beginStage(String id, String label, int totalSteps) {
        ProgressStage stage = new ProgressStage(progress, id, label, totalSteps, config.getBool("debug.progress", false));
        activeStage = stage;
        return stage;
    }


    private void endStage(ProgressStage stage) {
        stage.end();
        if (activeStage == stage) {
            activeStage = null;
        }
    }


    private Http.DownloadScope downloadScope(String taskId, String label) {
        ProgressStage stage = activeStage;
        if (stage == null || !stage.active()) {
            return Http.DownloadScope.NONE;
        }
        return new Http.DownloadScope(stage.id(), taskId, label, event -> {
            if (!stage.active()) {
                return;
            }
            progress.downloadEvent(event);
        });
    }


    private void progressMessage(String englishText) {
        ProgressStage stage = activeStage;
        if (stage != null && stage.active()) {
            stage.message(screen(englishText));
        } else {
            progress.message(screen(englishText));
        }
    }


    private static String screen(String text) {
        return ScreenText.ascii(text);
    }


    private void stall() {
        if (!stalled.compareAndSet(false, true)) {
            return;
        }
        int stallMs = config.getInt("startup.stallMs", 1200);
        if (stallMs <= 0) {
            return;
        }
        progress.message(ForbricLog.TAG + ": Translating mods, do not close the game");
        try {
            Thread.sleep(stallMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


    public static void writeSkippedReport(Path gameDir, String buildVersion, String summary, String note) {
        Path report = gameDir.resolve("logs").resolve("neoforbric-report.json");
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"neoforbric\": \"").append(escape(buildVersion)).append("\",\n");
        sb.append("  \"time\": \"").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\",\n");
        sb.append("  \"gameDir\": \"").append(escape(gameDir.toString())).append("\",\n");
        sb.append("  \"summary\": \"").append(escape(summary)).append("\",\n");
        sb.append("  \"decisions\": [],\n");
        sb.append("  \"notes\": [\n    \"").append(escape(note)).append("\"\n  ],\n");
        sb.append("  \"errors\": []\n");
        sb.append("}\n");
        try {
            Files.createDirectories(report.getParent());
            Files.writeString(report, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ForbricLog.warn("report write failed: %s", e.toString());
        }
    }


    public Path connectorJar() {
        return connectorJar;
    }
}
