import com.deepseek.neoforbric.net.*;
import com.deepseek.neoforbric.core.DownloadEvent;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
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
