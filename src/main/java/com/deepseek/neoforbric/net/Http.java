package com.deepseek.neoforbric.net;

import com.deepseek.neoforbric.core.BuildInfo;
import com.deepseek.neoforbric.core.DownloadEvent;
import com.deepseek.neoforbric.core.Hashes;
import com.deepseek.neoforbric.core.ForbricLog;
import com.deepseek.neoforbric.core.RunStats;
import com.deepseek.neoforbric.core.Timing;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;


public final class Http {

    public static final String RESUME_SUFFIX = ".neoforbric-tmp";

    private static final int ERROR_BODY_LIMIT = 8192;

    private static final long ERROR_BODY_MAX_MILLIS = 3000;

    private static final long BODY_GUARD_TICK_MILLIS = 25;

    private static final long BODY_GUARD_JOIN_MILLIS = 2000;

    private static final long API_BODY_MAX_BYTES = 64L * 1024L * 1024L;

    private final HttpClient client;
    private final int retries;
    private final boolean mirrorsEnabled;
    private final Duration readTimeout;
    private final Duration downloadTimeout;
    private final Duration stallTimeout;
    private final Duration totalDeadline;
    private final Consumer<String> progress;
    private final String userAgent;
    private final RequestLimiter limiter;
    private final SegmentedDownload.Settings segmentedSettings;
    private final SegmentedDownload segmented;

    public Http(int connectTimeoutMs, int readTimeoutMs, int downloadTimeoutMs, int retries, boolean mirrorsEnabled,
            Consumer<String> progress) {
        this(connectTimeoutMs, readTimeoutMs, downloadTimeoutMs, retries, mirrorsEnabled, progress, 45000);
    }

    public Http(int connectTimeoutMs, int readTimeoutMs, int downloadTimeoutMs, int retries, boolean mirrorsEnabled,
            Consumer<String> progress, int stallTimeoutMs) {
        this(connectTimeoutMs, readTimeoutMs, downloadTimeoutMs, retries, mirrorsEnabled, progress, stallTimeoutMs, 0);
    }

    public Http(int connectTimeoutMs, int readTimeoutMs, int downloadTimeoutMs, int retries, boolean mirrorsEnabled,
            Consumer<String> progress, int stallTimeoutMs, int totalDeadlineMs) {
        this(connectTimeoutMs, readTimeoutMs, downloadTimeoutMs, retries, mirrorsEnabled, progress, stallTimeoutMs,
                totalDeadlineMs, SegmentedDownload.Settings.OFF, RequestLimiter.UNLIMITED);
    }

