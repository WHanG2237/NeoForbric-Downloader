import com.deepseek.neoforbric.net.*;
import com.deepseek.neoforbric.core.DownloadEvent;
import com.deepseek.neoforbric.core.ForbricLog;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayOutputStream;

public final class DownloadRegression {
    static final byte[] DATA = new byte[8192];
    static int pass, fail;
    static { new Random(37).nextBytes(DATA); }
    static void check(String name, boolean ok, Object detail) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name + " " + detail);
    }
    static String sha(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
    }
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of("sandbox/regression"));
        limiter();
        scenario("normal", 5000, 5000, 500, true);
        scenario("error-probe", 1000, 1000, 500, false);
        scenario("slow-body", 1000, 1000, 500, false);
        scenario("slow-body", 0, 500, 500, false);
        scenario("error-unlimited", 0, 0, 0, true);
        scenario("bad-total", 5000, 5000, 500, true);
        scenario("bad-probe", 5000, 5000, 500, true);
        scenario("gzip", 5000, 5000, 500, true);
        scenario("weak-etag", 5000, 5000, 500, true);
        scenario("changed", 5000, 5000, 500, false);
        scenario("first-fails", 5000, 5000, 500, true);
        scenario("slow-error-segment", 1000, 1000, 0, false);
        queueBudget();
        cancellation();
        summaryMetrics();
        System.out.println("DOWNLOAD_REGRESSION PASSED=" + pass + " FAILED=" + fail);
        if (fail != 0) System.exit(1);
    }
    static void limiter() throws Exception {
        RequestLimiter limiter = new RequestLimiter(2, 1);
        for (int i=0;i<20;i++) {
            var first=limiter.acquire("a",1000);
            Thread waiter=new Thread(()->{try(var ignored=limiter.acquire("a",10000)){}catch(InterruptedException e){}});
            waiter.start();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            while(waiter.getState()!=Thread.State.TIMED_WAITING && System.nanoTime()<until) Thread.sleep(2);
            try(var other=limiter.acquire("b",100)) {
                check("other-host-uses-free-capacity-"+i,other!=null,limiter.availablePermits());
            }
            waiter.interrupt();waiter.join(1000);first.close();
            check("cancel-releases-capacity-"+i,limiter.availablePermits()==2&&!waiter.isAlive(),limiter.availablePermits());
        }
        var held=limiter.acquire("a",100);
        long start=System.nanoTime();
        var denied=limiter.acquire("a",100);
        long ms=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        check("one-wait-budget",denied==null&&ms>=80&&ms<300,ms);
        held.close();
        var permit=limiter.acquire("a",100);
        List<Thread> closers=new ArrayList<>();
        for(int i=0;i<16;i++){Thread t=new Thread(permit::close);closers.add(t);t.start();}
        for(Thread t:closers)t.join();
        check("concurrent-permit-close",limiter.availablePermits()==2,limiter.availablePermits());
    }
    static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final ExecutorService handlers;
        final AtomicInteger requests=new AtomicInteger();
        final AtomicInteger ifRanges=new AtomicInteger();
        final CountDownLatch segmentHeaders=new CountDownLatch(2);
        final String mode;
        Fixture(String mode) throws Exception {
            this.mode=mode;
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            handlers=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"RegressionServer");t.setDaemon(true);return t;});
            server.setExecutor(handlers);
            server.createContext("/file",exchange->{
                requests.incrementAndGet();
                try(exchange){
                    String range=exchange.getRequestHeaders().getFirst("Range");
                    if(exchange.getRequestHeaders().getFirst("If-Range")!=null)ifRanges.incrementAndGet();
                    boolean probe="bytes=0-0".equals(range);
                    if(probe&&(mode.equals("error-probe")||mode.equals("error-unlimited"))){
                        exchange.sendResponseHeaders(503,0);exchange.getResponseBody().flush();Thread.sleep(5000);exchange.getResponseBody().write(1);return;
                    }
                    if(range==null){
                        byte[] content=DATA.clone();
                        if(mode.equals("changed"))content[0]^=1;
                        exchange.sendResponseHeaders(200,content.length);exchange.getResponseBody().flush();
                        if(mode.equals("slow-body"))Thread.sleep(4000);
                        exchange.getResponseBody().write(content);return;
                    }
                    int dash=range.indexOf('-');int first=Integer.parseInt(range.substring(6,dash));int last=Integer.parseInt(range.substring(dash+1));
                    if(probe&&mode.equals("bad-probe"))last=1;
                    if(!probe&&mode.equals("slow-error-segment")){
                        exchange.sendResponseHeaders(429,0);exchange.getResponseBody().flush();Thread.sleep(5000);exchange.getResponseBody().write(1);return;
                    }
                    if(!probe&&mode.equals("first-fails")&&first==0){exchange.sendResponseHeaders(500,-1);return;}
                    if(!probe&&mode.equals("changed")){
                        byte[] changed=DATA.clone();changed[0]^=1;exchange.sendResponseHeaders(200,changed.length);exchange.getResponseBody().write(changed);return;
                    }
                    exchange.getResponseHeaders().set("Content-Range","bytes "+first+"-"+last+"/"+(!probe&&mode.equals("bad-total")?"garbage":DATA.length));
                    exchange.getResponseHeaders().set("ETag",mode.equals("weak-etag")?"W/\"review\"":"\"review\"");
                    byte[] content=Arrays.copyOfRange(DATA,first,last+1);
                    if(!probe&&mode.equals("gzip")){
                        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                        try(GZIPOutputStream gzip=new GZIPOutputStream(bytes)){gzip.write(content);}
                        content=bytes.toByteArray();exchange.getResponseHeaders().set("Content-Encoding","gzip");
                    }
                    exchange.sendResponseHeaders(206,content.length);exchange.getResponseBody().flush();
                    if(!probe){
                        segmentHeaders.countDown();
                        if(mode.equals("slow-body")||mode.equals("cancel")||mode.equals("first-fails"))Thread.sleep(4000);
                    }
                    exchange.getResponseBody().write(content);
                }catch(Exception ignored){}
            });
            server.start();
        }
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/file";}
        public void close(){server.stop(0);handlers.shutdownNow();}
    }
    static Http http(int total,int attempt,int idle,RequestLimiter limiter){
        return new Http(1000,1000,attempt,1,false,null,idle,total,new SegmentedDownload.Settings(true,2,1,4096),limiter);
    }
    static Path target(String name) throws Exception {
        return Files.createTempDirectory(Path.of("sandbox/regression"),name).resolve("target.jar");
    }

    // ------------------------------------------------ download summary metrics (0.0.10 C10-03)

    /**
     * The download summary must report the bytes that were really transferred, the mode, the source
     * with its authority, and the basis of the speed. Every server here is local: the stall fixtures
     * answer 200/206, write a prefix and then stop sending without closing, so the watchdog has to
     * cut the transfer.
     */
    static void summaryMetrics() throws Exception {
        Path logDir = Files.createDirectories(Path.of("sandbox/regression/metrics-logs"));
        Files.deleteIfExists(logDir.resolve("logs").resolve("neoforbric.log"));
        ForbricLog.attach(logDir);
        Path logFile = ForbricLog.logFile();
        try (MetricsFixture fixture = new MetricsFixture()) {
            // N7: a stalled single stream. The speed numerator must be what the network delivered.
            String url = fixture.url("/stall");
            Path target = target("metrics-stall");
            Http stalling = new Http(2000, 5000, 30000, 1, false, null, 1000, 30000,
                    SegmentedDownload.Settings.OFF, RequestLimiter.UNLIMITED, false);
            boolean ok = stalling.download(url, target, "M7 stall", sha(DATA), "1", DATA.length);
            Map<String, String> f = summary(logFile, "M7 stall");
            System.out.println("  N7 summary: " + f);
            check("N7-failed", !ok && "FAILED".equals(f.get("result")), "ok=" + ok + " " + f);
            check("N7-received-bytes", num(f, "receivedBytes") >= 4096,
                    "receivedBytes=" + f.get("receivedBytes"));
            check("N7-retained-recorded", f.containsKey("retainedBytes") && num(f, "retainedBytes") >= 0,
                    "retainedBytes=" + f.get("retainedBytes"));
            check("N7-basis", "network-received-bytes/whole-operation".equals(f.get("basis")), f.get("basis"));
            check("N7-bytes-is-the-received-total",
                    num(f, "bytes") == num(f, "receivedBytes")
                            && num(f, "receivedBytes") == num(f, "streamBytes") + num(f, "segmentBytes"),
                    "bytes=" + f.get("bytes") + " received=" + f.get("receivedBytes")
                            + " stream=" + f.get("streamBytes") + " segments=" + f.get("segmentBytes"));
            // The tolerance also covers the 3-decimal rounding of elapsedSeconds (see T3 in EngineProbe).
            check("N7-speed-is-received-over-elapsed",
                    num(f, "averageBytesPerSecond") > 0
                            && Math.abs(num(f, "averageBytesPerSecond")
                                    - num(f, "receivedBytes") / num(f, "elapsedSeconds"))
                                    <= num(f, "receivedBytes") / num(f, "elapsedSeconds")
                                            * (0.05 + 0.0005 / num(f, "elapsedSeconds")),
                    "average=" + f.get("averageBytesPerSecond") + " received=" + f.get("receivedBytes")
                            + " elapsed=" + f.get("elapsedSeconds"));
            check("N7-source-is-not-the-mode", "official".equals(sourceName(f))
                            && f.get("source").contains("@") && !"segmented".equals(f.get("source"))
                            && "single-stream".equals(f.get("mode")),
                    "source=" + f.get("source") + " mode=" + f.get("mode"));
            check("N7-origin-is-the-requested-host", f.get("origin") != null
                            && f.get("origin").startsWith("127.0.0.1"),
                    "origin=" + f.get("origin"));
            check("N7-last-failure-is-stalled", "stalled".equals(f.get("lastFailure")),
                    rawSummary(logFile, "M7 stall"));
            check("N7-termination-is-exhausted", "exhausted".equals(f.get("termination")),
                    rawSummary(logFile, "M7 stall"));
            check("N7-no-opaque-reason-field", !f.containsKey("reason"), rawSummary(logFile, "M7 stall"));
            check("N7-numerator-is-the-received-total",
                    num(f, "bytes") == num(f, "receivedBytes") && num(f, "receivedBytes") > 0,
                    "bytes=" + f.get("bytes") + " received=" + f.get("receivedBytes")
                            + " retained=" + f.get("retainedBytes"));
            accounting("N7", f, target, sha(DATA));
            check("N7-no-target-file", !Files.exists(target), target);

            // N7b: the total deadline expiring is reported as such, and it is not the retained bytes.
            Path deadlineTarget = target("metrics-deadline");
            Http expiring = new Http(2000, 5000, 30000, 2, false, null, 0, 1200,
                    SegmentedDownload.Settings.OFF, RequestLimiter.UNLIMITED, false);
            boolean expired = expiring.download(fixture.url("/deadline"), deadlineTarget, "M7b deadline",
                    sha(DATA), "1", DATA.length);
            Map<String, String> d = summary(logFile, "M7b deadline");
            System.out.println("  N7b summary: " + d);
            check("N7b-deadline-fails", !expired && "FAILED".equals(d.get("result")), d.toString());
            check("N7b-termination-is-the-deadline",
                    "deadline".equals(d.get("termination")) && "deadline".equals(d.get("lastFailure")),
                    rawSummary(logFile, "M7b deadline"));
            check("N7b-deadline-basis", "network-received-bytes/whole-operation".equals(d.get("basis")),
                    d.get("basis"));
            accounting("N7b", d, deadlineTarget, sha(DATA));

            // N8: a segmented attempt that fails and falls back to a single stream.
            Path segTarget = target("metrics-segmented");
            Http segmented = new Http(2000, 5000, 30000, 1, false, null, 1000, 30000,
                    new SegmentedDownload.Settings(true, 2, 1, 4096), RequestLimiter.UNLIMITED, false);
            boolean segOk = segmented.download(fixture.url("/segstall"), segTarget, "M8 segmented", sha(DATA),
                    "1", DATA.length);
            Map<String, String> s = summary(logFile, "M8 segmented");
            System.out.println("  N8 summary: " + s);
            Set<String> modes = Set.of("segmented", "single-stream-after-segmented");
            check("N8-failed", !segOk && "FAILED".equals(s.get("result")), "ok=" + segOk + " " + s);
            check("N8-mode", modes.contains(s.get("mode")), "mode=" + s.get("mode"));
            check("N8-bytes-were-transferred", num(s, "segmentBytes") > 0 || num(s, "receivedBytes") > 0,
                    "segmentBytes=" + s.get("segmentBytes") + " receivedBytes=" + s.get("receivedBytes"));
            check("N8-bytes-is-the-received-total",
                    num(s, "bytes") == num(s, "receivedBytes")
                            && num(s, "receivedBytes") == num(s, "streamBytes") + num(s, "segmentBytes")
                            && num(s, "segmentBytes") > 0 && num(s, "streamBytes") > 0,
                    "bytes=" + s.get("bytes") + " received=" + s.get("receivedBytes")
                            + " stream=" + s.get("streamBytes") + " segments=" + s.get("segmentBytes"));
            check("N8-source-is-not-the-mode", !"segmented".equals(s.get("source"))
                            && "official".equals(sourceName(s)) && s.get("source").contains("@"),
                    "source=" + s.get("source") + " mode=" + s.get("mode"));
            check("N8-last-failure-is-stalled", "stalled".equals(s.get("lastFailure"))
                            && "exhausted".equals(s.get("termination")),
                    rawSummary(logFile, "M8 segmented"));
            accounting("N8", s, segTarget, sha(DATA));

            // N9: a single stream whose sha1 does not match.
            Path shaTarget = target("metrics-sha");
            Http shaHttp = new Http(2000, 5000, 30000, 1, false, null, 1000, 30000,
                    SegmentedDownload.Settings.OFF, RequestLimiter.UNLIMITED, false);
            boolean shaOk = shaHttp.download(fixture.url("/sha"), shaTarget, "M9 sha", sha(DATA), "1",
                    DATA.length);
            Map<String, String> h = summary(logFile, "M9 sha");
            System.out.println("  N9 summary: " + h);
            check("N9-sha-fails", !shaOk && "FAILED".equals(h.get("result")), "ok=" + shaOk + " " + h);
            check("N9-received-recorded", num(h, "receivedBytes") > 0,
                    "receivedBytes=" + h.get("receivedBytes"));
            check("N9-nothing-retained", num(h, "retainedBytes") == 0
                            && !Files.exists(shaTarget), "retained=" + h.get("retainedBytes"));
            check("N9-last-failure-is-hash-mismatch", "hash-mismatch".equals(h.get("lastFailure")),
                    rawSummary(logFile, "M9 sha"));
            check("N9-termination-is-exhausted", "exhausted".equals(h.get("termination")),
                    rawSummary(logFile, "M9 sha"));
            check("N9-no-opaque-reason-field", !h.containsKey("reason"), rawSummary(logFile, "M9 sha"));
            check("N9-received-total-splits-into-stream-plus-segments",
                    num(h, "bytes") == num(h, "receivedBytes")
                            && num(h, "receivedBytes") == num(h, "streamBytes") + num(h, "segmentBytes")
                            && num(h, "streamBytes") > 0 && num(h, "segmentBytes") == 0,
                    "bytes=" + h.get("bytes") + " stream=" + h.get("streamBytes")
                            + " segments=" + h.get("segmentBytes"));
            check("N9-numerator-is-not-the-residual-size",
                    num(h, "receivedBytes") == DATA.length && num(h, "retainedBytes") == 0,
                    "received=" + h.get("receivedBytes") + " retained=" + h.get("retainedBytes"));
            accounting("N9", h, shaTarget, sha(DATA));

            // N10: the retry after a stall is accepted as a resume.
            Path resumeTarget = target("metrics-resume");
            Http resuming = new Http(2000, 5000, 30000, 3, false, null, 1000, 30000,
                    SegmentedDownload.Settings.OFF, RequestLimiter.UNLIMITED, false);
            boolean resumeOk = resuming.download(fixture.url("/resume"), resumeTarget, "M10 resume",
                    sha(DATA), "1", DATA.length);
            Map<String, String> r = summary(logFile, "M10 resume");
            System.out.println("  N10 summary: " + r);
            check("N10-succeeds", resumeOk && "SUCCESS".equals(r.get("result")),
                    "ok=" + resumeOk + " " + r);
            check("N10-reused-bytes", num(r, "reusedBytes") > 0 && num(r, "resumeCount") >= 1,
                    "reused=" + r.get("reusedBytes") + " resumeCount=" + r.get("resumeCount"));
            check("N10-verified-is-the-file-size", num(r, "verifiedBytes") == DATA.length
                            && num(r, "bytes") == DATA.length
                            && num(r, "receivedBytes") == num(r, "streamBytes") + num(r, "segmentBytes")
                            && Files.isRegularFile(resumeTarget)
                            && sha(Files.readAllBytes(resumeTarget)).equals(sha(DATA)),
                    "verified=" + r.get("verifiedBytes") + " bytes=" + r.get("bytes")
                            + " received=" + r.get("receivedBytes") + " stream=" + r.get("streamBytes"));
            check("N10-basis", "verified-file-bytes/whole-operation".equals(r.get("basis")), r.get("basis"));
            check("N10-attempts", num(r, "attempts") >= 2, "attempts=" + r.get("attempts"));
            check("N10-reused-is-the-accepted-resume-offset",
                    num(r, "reusedBytes") == 4096 && num(r, "resumeCount") == 1,
                    "reused=" + r.get("reusedBytes") + " resumeCount=" + r.get("resumeCount"));
            check("N10-success-leaves-no-stale-failure",
                    "none".equals(r.get("termination")) && "none".equals(r.get("lastFailure")),
                    "attempt one stalled, the final summary still says " + rawSummary(logFile, "M10 resume"));
            accounting("N10", r, resumeTarget, sha(DATA));

            // N11: a clean success carries no failure token at all.
            Path okTarget = target("metrics-ok");
            Http okHttp = new Http(2000, 5000, 30000, 1, false, null, 1000, 30000,
                    SegmentedDownload.Settings.OFF, RequestLimiter.UNLIMITED, false);
            boolean okResult = okHttp.download(fixture.url("/ok"), okTarget, "M11 ok", sha(DATA), "1",
                    DATA.length);
            Map<String, String> okFields = summary(logFile, "M11 ok");
            System.out.println("  N11 summary: " + okFields);
            check("N11-succeeds", okResult && "SUCCESS".equals(okFields.get("result")),
                    "ok=" + okResult + " " + okFields);
            check("N11-no-terminal-failure-left",
                    "none".equals(okFields.get("termination")) && "none".equals(okFields.get("lastFailure"))
                            && !okFields.containsKey("reason"),
                    rawSummary(logFile, "M11 ok"));
            check("N11-received-is-the-whole-file",
                    num(okFields, "receivedBytes") == DATA.length && num(okFields, "streamBytes") == DATA.length
                            && num(okFields, "segmentBytes") == 0 && num(okFields, "verifiedBytes") == DATA.length
                            && num(okFields, "reusedBytes") == 0 && num(okFields, "resumeCount") == 0,
                    "received=" + okFields.get("receivedBytes") + " stream=" + okFields.get("streamBytes")
                            + " verified=" + okFields.get("verifiedBytes"));
            accounting("N11", okFields, okTarget, sha(DATA));

            // N12: an exhausted HTTP error status keeps its own stable token.
            Map<String, String> http500 = Map.of();
            Map<String, String> http404 = Map.of();
            for (String status : List.of("500", "404")) {
                String what = "M12 http" + status;
                Path errorTarget = target("metrics-http" + status);
                Http errorHttp = new Http(2000, 5000, 30000, 1, false, null, 1000, 30000,
                        SegmentedDownload.Settings.OFF, RequestLimiter.UNLIMITED, false);
                boolean errorResult = errorHttp.download(fixture.url("/http" + status), errorTarget, what,
                        sha(DATA), "1", DATA.length);
                Map<String, String> e = summary(logFile, what);
                System.out.println("  N12/" + status + " summary: " + e);
                check("N12-http" + status + "-fails", !errorResult && "FAILED".equals(e.get("result")),
                        "ok=" + errorResult + " " + e);
                check("N12-http" + status + "-token",
                        ("http-" + status).equals(e.get("lastFailure"))
                                && "exhausted".equals(e.get("termination")),
                        rawSummary(logFile, what));
                check("N12-http" + status + "-nothing-was-transferred",
                        num(e, "receivedBytes") == 0 && num(e, "retainedBytes") == 0 && !Files.exists(errorTarget),
                        "received=" + e.get("receivedBytes") + " retained=" + e.get("retainedBytes"));
                accounting("N12-http" + status, e, errorTarget, sha(DATA));
                if ("500".equals(status)) {
                    http500 = e;
                } else {
                    http404 = e;
                }
            }

            // N14: a body shorter than the announced size, complete and truncated, keeps its own token.
            Map<String, String> shortBody = Map.of();
            Map<String, String> truncatedBody = Map.of();
            for (String mode : List.of("short", "truncated")) {
                String what = "M14 " + mode;
                Path lengthTarget = target("metrics-" + mode);
                Http lengthHttp = new Http(2000, 5000, 30000, 1, false, null, 1000, 30000,
                        SegmentedDownload.Settings.OFF, RequestLimiter.UNLIMITED, false);
                boolean lengthResult = lengthHttp.download(fixture.url("/" + mode), lengthTarget, what,
                        sha(DATA), "1", DATA.length);
                Map<String, String> l = summary(logFile, what);
                System.out.println("  N14/" + mode + " summary: " + l);
                check("N14-" + mode + "-fails", !lengthResult && "FAILED".equals(l.get("result")),
                        "ok=" + lengthResult + " " + l);
                check("N14-" + mode + "-token",
                        "length-mismatch".equals(l.get("lastFailure"))
                                && "exhausted".equals(l.get("termination")),
                        rawSummary(logFile, what));
                check("N14-" + mode + "-numerator-is-the-received-total",
                        num(l, "bytes") == num(l, "receivedBytes")
                                && num(l, "receivedBytes") == DATA.length - 1000,
                        "bytes=" + l.get("bytes") + " received=" + l.get("receivedBytes")
                                + " retained=" + l.get("retainedBytes"));
                accounting("N14-" + mode, l, lengthTarget, sha(DATA));
                if ("short".equals(mode)) {
                    shortBody = l;
                } else {
                    truncatedBody = l;
                }
            }
            check("N14-short-numerator-is-not-the-residual-size", num(shortBody, "retainedBytes") == 0,
                    "received=" + shortBody.get("receivedBytes") + " retained=" + shortBody.get("retainedBytes"));

            // N16: the per-request deadline has its own token, distinct from the total deadline.
            Path requestTarget = target("metrics-request");
            Http requestTimeout = new Http(2000, 5000, 800, 1, false, null, 0, 30000,
                    SegmentedDownload.Settings.OFF, RequestLimiter.UNLIMITED, false);
            boolean requestResult = requestTimeout.download(fixture.url("/stall"), requestTarget, "M16 request",
                    sha(DATA), "1", DATA.length);
            Map<String, String> requestFields = summary(logFile, "M16 request");
            System.out.println("  N16 summary: " + requestFields);
            check("N16-request-timeout-fails",
                    !requestResult && "FAILED".equals(requestFields.get("result")),
                    "ok=" + requestResult + " " + requestFields);
            check("N16-request-timeout-token",
                    "request-timeout".equals(requestFields.get("lastFailure"))
                            && "exhausted".equals(requestFields.get("termination")),
                    rawSummary(logFile, "M16 request"));
            accounting("N16", requestFields, requestTarget, sha(DATA));

            // N17: a local write failure has its own token and is not blamed on the transfer.
            Path diskTarget = target("metrics-disk");
            Files.delete(diskTarget.getParent());
            Http diskHttp = new Http(2000, 5000, 30000, 1, false, null, 1000, 30000,
                    SegmentedDownload.Settings.OFF, RequestLimiter.UNLIMITED, false);
            boolean diskResult = diskHttp.download(fixture.url("/ok"), diskTarget, "M17 disk", sha(DATA), "1",
                    DATA.length);
            Map<String, String> diskFields = summary(logFile, "M17 disk");
            System.out.println("  N17 summary: " + diskFields);
            check("N17-disk-error-fails", !diskResult && "FAILED".equals(diskFields.get("result")),
                    "ok=" + diskResult + " " + diskFields);
            check("N17-disk-error-token",
                    "disk-error".equals(diskFields.get("lastFailure"))
                            && "exhausted".equals(diskFields.get("termination")),
                    rawSummary(logFile, "M17 disk"));
            check("N17-disk-error-keeps-the-received-total",
                    num(diskFields, "bytes") == num(diskFields, "receivedBytes")
                            && num(diskFields, "retainedBytes") == 0,
                    "bytes=" + diskFields.get("bytes") + " received=" + diskFields.get("receivedBytes")
                            + " retained=" + diskFields.get("retainedBytes"));
            accounting("N17", diskFields, diskTarget, sha(DATA));

            // N15: cancellation has its own token and still stops the transfer promptly.
            Path cancelTarget = target("metrics-cancel");
            Http cancelling = new Http(2000, 5000, 30000, 1, false, null, 30000, 30000,
                    SegmentedDownload.Settings.OFF, RequestLimiter.UNLIMITED, false);
            AtomicBoolean cancelResult = new AtomicBoolean(true);
            AtomicBoolean cancelFlag = new AtomicBoolean();
            Thread canceller = new Thread(() -> {
                try {
                    cancelResult.set(cancelling.download(fixture.url("/stall"), cancelTarget, "M15 cancel",
                            sha(DATA), "1", DATA.length));
                    cancelFlag.set(Thread.currentThread().isInterrupted());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            canceller.start();
            Thread.sleep(600);
            long cancelStart = System.nanoTime();
            canceller.interrupt();
            canceller.join(3000);
            long cancelMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelStart);
            Map<String, String> cancelled = summary(logFile, "M15 cancel");
            System.out.println("  N15 summary: " + cancelled);
            check("N15-cancel-stops-promptly",
                    !canceller.isAlive() && cancelMs < 2000 && cancelFlag.get(),
                    "ms=" + cancelMs + " alive=" + canceller.isAlive() + " flag=" + cancelFlag.get());
            check("N15-cancel-fails", !cancelResult.get() && "FAILED".equals(cancelled.get("result")),
                    "ok=" + cancelResult.get() + " " + cancelled);
            check("N15-cancel-token",
                    "cancelled".equals(cancelled.get("termination"))
                            && "cancelled".equals(cancelled.get("lastFailure")),
                    rawSummary(logFile, "M15 cancel"));
            check("N15-cancel-keeps-what-arrived", num(cancelled, "receivedBytes") > 0,
                    "received=" + cancelled.get("receivedBytes"));
            accounting("N15", cancelled, cancelTarget, sha(DATA));

            // N13: every numeric field is finite and never negative, for success and failure alike.
            for (Map<String, String> fields : List.of(f, d, s, h, r, okFields, http500, http404, shortBody,
                    truncatedBody, requestFields, diskFields, cancelled)) {
                String[] numeric = {"bytes", "receivedBytes", "streamBytes", "segmentBytes", "reusedBytes",
                        "resumeCount", "verifiedBytes", "retainedBytes", "elapsedSeconds",
                        "averageBytesPerSecond", "averageMiBPerSecond", "attempts"};
                boolean okNumbers = true;
                StringBuilder detail = new StringBuilder();
                for (String key : numeric) {
                    double value = num(fields, key);
                    if (!Double.isFinite(value) || value < 0) {
                        okNumbers = false;
                        detail.append(key).append('=').append(fields.get(key)).append(' ');
                    }
                }
                okNumbers &= fields.containsKey("file") && fields.containsKey("result")
                        && fields.containsKey("mode") && fields.containsKey("source")
                        && fields.containsKey("origin") && fields.containsKey("basis")
                        && fields.containsKey("termination") && fields.containsKey("lastFailure");
                check("N13-fields-are-non-negative-and-finite", okNumbers,
                        "file=" + fields.get("file") + " bad=" + detail);
            }
            // The log must never carry a credential, a token or a query string on a url.
            String log = Files.readString(logFile, StandardCharsets.UTF_8);
            boolean queryInUrl = java.util.regex.Pattern.compile("https?://\\S*\\?\\S*").matcher(log).find();
            check("N13-log-has-no-credential-or-query-string",
                    !log.contains("x-api-key") && !log.contains("apiKey") && !log.contains("token=")
                            && !log.contains("SECRET") && !queryInUrl,
                    "log bytes=" + log.length() + " queryInUrl=" + queryInUrl);
        } finally {
            ForbricLog.detach();
        }
    }

    /** Local stall/sha/resume fixture: it writes a prefix and then stops sending without closing. */
    static final class MetricsFixture implements AutoCloseable {
        final HttpServer server;
        final ExecutorService handlers;

        MetricsFixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            handlers = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "MetricsServer");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(handlers);
            server.createContext("/", this::handle);
            server.start();
        }

        String url(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        private void handle(com.sun.net.httpserver.HttpExchange exchange) {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                String range = exchange.getRequestHeaders().getFirst("Range");
                if (path.startsWith("/stall") || path.startsWith("/deadline")) {
                    boolean sendPrefix = path.startsWith("/stall");
                    exchange.sendResponseHeaders(200, DATA.length);
                    if (sendPrefix) {
                        exchange.getResponseBody().write(DATA, 0, 4096);
                    }
                    exchange.getResponseBody().flush();
                    Thread.sleep(6000);
                    return;
                }
                if (path.startsWith("/sha")) {
                    byte[] wrong = new byte[DATA.length];
                    Arrays.fill(wrong, (byte) 7);
                    exchange.sendResponseHeaders(200, wrong.length);
                    exchange.getResponseBody().write(wrong);
                    return;
                }
                if (path.startsWith("/resume")) {
                    long start = rangeStart(range);
                    if (start > 0 && start < DATA.length) {
                        byte[] part = Arrays.copyOfRange(DATA, (int) start, DATA.length);
                        exchange.getResponseHeaders().set("Content-Range",
                                "bytes " + start + "-" + (DATA.length - 1) + "/" + DATA.length);
                        exchange.sendResponseHeaders(206, part.length);
                        exchange.getResponseBody().write(part);
                        return;
                    }
                    exchange.sendResponseHeaders(200, DATA.length);
                    exchange.getResponseBody().write(DATA, 0, 4096);
                    exchange.getResponseBody().flush();
                    Thread.sleep(6000);
                    return;
                }
                if (path.startsWith("/segstall")) {
                    long[] span = rangeSpan(range);
                    if (span != null) {
                        long length = span[1] - span[0] + 1;
                        exchange.getResponseHeaders().set("Content-Range",
                                "bytes " + span[0] + "-" + span[1] + "/" + DATA.length);
                        exchange.getResponseHeaders().set("ETag", "\"metrics\"");
                        exchange.sendResponseHeaders(206, length);
                        exchange.getResponseBody().write(DATA, (int) span[0], (int) Math.max(1, length / 2));
                        exchange.getResponseBody().flush();
                        Thread.sleep(6000);
                        return;
                    }
                    exchange.sendResponseHeaders(200, DATA.length);
                    exchange.getResponseBody().write(DATA, 0, 4096);
                    exchange.getResponseBody().flush();
                    Thread.sleep(6000);
                    return;
                }
                if (path.startsWith("/ok")) {
                    exchange.sendResponseHeaders(200, DATA.length);
                    exchange.getResponseBody().write(DATA);
                    return;
                }
                if (path.startsWith("/http500")) {
                    exchange.sendResponseHeaders(500, -1);
                    return;
                }
                if (path.startsWith("/http404")) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                if (path.startsWith("/short")) {
                    byte[] content = Arrays.copyOfRange(DATA, 0, DATA.length - 1000);
                    exchange.sendResponseHeaders(200, content.length);
                    exchange.getResponseBody().write(content);
                    return;
                }
                if (path.startsWith("/truncated")) {
                    exchange.sendResponseHeaders(200, DATA.length);
                    exchange.getResponseBody().write(DATA, 0, DATA.length - 1000);
                    exchange.getResponseBody().flush();
                    return;
                }
                exchange.sendResponseHeaders(404, -1);
            } catch (Exception ignored) {
                // the client is expected to cut these transfers
            }
        }

        private static long rangeStart(String range) {
            long[] span = rangeSpan(range);
            return span == null ? 0 : span[0];
        }

        /** Inclusive [start, end] for "bytes=a-b" or "bytes=a-", null when there is no range. */
        private static long[] rangeSpan(String range) {
            if (range == null || !range.startsWith("bytes=")) {
                return null;
            }
            String spec = range.substring("bytes=".length()).trim();
            int dash = spec.indexOf('-');
            if (dash <= 0) {
                return null;
            }
            try {
                long start = Long.parseLong(spec.substring(0, dash).trim());
                String tail = spec.substring(dash + 1).trim();
                long end = tail.isEmpty() ? DATA.length - 1 : Long.parseLong(tail);
                if (start < 0 || end < start) {
                    return null;
                }
                return new long[] {start, Math.min(end, DATA.length - 1)};
            } catch (NumberFormatException e) {
                return null;
            }
        }

        public void close() {
            server.stop(0);
            handlers.shutdownNow();
        }
    }

    static String rawSummary(Path logFile, String what) {
        List<String> lines = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
                if (line.contains("download summary: file=" + what)) {
                    lines.add(line.trim());
                }
            }
        } catch (Exception e) {
            return "<unreadable: " + e + ">";
        }
        return lines.isEmpty() ? "<no summary for " + what + ">" : lines.get(lines.size() - 1);
    }

    static Map<String, String> summary(Path logFile, String what) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String token : rawSummary(logFile, what).split("\\s+")) {
            int eq = token.indexOf('=');
            if (eq > 0) {
                fields.put(token.substring(0, eq), token.substring(eq + 1));
            }
        }
        return fields;
    }

    static double num(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * The resume file of a target is keyed by the expected sha1, exactly like Http.resumeFileFor.
     */
    static Path partialFor(Path target, String sha1) {
        return target.resolveSibling(
                target.getFileName() + "." + Integer.toHexString(sha1.hashCode()) + Http.RESUME_SUFFIX);
    }

    static long onDisk(Path path) {
        try {
            return Files.exists(path) ? Files.size(path) : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * The byte accounting of one summary: receivedBytes is the stream plus the segment total, the
     * speed numerator is the received total on a failure and the verified file size on a success, and
     * retainedBytes is exactly the leftover file on disk.
     */
    static void accounting(String label, Map<String, String> fields, Path target, String sha1) {
        long disk = onDisk(partialFor(target, sha1));
        check(label + "-accounting-received-is-stream-plus-segments",
                num(fields, "receivedBytes") == num(fields, "streamBytes") + num(fields, "segmentBytes"),
                "received=" + fields.get("receivedBytes") + " stream=" + fields.get("streamBytes")
                        + " segments=" + fields.get("segmentBytes"));
        check(label + "-accounting-retained-is-the-leftover-on-disk", num(fields, "retainedBytes") == disk,
                "retained=" + fields.get("retainedBytes") + " disk=" + disk);
        check(label + "-accounting-numerator",
                "FAILED".equals(fields.get("result"))
                        ? num(fields, "bytes") == num(fields, "receivedBytes")
                        : num(fields, "bytes") == num(fields, "verifiedBytes"),
                "result=" + fields.get("result") + " bytes=" + fields.get("bytes")
                        + " received=" + fields.get("receivedBytes") + " verified=" + fields.get("verifiedBytes"));
    }

    /** The source field is "&lt;official|mirror&gt;@&lt;host&gt;": only the label part is compared. */
    static String sourceName(Map<String, String> fields) {
        String source = fields.get("source");
        if (source == null) {
            return "";
        }
        int at = source.lastIndexOf('@');
        return at < 0 ? source : source.substring(0, at);
    }
    static boolean noWorkers(){
        return Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().startsWith("NeoForbric-Segment-"));
    }
    static boolean noParts(Path path) throws Exception {
        try(var files=Files.list(path.getParent())){return files.noneMatch(f->f.getFileName().toString().endsWith(".segments"));}
    }
    static void scenario(String mode,int total,int attempt,int idle,boolean expected) throws Exception {
        Path path=target(mode);Queue<DownloadEvent> events=new ConcurrentLinkedQueue<>();
        try(Fixture fixture=new Fixture(mode)){
            long start=System.nanoTime();
            boolean ok=http(total,attempt,idle,new RequestLimiter(8,4)).download(fixture.url(),path,mode,sha(DATA),"1",DATA.length,
                new Http.DownloadScope("stage","task",mode,events::add));
            long ms=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
            check(mode+"-result",ok==expected,"ok="+ok+" ms="+ms+" requests="+fixture.requests.get());
            if(mode.startsWith("error")||mode.startsWith("slow")||mode.equals("first-fails"))
                check(mode+"-bounded",ms<(mode.equals("error-unlimited")?4000:2000),ms);
            check(mode+"-target-protection",ok?Files.exists(path)&&sha(Files.readAllBytes(path)).equals(sha(DATA)):!Files.exists(path),path);
            check(mode+"-workers-stopped",noWorkers(),"all states included");
            check(mode+"-parts-cleaned",noParts(path),path.getParent());
            if(mode.equals("normal")){
                check("normal-range-count",fixture.requests.get()==3,fixture.requests.get());
                check("known-byte-events",events.stream().anyMatch(e->e.state()==DownloadEvent.State.PROGRESS&&e.total()==DATA.length),events);
            }
            if(mode.equals("weak-etag"))check("weak-etag-not-used",fixture.ifRanges.get()==0&&fixture.requests.get()==2,fixture.requests.get());
            if(mode.equals("bad-total")||mode.equals("gzip"))check(mode+"-fallback",events.stream().anyMatch(e->e.state()==DownloadEvent.State.STARTED&&e.attempt()==2),events);
        }
    }
    static void queueBudget() throws Exception {
        var limiter=new RequestLimiter(1,1);Path path=target("queue");
        try(Fixture fixture=new Fixture("normal");var held=limiter.acquire("busy",1000)){
            long start=System.nanoTime();boolean ok=http(200,0,0,limiter).download(fixture.url(),path,"queue",sha(DATA),"1",DATA.length);
            long ms=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
            check("queue-consumes-total-budget",!ok&&ms<700&&fixture.requests.get()==0,ms);
        }
        check("queue-no-permit-leak",limiter.availablePermits()==1,limiter.availablePermits());
    }
    static void cancellation() throws Exception {
        Path path=target("cancel");
        try(Fixture fixture=new Fixture("cancel")){
            AtomicBoolean result=new AtomicBoolean(true),flag=new AtomicBoolean();
            Http http=http(5000,5000,0,new RequestLimiter(8,4));
            Thread coordinator=new Thread(()->{
                try{result.set(http.download(fixture.url(),path,"cancel",sha(DATA),"1",DATA.length));flag.set(Thread.currentThread().isInterrupted());}
                catch(Exception e){throw new RuntimeException(e);}
            });
            coordinator.start();check("cancel-started-segments",fixture.segmentHeaders.await(2,TimeUnit.SECONDS),fixture.requests.get());
            long start=System.nanoTime();coordinator.interrupt();coordinator.join(2000);
            check("cancel-stops-coordinator",!coordinator.isAlive(),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
            check("cancel-preserves-flag",flag.get(),flag);
            check("cancel-no-fallback-or-target",!result.get()&&!Files.exists(path)&&fixture.requests.get()==3,fixture.requests.get());
            check("cancel-workers-stopped",noWorkers(),"all states included");
            check("cancel-parts-cleaned",noParts(path),path.getParent());
        }
    }
}
