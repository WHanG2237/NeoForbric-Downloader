package com.deepseek.neoforbric.net;

import com.deepseek.neoforbric.core.DownloadEvent;
import com.deepseek.neoforbric.core.ForbricLog;
import com.deepseek.neoforbric.core.Hashes;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SegmentedDownload {
    public record Settings(boolean enabled, int maxSegments, long thresholdBytes, long minSegmentBytes) {
        public static final Settings OFF = new Settings(false, 0, Long.MAX_VALUE, Long.MAX_VALUE);
        public boolean allows(long totalBytes) {
            return enabled && maxSegments >= 2 && totalBytes >= thresholdBytes
                    && totalBytes / Math.max(1L, minSegmentBytes) >= 2;
        }
    }

    public enum Result { SUCCESS, FALLBACK, ABORTED }
    record Range(long start, long end) { long length() { return end - start + 1; } }
    private record ContentRange(long start, long end, long total) {}
    private record Probe(long total, String etag) {}
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final AtomicLong THREAD_IDS = new AtomicLong();
    private final HttpClient client;
    private final RequestLimiter limiter;
    private final String userAgent;
    private final Duration attemptTimeout;
    private final Duration stallTimeout;

    public SegmentedDownload(HttpClient client, RequestLimiter limiter, String userAgent,
            Duration attemptTimeout, Duration stallTimeout) {
        this.client = client;
        this.limiter = limiter;
        this.userAgent = userAgent;
        this.attemptTimeout = attemptTimeout;
        this.stallTimeout = stallTimeout;
    }

    private HttpResponse<InputStream> send(String url, Range range, String etag, long attemptDeadline,
            long deadline, RequestLimiter.Permit permit) throws IOException, InterruptedException {
        long remaining = Http.remainingMillis(attemptDeadline, deadline);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", userAgent)
                .header("Accept-Encoding", "identity").header("Range", "bytes=" + range.start() + "-" + range.end()).GET();
        if (remaining != Long.MAX_VALUE) { builder.timeout(Duration.ofMillis(remaining)); }
        if (etag != null) { builder.header("If-Range", etag); }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private RequestLimiter.Permit acquire(String url, long attemptDeadline, long deadline)
            throws IOException, InterruptedException {
        long wait = Http.remainingMillis(attemptDeadline, deadline);
        RequestLimiter.Permit permit = limiter.acquire(hostOf(url), wait);
        if (permit == null) { throw new IOException("request budget unavailable before deadline"); }
        return permit;
    }

    private Probe probe(String url, String what, long attemptDeadline, long deadline)
            throws IOException, InterruptedException {
        try (RequestLimiter.Permit permit = acquire(url, attemptDeadline, deadline)) {
            HttpResponse<InputStream> response = send(url, new Range(0, 0), null, attemptDeadline, deadline, permit);
            ContentRange span = parseContentRange(response.headers().firstValue("Content-Range").orElse(""));
            if (response.statusCode() != 206 || !identity(response) || span == null || span.start() != 0 || span.end() != 0) {
                Http.drain(response, earliest(attemptDeadline, deadline));
                throw new IOException("server did not provide a valid identity bytes 0-0 range probe");
            }
            try (InputStream body = guarded(response, attemptDeadline, deadline, 1, what, url)) {
                if (body.read() < 0 || body.read() >= 0) { throw new IOException("range probe body length is not 1"); }
            }
            String etag = response.headers().firstValue("ETag").orElse(null);
            if (etag != null && (!etag.startsWith("\"") || !etag.endsWith("\""))) {
                throw new IOException("range probe did not provide a strong ETag");
            }
            return new Probe(span.total(), etag);
        }
    }

    private InputStream guarded(HttpResponse<InputStream> response, long attemptDeadline, long deadline,
            long size, String what, String url) {
        return new Http.TransferWatchdog(response.body(), stallTimeout, attemptDeadline, deadline, size, 0,
                what, "segmented", url, null, 1, Http.DownloadScope.NONE);
    }

    public Result download(String url, Path target, Path temp, String what, String expectedSha1, long expectedSize,
            Settings settings, Http.DownloadScope scope, long deadline) {
        if (!settings.enabled() || (expectedSize > 0 && !settings.allows(expectedSize))) { return Result.FALLBACK; }
        long attemptDeadline = attemptTimeout == null ? 0 : System.nanoTime() + attemptTimeout.toNanos();
        ExecutorService workers = null;
        Set<InputStream> streams = ConcurrentHashMap.newKeySet();
        AtomicBoolean aborted = new AtomicBoolean();
        List<Future<Boolean>> futures = new ArrayList<>();
        Path directory = temp.resolveSibling(temp.getFileName() + "." + UUID.randomUUID() + ".segments");
        Result result = Result.FALLBACK;
        try {
            Probe probe = probe(url, what, attemptDeadline, deadline);
            long total = probe.total();
            if (expectedSize > 0 && expectedSize != total) { throw new IOException("range probe size mismatch"); }
            if (settings.allows(total)) {
                List<Range> ranges = plan(total, settings);
                Files.createDirectories(directory);
                workers = Executors.newFixedThreadPool(ranges.size(), task -> {
                    Thread thread = new Thread(task, "NeoForbric-Segment-" + THREAD_IDS.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
                ExecutorCompletionService<Boolean> completions = new ExecutorCompletionService<>(workers);
                AtomicLong received = new AtomicLong();
                List<Path> parts = new ArrayList<>();
                scope.emit(DownloadEvent.State.STARTED, 1, 0, total, "segmented");
                for (int i = 0; i < ranges.size(); i++) {
                    Path part = directory.resolve("part-" + i);
                    parts.add(part);
                    Range range = ranges.get(i);
                    futures.add(completions.submit(() -> fetch(url, range, part, probe, what, scope, received,
                            aborted, streams, attemptDeadline, deadline)));
                }
                for (int i = 0; i < ranges.size();) {
                    Http.requireActive(attemptDeadline, deadline);
                    Future<Boolean> finished = completions.poll(25, TimeUnit.MILLISECONDS);
                    if (finished != null) {
                        if (!finished.get()) { throw new IOException("a segment failed"); }
                        i++;
                    }
                }
                Path merged = directory.resolve("merged.tmp");
                try (var out = Files.newOutputStream(merged)) {
                    byte[] buffer = new byte[16 * 1024];
                    for (Path part : parts) {
                        try (InputStream in = Files.newInputStream(part)) {
                            int count;
                            while ((count = in.read(buffer)) >= 0) {
                                Http.requireActive(attemptDeadline, deadline);
                                out.write(buffer, 0, count);
                            }
                        }
                    }
                }
                Http.requireActive(attemptDeadline, deadline);
                if (Files.size(merged) != total || !Hashes.sha1(merged).equalsIgnoreCase(expectedSha1)) {
                    throw new IOException("merged segment length or hash mismatch");
                }
                Http.requireActive(attemptDeadline, deadline);
                Files.move(merged, target, StandardCopyOption.REPLACE_EXISTING);
                scope.emit(DownloadEvent.State.DONE, 1, total, total, "segmented/" + ranges.size());
                ForbricLog.info("  [%s] downloaded %s in %d segment(s) (%d bytes)", what,
                        target.getFileName(), ranges.size(), total);
                result = Result.SUCCESS;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = Result.ABORTED;
        } catch (Exception e) {
            ForbricLog.debug("  [%s] segmented download stopped: %s", what, e.toString());
            if (Thread.currentThread().isInterrupted() || (deadline > 0 && System.nanoTime() >= deadline)) {
                result = Result.ABORTED;
            }
        } finally {
            aborted.set(true);
            for (Future<Boolean> future : futures) { future.cancel(true); }
            for (InputStream body : streams) {
                try { body.close(); } catch (IOException e) { ForbricLog.debug("segment close failed: %s", e); }
            }
            boolean interrupted = Thread.interrupted();
            boolean stopped = workers == null;
            try {
                if (workers != null) {
                    workers.shutdownNow();
                    stopped = workers.awaitTermination(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                interrupted = true;
            } finally {
                if (interrupted) { Thread.currentThread().interrupt(); result = Result.ABORTED; }
            }
            if (stopped) {
                try {
                    if (Files.isDirectory(directory)) {
                        try (var files = Files.list(directory)) {
                            for (Path file : files.toList()) { Files.deleteIfExists(file); }
                        }
                        Files.deleteIfExists(directory);
                    }
                } catch (IOException e) { ForbricLog.warn("segment cleanup failed for %s: %s", directory, e); }
            } else {
                ForbricLog.error("segment workers did not stop; retained files in %s", directory);
                result = Result.ABORTED;
            }
        }
        return result;
    }

    private boolean fetch(String url, Range range, Path part, Probe probe, String what, Http.DownloadScope scope,
            AtomicLong received, AtomicBoolean aborted, Set<InputStream> streams, long attemptDeadline, long deadline) {
        try (RequestLimiter.Permit permit = acquire(url, attemptDeadline, deadline)) {
            if (aborted.get()) { return false; }
            HttpResponse<InputStream> response = send(url, range, probe.etag(), attemptDeadline, deadline, permit);
            ContentRange actual = parseContentRange(response.headers().firstValue("Content-Range").orElse(""));
            if (response.statusCode() != 206 || !identity(response) || actual == null
                    || actual.start() != range.start() || actual.end() != range.end() || actual.total() != probe.total()) {
                Http.drain(response, earliest(attemptDeadline, deadline));
                return false;
            }
            InputStream body = guarded(response, attemptDeadline, deadline, range.length(), what, url);
            streams.add(body);
            try (body; var out = Files.newOutputStream(part)) {
                if (aborted.get()) { return false; }
                byte[] buffer = new byte[16 * 1024];
                long written = 0;
                int count;
                while ((count = body.read(buffer)) >= 0) {
                    if (aborted.get()) { return false; }
                    written += count;
                    if (written > range.length()) { return false; }
                    out.write(buffer, 0, count);
                    scope.emit(DownloadEvent.State.PROGRESS, 1, received.addAndGet(count), probe.total(), "segmented");
                }
                return written == range.length();
            } finally { streams.remove(body); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            ForbricLog.debug("  [%s] segment failed: %s", what, e.toString());
            return false;
        }
    }

    static List<Range> plan(long total, Settings settings) {
        int segments = settings.maxSegments();
        long minimum = Math.max(1, settings.minSegmentBytes());
        while (segments > 2 && total / segments < minimum) { segments--; }
        if (segments < 2 || total / segments < 1) { return List.of(); }
        List<Range> ranges = new ArrayList<>();
        long base = total / segments;
        long remainder = total % segments;
        long cursor = 0;
        for (int i = 0; i < segments; i++) {
            long length = base + (i < remainder ? 1 : 0);
            ranges.add(new Range(cursor, cursor + length - 1));
            cursor += length;
        }
        return ranges;
    }

    private static ContentRange parseContentRange(String value) {
        Matcher match = CONTENT_RANGE.matcher(value.trim());
        if (!match.matches()) { return null; }
        try {
            long start = Long.parseLong(match.group(1));
            long end = Long.parseLong(match.group(2));
            long total = Long.parseLong(match.group(3));
            return start <= end && end < total ? new ContentRange(start, end, total) : null;
        } catch (NumberFormatException e) { return null; }
    }

    static long totalFromContentRange(String value) {
        ContentRange parsed = value == null ? null : parseContentRange(value);
        return parsed == null ? -1 : parsed.total();
    }

    private static boolean identity(HttpResponse<?> response) {
        return response.headers().allValues("Content-Encoding").stream().allMatch(value -> value.equalsIgnoreCase("identity"));
    }

    private static long earliest(long first, long second) {
        return first == 0 ? second : second == 0 ? first : Math.min(first, second);
    }

    private static String hostOf(String url) {
        URI uri = URI.create(url);
        return uri.getHost() + ":" + (uri.getPort() > 0 ? uri.getPort() : -1);
    }

    static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