    public Http(int connectTimeoutMs, int readTimeoutMs, int downloadTimeoutMs, int retries, boolean mirrorsEnabled,
            Consumer<String> progress, int stallTimeoutMs, int totalDeadlineMs,
            SegmentedDownload.Settings segmentedSettings, RequestLimiter limiter) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (connectTimeoutMs > 0) {
            builder.connectTimeout(Duration.ofMillis(connectTimeoutMs));
        }
        this.client = builder.build();
        this.readTimeout = readTimeoutMs > 0 ? Duration.ofMillis(readTimeoutMs) : null;
        this.downloadTimeout = downloadTimeoutMs > 0 ? Duration.ofMillis(downloadTimeoutMs) : null;
        this.stallTimeout = stallTimeoutMs > 0 ? Duration.ofMillis(stallTimeoutMs) : null;
        this.totalDeadline = totalDeadlineMs > 0 ? Duration.ofMillis(totalDeadlineMs) : null;
        this.retries = Math.max(1, retries);
        this.mirrorsEnabled = mirrorsEnabled;
        this.progress = progress == null ? message -> {} : progress;
        this.userAgent = BuildInfo.load().userAgent();
        this.limiter = limiter == null ? RequestLimiter.UNLIMITED : limiter;
        this.segmentedSettings = segmentedSettings == null ? SegmentedDownload.Settings.OFF : segmentedSettings;
        this.segmented = this.segmentedSettings.enabled()
                ? new SegmentedDownload(this.client, this.limiter, this.userAgent, this.downloadTimeout,
                        this.stallTimeout) : null;
    }


    public RequestLimiter limiter() {
        return limiter;
    }


    public SegmentedDownload.Settings segmentedSettings() {
        return segmentedSettings;
    }


    public QueryResult<String> postJson(String url, String jsonBody, String what, Map<String, String> extraHeaders) {
        QueryResult.Kind last = null;
        String detail = "";
        for (int attempt = 1; attempt <= retries; attempt++) {
            long startedAt = System.nanoTime();
            long deadlineNanos = readDeadline(startedAt);
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", userAgent)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
                if (readTimeout != null) {
                    builder.timeout(readTimeout);
                }
                if (extraHeaders != null) {
                    extraHeaders.forEach(builder::header);
                }
                HttpResponse<InputStream> response = client.send(builder.build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                QueryResult.Kind kind = classify(status);
                if (kind == QueryResult.Kind.OK) {
                    String body = readBody(response, "POST " + url, deadlineNanos);
                    ForbricLog.debug("  [POST] hit %s (attempt %d, %s)", url, attempt, Timing.elapsed(startedAt));
                    return QueryResult.ok(body);
                }
                ForbricLog.debug("  [POST] HTTP %d <- %s", status, url);
                drain(response, deadlineNanos);
                if (kind == QueryResult.Kind.NOT_FOUND || kind == QueryResult.Kind.CONFIG_ERROR) {
                    ForbricLog.error("  fetch failed: %s <- %s (HTTP %d)", what, url, status);
                    return of(kind, "HTTP " + status);
                }
                last = kind;
                detail = "HTTP " + status;
            } catch (IOException e) {
                last = QueryResult.Kind.TEMPORARY;
                detail = String.valueOf(e);
                ForbricLog.debug("  [POST] request failed (attempt %d): %s <- %s", attempt, e, url);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return QueryResult.temporary("interrupted");
            }
        }
        ForbricLog.error("  fetch failed: %s <- %s (%s)", what, url, detail);
        return of(last, detail);
    }


    public QueryResult<String> fetchString(String officialUrl, String what, boolean retryOn404, boolean expandMirrors) {
        return fetchString(officialUrl, what, retryOn404, expandMirrors, null);
    }


    public QueryResult<String> fetchString(String officialUrl, String what, boolean retryOn404, boolean expandMirrors,
            Map<String, String> extraHeaders) {
        List<Mirrors.Candidate> candidates = expandMirrors
                ? Mirrors.candidates(officialUrl, mirrorsEnabled)
                : List.of(Mirrors.official(officialUrl));
        QueryResult.Kind uncertain = null;
        String uncertainDetail = "";
        boolean sawNotFound = false;
        String notFoundDetail = "";
        for (Mirrors.Candidate candidate : candidates) {
            String url = candidate.url();
            String sourceCn = candidate.mirror() ? "mirror" : "official";
            boolean authAllowed = extraHeaders != null && !extraHeaders.isEmpty() && !candidate.mirror();
            for (int attempt = 1; attempt <= retries; attempt++) {
                long startedAt = System.nanoTime();
                long deadlineNanos = readDeadline(startedAt);
                try {
                    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                            .header("User-Agent", userAgent)
                            .header("Accept", "application/json")
                            .GET();
                    if (readTimeout != null) {
                        builder.timeout(readTimeout);
                    }
                    if (authAllowed) {
                        extraHeaders.forEach(builder::header);
                    }
                    HttpResponse<InputStream> response = client.send(builder.build(),
                            HttpResponse.BodyHandlers.ofInputStream());
                    int status = response.statusCode();
                    QueryResult.Kind kind = classify(status);
                    if (kind == QueryResult.Kind.OK) {
                        String body = readBody(response, sourceCn + " " + url, deadlineNanos);
                        ForbricLog.debug("  [%s] hit %s (attempt %d, %s)", sourceCn, url, attempt,
                                Timing.elapsed(startedAt));
                        return QueryResult.ok(body);
                    }
                    ForbricLog.debug("  [%s] HTTP %d <- %s", sourceCn, status, url);
                    drain(response, deadlineNanos);
                    if (kind == QueryResult.Kind.CONFIG_ERROR) {
                        ForbricLog.error("  fetch rejected: %s <- %s (HTTP %d)", what, url, status);
                        return QueryResult.configError("HTTP " + status + " from " + sourceCn);
                    }
                    if (kind == QueryResult.Kind.NOT_FOUND) {
                        sawNotFound = true;
                        notFoundDetail = "HTTP " + status + " from " + sourceCn;
                        if (!retryOn404) {
                            break;
                        }
                    } else {
                        uncertain = kind;
                        uncertainDetail = "HTTP " + status + " from " + sourceCn;
                    }
                } catch (IOException e) {
                    uncertain = QueryResult.Kind.TEMPORARY;
                    uncertainDetail = e.toString();
                    ForbricLog.debug("  [%s] request failed (attempt %d): %s <- %s", sourceCn, attempt, e, url);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    ForbricLog.debug("  request interrupted: %s", url);
                    return QueryResult.temporary("interrupted");
                }
            }
        }
        if (uncertain != null) {
            ForbricLog.error("  fetch failed: %s (%d sources tried) <- %s (%s)", what, candidates.size(), officialUrl,
                    uncertainDetail);
            return of(uncertain, uncertainDetail);
        }
        if (sawNotFound) {
            ForbricLog.debug("  not found: %s (%d sources tried) <- %s", what, candidates.size(), officialUrl);
            return QueryResult.notFound(notFoundDetail);
        }
        ForbricLog.error("  fetch failed: %s (%d sources tried) <- %s", what, candidates.size(), officialUrl);
        return QueryResult.temporary("no source answered");
    }


    private static QueryResult.Kind classify(int status) {
        if (status / 100 == 2) {
            return QueryResult.Kind.OK;
        }
        if (status == 404 || status == 410) {
            return QueryResult.Kind.NOT_FOUND;
        }
        if (status == 401 || status == 403 || status == 400 || status == 422) {
            return QueryResult.Kind.CONFIG_ERROR;
        }
        return QueryResult.Kind.TEMPORARY;
    }


    private static <T> QueryResult<T> of(QueryResult.Kind kind, String detail) {
        if (kind == null) {
            return QueryResult.temporary(detail);
        }
        return switch (kind) {
            case OK -> QueryResult.temporary(detail);
            case NOT_FOUND -> QueryResult.notFound(detail);
            case CONFIG_ERROR -> QueryResult.configError(detail);
            case TEMPORARY -> QueryResult.temporary(detail);
        };
    }


    public record DownloadScope(String stageId, String taskId, String label, Consumer<DownloadEvent> sink) {

        public static final DownloadScope NONE = new DownloadScope("", "", "", null);


        public void emit(DownloadEvent.State state, int attempt, long received, long total, String detail) {
            if (sink == null) {
                return;
            }
            try {
                sink.accept(new DownloadEvent(stageId, taskId, label, attempt, received, total, state, detail));
            } catch (Throwable ignored) {

            }
        }
    }


    public boolean download(String officialUrl, Path target, String what, String expectedSha1, String version,
            long expectedSize) {
        return download(officialUrl, target, what, expectedSha1, version, expectedSize, DownloadScope.NONE);
    }


    public boolean download(String officialUrl, Path target, String what, String expectedSha1, String version,
            long expectedSize, DownloadScope scope) {
        DownloadScope downloadScope = scope == null ? DownloadScope.NONE : scope;
        int attemptOffset = 0;
        List<Mirrors.Candidate> candidates = Mirrors.candidates(officialUrl, mirrorsEnabled);
        Path temp = resumeFileFor(target, expectedSha1, officialUrl);

        long downloadStartNanos = System.nanoTime();
        long deadlineNanos = totalDeadline == null ? 0 : downloadStartNanos + totalDeadline.toNanos();
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        if (segmented != null && expectedSha1 != null && !expectedSha1.isBlank()
                && (expectedSize <= 0 || segmentedSettings.allows(expectedSize))) {
            long remainingMs = deadlineNanos == 0 ? 0 : (deadlineNanos - System.nanoTime()) / 1_000_000;
            if (deadlineNanos == 0 || remainingMs > 0) {
                attemptOffset = 1;
                try {
                    SegmentedDownload.Result result = segmented.download(officialUrl, target, temp, what,
                            expectedSha1, expectedSize, segmentedSettings, downloadScope, deadlineNanos);
                    if (result == SegmentedDownload.Result.SUCCESS) {
                        RunStats.download(what, version, "segmented", expectedSize,
                                (System.nanoTime() - downloadStartNanos) / 1e9);
                        return true;
                    }
                    if (result == SegmentedDownload.Result.ABORTED || Thread.currentThread().isInterrupted()) {
                        downloadScope.emit(DownloadEvent.State.FAILED, 1, 0, expectedSize, "download cancelled or expired");
                        return false;
                    }
                } catch (RuntimeException t) {
                    ForbricLog.debug("  segmented download error, falling back to a single stream: %s", t.toString());
                }
            }
        }
        int displayAttempt = attemptOffset;
        for (Mirrors.Candidate candidate : candidates) {
            String url = candidate.url();
            String sourceCn = candidate.mirror() ? "mirror" : "official";
            int attempt = 0;
            while (attempt < retries) {
                attempt++;
                displayAttempt++;
                long startedAt = System.nanoTime();
                long existing = sizeOf(temp);
                long remainingMs = deadlineNanos == 0 ? Long.MAX_VALUE
                        : (deadlineNanos - System.nanoTime()) / 1_000_000;
                if (remainingMs <= 0) {
                    ForbricLog.warn("  [%s] download deadline reached after %s, giving up on %s <- %s",
                            sourceCn, Timing.elapsed(downloadStartNanos), what, officialUrl);
                    downloadScope.emit(DownloadEvent.State.FAILED, displayAttempt, sizeOf(temp), expectedSize,
                            "download deadline reached");
                    return false;
                }
                long attemptDeadlineNanos = attemptDeadline(startedAt, remainingMs);
                try {
                    downloadScope.emit(DownloadEvent.State.STARTED, displayAttempt, existing, expectedSize, sourceCn);
                    if (downloadScope.sink() == null) {
                        progress.accept("Translating " + what + " (" + sourceCn + ")");
                    }
                    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                            .header("User-Agent", userAgent)
                            .GET();
                    long requestMs = remainingMs;
                    if (downloadTimeout != null) {
                        requestMs = Math.min(requestMs, downloadTimeout.toMillis());
                    }
                    if (requestMs > 0 && requestMs != Long.MAX_VALUE) {
                        builder.timeout(Duration.ofMillis(requestMs));
                    }
                    if (existing > 0) {
                        builder.header("Range", "bytes=" + existing + "-");
                    }
                    RequestLimiter.Permit permit = limiter.acquire(hostOf(url), requestMs > 0 ? requestMs : 60_000L);
                    if (permit == null) {
                        ForbricLog.debug("  [%s] request budget is busy, retrying <- %s", sourceCn, url);
                        Thread.sleep(150L);
                        continue;
                    }
                    try {
                    long afterQueueMs = remainingMillis(attemptDeadlineNanos, deadlineNanos);
                    if (afterQueueMs > 0 && afterQueueMs != Long.MAX_VALUE) {
                        builder.timeout(Duration.ofMillis(afterQueueMs));
                    }
                    HttpResponse<InputStream> response = client.send(builder.build(),
                            HttpResponse.BodyHandlers.ofInputStream());
                    int status = response.statusCode();
                    if (status == 206) {
                        if (existing <= 0 || !rangeStartsAt(response, existing)) {
                            ForbricLog.debug("  [%s] resume not honored (HTTP 206, %d bytes on disk), "
                                    + "restarting from scratch <- %s", sourceCn, existing, url);
                            drain(response, attemptDeadlineNanos);
                            Files.deleteIfExists(temp);
                            continue;
                        }
                    } else if (status == 200) {
                        if (existing > 0) {
                            existing = 0;
                            Files.deleteIfExists(temp);
                        }
                    } else if (status / 100 == 2) {
                        ForbricLog.debug("  [%s] unexpected HTTP %d for a file download, retry <- %s",
                                sourceCn, status, url);
                        drain(response, attemptDeadlineNanos);
                        Files.deleteIfExists(temp);
                        continue;
                    } else {
                        ForbricLog.debug("  [%s] download HTTP %d <- %s", sourceCn, status, url);
                        drain(response, attemptDeadlineNanos);
                        if (isGone(status)) {
                            if (existing > 0) {
                                ForbricLog.debug("  [%s] Range rejected (HTTP %d), dropped %d bytes, "
                                        + "retry from start <- %s", sourceCn, status, existing, url);
                                Files.deleteIfExists(temp);
                                attempt--;
                            } else {
                                Files.deleteIfExists(temp);
                                break;
                            }
                        } else {
                            ForbricLog.debug("  [%s] transient failure, keep %d bytes for resume <- %s",
                                    sourceCn, existing, url);
                        }
                        continue;
                    }


                    try (InputStream body = new TransferWatchdog(response.body(), stallTimeout, attemptDeadlineNanos,
                            deadlineNanos, expectedSize, existing, what, sourceCn, url,
                            downloadScope.sink() == null ? progress : null, displayAttempt,
                            downloadScope);
                            var out = Files.newOutputStream(temp, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                        body.transferTo(out);
                    }
                    long size = Files.size(temp);
                    if (size == 0) {
                        ForbricLog.debug("  [%s] empty file, retry <- %s", sourceCn, url);
                        downloadScope.emit(DownloadEvent.State.SKIPPED, displayAttempt, 0, expectedSize, "empty body");
                        Files.deleteIfExists(temp);
                        continue;
                    }


                    if (expectedSize > 0 && size != expectedSize) {
                        ForbricLog.debug("  [%s] size mismatch (expected %d, actual %d), discard and retry <- %s",
                                sourceCn, expectedSize, size, url);
                        downloadScope.emit(DownloadEvent.State.SKIPPED, displayAttempt, size, expectedSize,
                                "size mismatch, retry from scratch");
                        Files.deleteIfExists(temp);
                        continue;
                    }
                    if (expectedSha1 != null && !expectedSha1.isBlank()) {
                        String actual = Hashes.sha1(temp);
                        if (!actual.equalsIgnoreCase(expectedSha1)) {
                            ForbricLog.debug("  [%s] SHA1 mismatch (expected %s, actual %s), discard and retry", sourceCn,
                                    expectedSha1, actual);
                            downloadScope.emit(DownloadEvent.State.SKIPPED, displayAttempt, size, expectedSize,
                                    "sha1 mismatch, retry from scratch");
                            Files.deleteIfExists(temp);
                            continue;
                        }
                    }
                    requireActive(attemptDeadlineNanos, deadlineNanos);
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                    ForbricLog.info("  [%s] downloaded %s (%d bytes, %s) <- %s", sourceCn, target.getFileName(), size,
                            Timing.elapsedWithSpeed(size, startedAt), url);

                    RunStats.download(what, version, sourceCn, size, (System.nanoTime() - startedAt) / 1e9);
                    downloadScope.emit(DownloadEvent.State.DONE, displayAttempt, size, size, sourceCn);
                    return true;
                    } finally {
                        permit.close();
                    }
                } catch (IOException e) {
                    if (Thread.currentThread().isInterrupted()) {
                        downloadScope.emit(DownloadEvent.State.FAILED, displayAttempt, sizeOf(temp), expectedSize, "interrupted");
                        return false;
                    }
                    ForbricLog.debug("  [%s] download failed (attempt %d, %s, %d bytes so far, resume next): %s <- %s",
                            sourceCn, attempt, Timing.elapsed(startedAt), existing, e, url);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    downloadScope.emit(DownloadEvent.State.FAILED, displayAttempt, sizeOf(temp), expectedSize, "interrupted");
                    return false;
                }
            }


            if (expectedSha1 == null || expectedSha1.isBlank()) {
                deleteQuietly(temp);
            } else {
                ForbricLog.debug("  keep %d bytes for resume on source switch",
                        sizeOf(temp));
            }
        }
        ForbricLog.error("  download failed: %s (%d sources tried): %s", what, candidates.size(), officialUrl);
        downloadScope.emit(DownloadEvent.State.FAILED, displayAttempt, sizeOf(temp), expectedSize,
                "all " + candidates.size() + " source(s) failed");
        return false;
    }


    private long attemptDeadline(long startedAtNanos, long remainingMs) {
        if (downloadTimeout == null) {
            return remainingMs == Long.MAX_VALUE ? 0 : startedAtNanos + remainingMs * 1_000_000L;
        }
        long millis = Math.min(downloadTimeout.toMillis(), remainingMs);
        if (millis <= 0 || millis == Long.MAX_VALUE) {
            return 0;
        }
        return startedAtNanos + millis * 1_000_000L;
    }


    private static String hostOf(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return "";
            }
            return host + ":" + (uri.getPort() > 0 ? uri.getPort() : -1);
        } catch (RuntimeException e) {
            return "";
        }
    }


    private static boolean rangeStartsAt(HttpResponse<?> response, long expectedStart) {
        return response.headers().firstValue("Content-Range")
                .map(value -> value.trim().toLowerCase(Locale.ROOT).startsWith("bytes " + expectedStart + "-"))
                .orElse(false);
    }


    private static Path resumeFileFor(Path target, String expectedSha1, String url) {
        String key = (expectedSha1 != null && !expectedSha1.isBlank()) ? expectedSha1 : url;
        return target.resolveSibling(
                target.getFileName() + "." + Integer.toHexString(key.hashCode()) + RESUME_SUFFIX);
    }


    static final class TransferWatchdog extends FilterInputStream {

        private static final long TICK_MILLIS = 25;

        private static final long LOG_EVERY_MILLIS = 15000;

        private final long stallMillis;

        private final long attemptDeadlineNanos;

        private final long fileDeadlineNanos;
        private final long expectedSize;

        private final long alreadyOnDisk;
        private final String what;
        private final String sourceCn;
        private final String url;
        private final Consumer<String> progress;
        private final int attempt;
        private final DownloadScope scope;
        private final Thread thread;
        private final AtomicLong lastProgress = new AtomicLong(System.nanoTime());

        private final AtomicLong transferred = new AtomicLong();
        private volatile boolean closed;
        private final Thread owner = Thread.currentThread();
        private volatile boolean expired;

        TransferWatchdog(InputStream in, Duration stallTimeout, long attemptDeadlineNanos, long fileDeadlineNanos,
                long expectedSize, long alreadyOnDisk, String what, String sourceCn, String url,
                Consumer<String> progress, int attempt, DownloadScope scope) {
            super(in);
            this.stallMillis = stallTimeout == null ? 0 : stallTimeout.toMillis();
            this.attemptDeadlineNanos = attemptDeadlineNanos;
            this.fileDeadlineNanos = fileDeadlineNanos;
            this.expectedSize = expectedSize;
            this.alreadyOnDisk = alreadyOnDisk;
            this.what = what;
            this.sourceCn = sourceCn;
            this.url = url;
            this.progress = progress;
            this.attempt = attempt;
            this.scope = scope == null ? DownloadScope.NONE : scope;
            this.thread = new Thread(this::watch, "neoforbric-transfer-watchdog");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private void watch() {
            long startedAt = System.nanoTime();
            long lastLog = startedAt;
            while (!closed) {
                try {
                    Thread.sleep(TICK_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (closed) {
                    return;
                }
                long now = System.nanoTime();
                if (owner.isInterrupted()) {
                    expired = true;
                    closeQuietly();
                    return;
                }


                if (fileDeadlineNanos > 0 && now > fileDeadlineNanos) {
                    expired = true;
                    ForbricLog.warn("  [%s] total download deadline reached (%s, %s of %s), aborting <- %s",
                            sourceCn, what, human(done()), human(expectedSize), url);
                    closeQuietly();
                    return;
                }

                if (attemptDeadlineNanos > 0 && now > attemptDeadlineNanos) {
                    expired = true;
                    ForbricLog.debug("  [%s] single request deadline reached (%s, %s of %s), abort to resume <- %s",
                            sourceCn, what, human(done()), human(expectedSize), url);
                    closeQuietly();
                    return;
                }

                reportProgress();
                if (now - lastLog >= LOG_EVERY_MILLIS * 1_000_000L) {
                    lastLog = now;
                    ForbricLog.info("  [%s] still downloading %s: %s of %s (%s/s, %s elapsed)", sourceCn, what,
                            human(done()), human(expectedSize), human(speed(now - startedAt)),
                            Timing.elapsed(startedAt));
                }


                if (stallMillis <= 0) {
                    continue;
                }
                long idle = (now - lastProgress.get()) / 1_000_000;
                if (idle < stallMillis) {
                    continue;
                }
                ForbricLog.debug("  [%s] stalled %d ms (%s), abort to resume with Range <- %s",
                        sourceCn, idle, what, url);

                expired = true;
                closeQuietly();
                return;
            }
        }


        private long done() {
            return alreadyOnDisk + transferred.get();
        }

        private long speed(long elapsedNanos) {
            double seconds = Math.max(0.5, elapsedNanos / 1e9);
            return (long) (transferred.get() / seconds);
        }


        private void reportProgress() {
            long done = done();
            scope.emit(DownloadEvent.State.PROGRESS, attempt, done, expectedSize, sourceCn);
            if (expectedSize <= 0) {
                return;
            }
            int percent = (int) Math.min(100, done * 100 / expectedSize);
            if (progress != null) {
                progress.accept(String.format(Locale.ROOT, "Translating %s  %d%%", what, percent));
            }
        }

        private void closeQuietly() {
            try {
                super.close();
            } catch (IOException ignored) {

            }
        }

        @Override
        public int read() throws IOException {
            checkActive();
            int value = super.read();
            checkActive();
            if (value >= 0) {
                lastProgress.set(System.nanoTime());
                transferred.incrementAndGet();
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            checkActive();
            int count = super.read(b, off, len);
            checkActive();
            if (count > 0) {
                lastProgress.set(System.nanoTime());
                transferred.addAndGet(count);
            }
            return count;
        }

        private void checkActive() throws IOException {
            requireActive(attemptDeadlineNanos, fileDeadlineNanos);
            if (expired) {
                throw new IOException("download body stopped after cancellation, timeout or stalled transfer");
            }
        }

        @Override
        public void close() throws IOException {
            closed = true;
            thread.interrupt();
            try {
                super.close();
            } finally {
                try {
                    thread.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (thread.isAlive()) {
                    ForbricLog.warn("  the transfer watchdog for %s did not stop in time", what);
                }
            }
        }
    }


    private static String human(long bytes) {
        if (bytes <= 0) {
            return "?";
        }
        if (bytes >= 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1fMB", bytes / 1024.0 / 1024.0);
        }
        return String.format(Locale.ROOT, "%.0fKB", bytes / 1024.0);
    }


    static void drain(HttpResponse<InputStream> response, long callerDeadlineNanos) {
        InputStream body = response.body();
        if (body == null) {
            return;
        }
        long deadline = System.nanoTime() + ERROR_BODY_MAX_MILLIS * 1_000_000L;
        if (callerDeadlineNanos > 0 && callerDeadlineNanos < deadline) {
            deadline = callerDeadlineNanos;
        }
        BodyGuard guard = new BodyGuard(body, deadline, "the error response body");
        try (InputStream in = guard) {
            byte[] buffer = new byte[1024];
            int total = 0;
            while (total < ERROR_BODY_LIMIT) {
                int read = in.read(buffer, 0, Math.min(buffer.length, ERROR_BODY_LIMIT - total));
                if (read <= 0) {
                    return;
                }
                total += read;
            }
        } catch (IOException e) {
            ForbricLog.debug("  the error response body could not be read to the end: %s", e.toString());
        } finally {
            guard.stop();
        }
    }


    private long readDeadline(long startedAtNanos) {
        return readTimeout == null ? 0 : startedAtNanos + readTimeout.toMillis() * 1_000_000L;
    }


    private String readBody(HttpResponse<InputStream> response, String what, long deadlineNanos) throws IOException {
        InputStream body = response.body();
        if (body == null) {
            return "";
        }
        BodyGuard guard = new BodyGuard(body, deadlineNanos, "the response body of " + what);
        try (InputStream in = guard) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > API_BODY_MAX_BYTES) {
                    throw new IOException("the response of " + what + " is larger than " + API_BODY_MAX_BYTES
                            + " bytes");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        } finally {
            guard.stop();
        }
    }


    private static final class BodyGuard extends FilterInputStream {

        private final Thread thread;

        private final long deadlineNanos;

        private final String what;

        private volatile boolean stopped;
        private final Thread owner = Thread.currentThread();

        BodyGuard(InputStream in, long deadlineNanos, String what) {
            super(in);
            this.deadlineNanos = deadlineNanos;
            this.what = what;
            if (deadlineNanos <= 0) {
                this.thread = null;
                return;
            }
            this.thread = new Thread(this::watch, "neoforbric-body-guard");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private void watch() {
            while (!stopped) {
                try {
                    Thread.sleep(BODY_GUARD_TICK_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (stopped) {
                    return;
                }
                if (owner.isInterrupted() || System.nanoTime() >= deadlineNanos) {
                    ForbricLog.debug("  %s was still open after its deadline, closing it", what);
                    closeUnderlying();
                    return;
                }
            }
        }

        private void closeUnderlying() {
            try {
                super.close();
            } catch (IOException ignored) {

            }
        }

        void stop() {
            stopped = true;
            if (thread != null) {
                thread.interrupt();
            }
        }

        private void joinGuard() {
            if (thread == null) {
                return;
            }
            try {
                thread.join(BODY_GUARD_JOIN_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                ForbricLog.warn("  the body guard for %s did not stop in time", what);
            }
        }

        @Override
        public void close() throws IOException {
            stop();
            try {
                super.close();
            } finally {
                joinGuard();
            }
        }
    }

    static void requireActive(long attemptDeadline, long fileDeadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.io.InterruptedIOException("download cancelled");
        }
        long now = System.nanoTime();
        if ((attemptDeadline > 0 && now >= attemptDeadline) || (fileDeadline > 0 && now >= fileDeadline)) {
            throw new java.net.SocketTimeoutException("download deadline reached");
        }
    }

    static long remainingMillis(long attemptDeadline, long fileDeadline) throws IOException {
        requireActive(attemptDeadline, fileDeadline);
        long deadline = attemptDeadline == 0 ? fileDeadline
                : fileDeadline == 0 ? attemptDeadline : Math.min(attemptDeadline, fileDeadline);
        return deadline == 0 ? Long.MAX_VALUE : Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
    }

    private static long sizeOf(Path path) {
        try {
            return Files.exists(path) ? Files.size(path) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {

        }
    }


    private static boolean isGone(int status) {
        return status == 404 || status == 403 || status == 410 || status == 416;
    }
}
