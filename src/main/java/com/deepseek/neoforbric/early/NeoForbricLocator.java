package com.deepseek.neoforbric.early;

import com.deepseek.neoforbric.core.BuildInfo;
import com.deepseek.neoforbric.core.ConnectorJar;
import com.deepseek.neoforbric.core.HiddenMods;
import com.deepseek.neoforbric.core.ForbricConfig;
import com.deepseek.neoforbric.core.ForbricLog;
import com.deepseek.neoforbric.core.ProgressReporter;
import com.deepseek.neoforbric.core.StartupTimer;
import com.deepseek.neoforbric.core.TranspileEngine;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.ImmediateWindowHandler;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;


public final class NeoForbricLocator implements IModFileCandidateLocator {

    public static final int PRIORITY = 500;


    private static final String BODY_RESOURCE = "/META-INF/neoforbric/neoforbric-body.jar";

    private static final String BODY_FILE_NAME = "neoforbric-body.jar";

    private static final String HIDE_FILE_NAME = "hide.properties";

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        try {
            run(context, pipeline);
        } catch (Throwable t) {

            ForbricLog.error("unexpected error in translation (ignored, game continues)", t);
            ForbricLog.gameError("NeoForbric hit an unexpected error; the game will continue. Details: logs/neoforbric.log");


            try {
                Path gameDir = resolveGameDir(context);
                if (gameDir != null) {
                    TranspileEngine.writeSkippedReport(gameDir, BuildInfo.load().version(),
                            "error (translation aborted)", "unexpected error: " + t);
                }
            } catch (Throwable ignored) {

            }
        }
    }

    private void run(ILaunchContext context, IDiscoveryPipeline pipeline) {
        Path gameDir = resolveGameDir(context);
        if (gameDir == null) {
            System.out.println("[NeoForbric] cannot resolve game dir, skipping translation");
            return;
        }
        Path modsDir = gameDir.resolve("mods");
        ForbricLog.attach(gameDir);

        ForbricLog.probeGameChannel();

        BuildInfo build = BuildInfo.load();
        ForbricConfig config = ForbricConfig.load(gameDir);
        ForbricLog.banner(build.version(), gameDir.toString(), modsDir.toString(), launchTarget(context));
        ForbricLog.game("NeoForbric %s - translating mods for NeoForge %s",
                build.version(), build.gameVersion());
        ForbricLog.debug("connector not bundled; downloaded on demand (restart required). builtAgainst=%s",
                build.connectorVersion());
        ForbricLog.debug("jvm->engine: %s",
                StartupTimer.sinceJvmStart());
        if (GameRelauncher.isRelaunched()) {
            ForbricLog.debug("relaunched: count=%d",
                    Integer.getInteger(GameRelauncher.PROP_COUNT, 0));
        }


        loadBody(pipeline, modsDir);

        if (!config.getBool("enable", true)) {
            ForbricLog.info("enable=false, nothing to translate");

            TranspileEngine.writeSkippedReport(gameDir, build.version(), "disabled (enable=false)",
                    "enable=false, nothing to translate");
            return;
        }
        if (!Files.isDirectory(modsDir)) {
            ForbricLog.warn("mods dir missing, skipping: %s", modsDir);
            TranspileEngine.writeSkippedReport(gameDir, build.version(), "skipped (mods folder missing)",
                    "mods folder not found: " + modsDir);
            return;
        }


        List<String> hideIds = HiddenMods.merge(HiddenMods.DEFAULTS, config.getList("hide.mods", HiddenMods.DEFAULTS));
        List<String> hidePrefixes = HiddenMods.merge(HiddenMods.DEFAULT_PREFIXES,
                config.getList("hide.prefixes", HiddenMods.DEFAULT_PREFIXES));
        List<String> hideNames = HiddenMods.merge(HiddenMods.DEFAULT_NAMES,
                config.getList("hide.names", HiddenMods.DEFAULT_NAMES));
        List<String> hideFiles = HiddenMods.merge(HiddenMods.DEFAULT_FILE_PREFIXES,
                config.getList("hide.files", HiddenMods.DEFAULT_FILE_PREFIXES));
        writeHideList(modsDir, hideIds, hidePrefixes, hideNames, hideFiles);

        ScreenProgress progress = new ScreenProgress();
        TranspileEngine.Result result;
        TranspileEngine engine;
        try {
            engine = new TranspileEngine(config, gameDir, modsDir, build.gameVersion(), build.version(), progress);
            result = engine.run();
        } finally {
            progress.finish();
        }
        ForbricLog.info("jvm->engine done: %s",
                StartupTimer.sinceJvmStart());


        if (!result.errors().isEmpty()) {
            ForbricLog.error("translation finished with %d unresolved item(s); originals untouched",
                    result.errors().size());
            for (var issue : result.errors()) {
                ForbricLog.error("  - %s", issue.translationKey());
                pipeline.addIssue(issue.withSeverity(ModLoadingIssue.Severity.WARNING));
            }
        }


        Path connectorJar = engine.connectorJar();
        if (connectorJar == null) {


            connectorJar = ConnectorJar.find(modsDir, false).orElse(null);
        }
        if (connectorJar != null && Files.isRegularFile(connectorJar)) {
            claimJar(pipeline, connectorJar);
        }


        if (result.restartNeeded()) {
            handleRestartRequired(pipeline, config, gameDir, modsDir, connectorJar, result);
        }
    }


    private void handleRestartRequired(IDiscoveryPipeline pipeline, ForbricConfig config, Path gameDir, Path modsDir,
            Path connectorJar, TranspileEngine.Result result) {


        if (result.connectorDownloaded() || result.connectorEnabled()) {
            ForbricLog.info("connector %s to %s; it is a loader, so the service layer fixed at JVM start "
                    + "cannot see it this run",
                    result.connectorEnabled() ? "enabled" : "downloaded",
                    connectorJar == null ? "(mods)" : connectorJar.getFileName());
        }
        if (result.serviceLayerChanged() > 0) {
            ForbricLog.info("%d replaced jar(s) gained service layer entries; their locators cannot run this run "
                    + "(the mod appears in the list but no code loads)",
                    result.serviceLayerChanged());
        }
        if (result.fabricReplaced() > 0 && result.serviceLayerChanged() == 0) {
            ForbricLog.info("%d fabric mod(s) replaced in place; FML/mixin state was built from the old files",
                    result.fabricReplaced());
        }
        if (result.restartNeeded()) {
            ForbricLog.info("restart required: the service layer and mixin state must be rebuilt");
        }

        GameRelauncher.Outcome outcome = GameRelauncher.tryRelaunch(config, gameDir, modsDir);
        if (outcome.launched()) {
            ForbricLog.info("relaunched: pid=%d", outcome.childPid());
            ForbricLog.info("parent stays alive so the launcher keeps treating this as one session; "
                    + "it will exit with the child's exit code");
            ForbricLog.game("Restarting the game to apply the translation...");


            GameRelauncher.hideEarlyWindow();


            GameRelauncher.refreshPreservedLog(gameDir);


            GameRelauncher.handOver(outcome);
            return;
        }

        ForbricLog.warn("relaunch not performed: %s", outcome.reason());
        ForbricLog.warn("falling back to the error screen (manual restart)");
        ForbricLog.gameError("Automatic restart failed; please restart the game manually. Details: logs/neoforbric.log");
        reportRestartRequired(pipeline, modsDir, outcome.reason(), result);
    }


    private void reportRestartRequired(IDiscoveryPipeline pipeline, Path modsDir, String reason,
            TranspileEngine.Result result) {


        StringBuilder sb = new StringBuilder();
        if (result.connectorDownloaded() || result.connectorEnabled()) {
            sb.append("检测到 Fabric 模组，兼容层（Sinytra Connector）已就绪。\n");
        }
        if (result.serviceLayerChanged() > 0) {
            sb.append("已替换 ").append(result.serviceLayerChanged())
                    .append(" 个需要进 FML 服务层的模组（其 NeoForge 构建靠内置定位器加载本体）。\n");
        } else if (result.fabricReplaced() > 0) {
            sb.append("已替换 ").append(result.fabricReplaced()).append(" 个 Fabric 模组。\n");
        }
        sb.append("请关闭游戏并重新启动一次，替换结果即可正常加载。\n");
        sb.append("本次未能自动重启的原因：").append(reason);
        String text = sb.toString();
        try {
            pipeline.addIssue(ModLoadingIssue.error(safeKey(text)).withAffectedPath(modsDir));
        } catch (Throwable t) {

            ForbricLog.error("reporting the restart issue to NeoForge failed (manual restart needed)", t);
        }
    }


    private static String safeKey(String text) {
        return text.replace('%', '％')
                .replace('{', '（')
                .replace('}', '）')
                .replace("'", "’");
    }


    private static void claimJar(IDiscoveryPipeline pipeline, Path jar) {
        try {
            pipeline.addPath(jar, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.IGNORE);
            ForbricLog.debug("  claimed %s", jar.getFileName());
        } catch (Throwable t) {
            ForbricLog.warn("claim %s failed (harmless): %s", jar.getFileName(), t.toString());
        }
    }


    private void loadBody(IDiscoveryPipeline pipeline, Path modsDir) {
        Path bodyJar = extractBody(modsDir);
        if (bodyJar == null) {
            ForbricLog.error("body unpack failed: this mod will not appear in the mod list");
            return;
        }
        try {
            var added = pipeline.addPath(bodyJar, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.IGNORE);
            if (added.isPresent()) {
                ForbricLog.debug("  body added to discovery: %s", bodyJar);
            } else {
                ForbricLog.error("body add failed: %s", bodyJar);
            }
        } catch (Throwable t) {
            ForbricLog.error("body add error (this mod will not appear in the mod list)", t);
        }
    }


    private Path extractBody(Path modsDir) {
        Path target = modsDir.resolve(TranspileEngine.WORK_DIR).resolve(BODY_FILE_NAME);
        try (InputStream in = NeoForbricLocator.class.getResourceAsStream(BODY_RESOURCE)) {
            if (in == null) {
                ForbricLog.error("embedded resource not found: %s", BODY_RESOURCE);
                return null;
            }
            byte[] bytes = in.readAllBytes();
            Files.createDirectories(target.getParent());
            if (Files.isRegularFile(target) && sameContent(target, bytes)) {
                return target;
            }
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            ForbricLog.debug("  body unpacked: %s (%d bytes)", target, bytes.length);
            return target;
        } catch (IOException e) {
            ForbricLog.error("body unpack failed: " + target, e);
            return null;
        }
    }


    private static boolean sameContent(Path file, byte[] expected) {
        byte[] expectedDigest = digest(expected);
        if (expectedDigest == null) {

            return false;
        }
        try {
            byte[] actualDigest = digest(Files.readAllBytes(file));
            return actualDigest != null && MessageDigest.isEqual(actualDigest, expectedDigest);
        } catch (IOException e) {
            ForbricLog.debug("  body compare failed, will rewrite: %s", e.toString());
            return false;
        }
    }


    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            ForbricLog.warn("SHA-256 unavailable, body jar will be rewritten every launch: %s", e.toString());
            return null;
        }
    }


    private void writeHideList(Path modsDir, List<String> ids, List<String> prefixes, List<String> names,
            List<String> files) {
        Path file = modsDir.resolve(TranspileEngine.WORK_DIR).resolve(HIDE_FILE_NAME);
        String content = "# written by the NeoForbric locator, read by the body mod (mod list hide list)" + System.lineSeparator()
                + "ids=" + String.join(",", ids) + System.lineSeparator()
                + "prefixes=" + String.join(",", prefixes) + System.lineSeparator()
                + "names=" + String.join(",", names) + System.lineSeparator()
                + "files=" + String.join(",", files) + System.lineSeparator();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            ForbricLog.warn("hide list write failed (defaults will be used): %s", e.toString());
        }
    }


    private static final class ScreenProgress implements ProgressReporter {

        private static final int RESOLUTION = 10_000;

        private ProgressMeter meter;
        private String currentStage;
        private String stageLabel = "";
        private int stageTotal;
        private int stageFinished;
        private int lastAbsolute = -1;
        private String lastLabel = "";
        private long lastStatusNanos;
        private final java.util.Map<String, com.deepseek.neoforbric.core.DownloadEvent> downloads = new java.util.HashMap<>();

        @Override
        public synchronized void start(String label, int totalSteps) {
            currentStage = null;
            beginStage(label, totalSteps);
        }

        @Override
        public synchronized void stageBegin(String stageId, String label, int totalSteps) {
            currentStage = stageId;
            beginStage(label, totalSteps);
        }

        private void beginStage(String label, int totalSteps) {
            downloads.clear();
            stageLabel = label == null ? "" : label;
            stageTotal = Math.max(0, totalSteps);
            stageFinished = 0;
            lastAbsolute = -1;
            ensureMeter(stageLabel);
            publish();
            status(stageLabel);
        }

        @Override
        public synchronized boolean stageActive(String stageId) {
            return stageId == null ? currentStage == null : stageId.equals(currentStage);
        }

        @Override
        public synchronized void stageTotal(String stageId, String label, int totalSteps) {
            if (!stageActive(stageId)) {
                return;
            }
            stageTotal = Math.max(0, totalSteps);
            publish();
        }

        @Override
        public synchronized void stageState(String stageId, String label, int done, int failed, int skipped, int kept,
                int total) {
            if (!stageActive(stageId)) {
                return;
            }
            stageTotal = Math.max(0, total);
            stageFinished = done + failed + skipped + kept;
            publish();
        }

        @Override
        public synchronized void stageEnd(String stageId, String label, int done, int failed, int skipped, int kept) {
            if (!stageActive(stageId)) {
                return;
            }
            currentStage = null;
            int ended = done + failed + skipped + kept;
            stageTotal = Math.max(stageTotal, ended);
            stageFinished = ended;
            publish();
            status(label);
        }

        @Override
        public synchronized void message(String englishText) {
            if (englishText == null || englishText.isBlank()) {
                return;
            }
            long now = System.nanoTime();
            if (now - lastStatusNanos < 100_000_000L) {
                return;
            }
            lastStatusNanos = now;
            status(englishText);
        }

        @Override
        public synchronized void step(String englishText) {
            if (stageTotal <= 0) {
                stageTotal = stageFinished + 1;
            }
            stageFinished++;
            publish();
            message(englishText);
        }

        @Override
        public synchronized void phaseDone(String englishText) {
            status(englishText);
        }

        @Override
        public synchronized void downloadEvent(com.deepseek.neoforbric.core.DownloadEvent event) {
            if (event == null || !stageActive(event.stageId())) {
                return;
            }
            var previous = downloads.get(event.taskId());
            if (previous != null && (event.attempt() < previous.attempt()
                    || (event.attempt() == previous.attempt()
                            && (previous.terminal() || event.received() < previous.received())))) {
                return;
            }
            downloads.put(event.taskId(), event);
            if (event.state() == com.deepseek.neoforbric.core.DownloadEvent.State.PROGRESS) {
                message("NeoForbric: Translating " + event.label() + " (attempt " + event.attempt() + ") "
                        + (event.totalKnown() ? String.format(java.util.Locale.ROOT, "%.0f%%", event.fraction() * 100)
                                : event.received() + " bytes"));
            }
        }

        @Override
        public synchronized void finish() {
            finishAll();
        }

        @Override
        public synchronized void finishAll() {
            currentStage = null;
            ProgressMeter current = meter;
            meter = null;
            if (current != null) {
                try {
                    current.complete();
                } catch (Throwable ignored) {

                }
            }
        }


        private void ensureMeter(String label) {
            if (meter != null) {
                return;
            }
            try {
                meter = StartupNotificationManager.prependProgressBar(label, RESOLUTION);
            } catch (Throwable ignored) {
                meter = null;
            }
        }


        private double fraction() {
            if (stageTotal <= 0) {
                return 0.0;
            }
            double value = (double) stageFinished / (double) stageTotal;
            if (value < 0) {
                return 0.0;
            }
            return value > 1.0 ? 1.0 : value;
        }


        private String labelWithCounts() {
            if (stageTotal > 0) {
                return stageLabel + " (" + Math.min(stageFinished, stageTotal) + "/" + stageTotal + ")";
            }
            return stageLabel + " (" + stageFinished + ")";
        }


        private void publish() {
            ProgressMeter current = meter;
            if (current == null) {
                return;
            }
            try {
                int absolute = (int) Math.round(fraction() * RESOLUTION);
                if (absolute < 0) {
                    absolute = 0;
                }
                if (absolute > RESOLUTION) {
                    absolute = RESOLUTION;
                }
                if (absolute != lastAbsolute) {
                    current.setAbsolute(absolute);
                    lastAbsolute = absolute;
                }
                String text = labelWithCounts();
                if (!text.equals(lastLabel)) {
                    current.label(text);
                    lastLabel = text;
                }
            } catch (Throwable ignored) {

            }
        }


        private void status(String englishText) {
            try {
                StartupNotificationManager.modLoaderMessage(englishText);
            } catch (Throwable ignored) {

            }
            try {
                ImmediateWindowHandler.updateProgress(englishText);
            } catch (Throwable ignored) {

            }
        }
    }

    private static Path resolveGameDir(ILaunchContext context) {
        try {
            Path gameDir = FMLPaths.GAMEDIR.get();
            if (gameDir != null) {
                return gameDir;
            }
        } catch (Throwable ignored) {

        }
        try {
            return context.environment().getProperty(cpw.mods.modlauncher.api.IEnvironment.Keys.GAMEDIR.get()).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String launchTarget(ILaunchContext context) {
        try {
            return context.environment().getProperty(cpw.mods.modlauncher.api.IEnvironment.Keys.LAUNCHTARGET.get())
                    .orElse("unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }


    @Override
    public String toString() {
        return ForbricLog.TAG + " locator (priority=" + PRIORITY + ")";
    }
}
