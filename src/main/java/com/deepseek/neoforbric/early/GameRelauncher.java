package com.deepseek.neoforbric.early;

import com.deepseek.neoforbric.core.ForbricConfig;
import com.deepseek.neoforbric.core.ForbricLog;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;


public final class GameRelauncher {

    public static final String PROP_COUNT = "neoforbric.relaunch.count";

    public static final String PROP_AT = "neoforbric.relaunch.at";

    private static final String PROP_PREFIX = "neoforbric.relaunch.";


    private static final String STATE_FILE = "relaunch.state";


    private static final java.time.format.DateTimeFormatter STAMP =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final String STAMP_PREFIX = "0000-00-00 00:00:00.000";


    private static final int MAX_COMMAND_CHARS = 30000;


    private static final List<String> PATHY_KEYS = List.of(
            "--gameDir", "--assetsDir", "--assetIndex", "--resourcePackDir", "--userProperties",
            "--version", "--username", "--userType",
            "--uuid", "--accessToken", "--clientId", "--xuid");


    private static final List<String> KNOWN_MAIN_CLASSES = List.of(
            "cpw.mods.bootstraplauncher.BootstrapLauncher");

    private GameRelauncher() {}


    public record Outcome(boolean launched, String reason, long childPid, Process child, boolean keepAlive) {
        static Outcome blocked(String reason) {
            return new Outcome(false, reason, -1, null, false);
        }
    }


    public static boolean isRelaunched() {
        return Integer.getInteger(PROP_COUNT, 0) > 0;
    }


    public static Outcome tryRelaunch(ForbricConfig config, Path gameDir, Path modsDir) {

        if (!config.getBool("restart.enable", true)) {
            return Outcome.blocked("restart.enable=false");
        }
        int maxAttempts = Math.max(1, config.getInt("restart.maxAttempts", 1));
        int cooldownSeconds = Math.max(0, config.getInt("restart.cooldownSeconds", 120));


        int count = Integer.getInteger(PROP_COUNT, 0);
        if (count >= maxAttempts) {
            return Outcome.blocked("attempt " + (count + 1) + " of " + maxAttempts + ", not restarting");
        }


        if (isRelaunched()) {
            long lastAt = Math.max(Long.getLong(PROP_AT, 0L), lastRelaunchAt(modsDir));
            long sinceMs = System.currentTimeMillis() - lastAt;
            if (lastAt > 0 && sinceMs < cooldownSeconds * 1000L) {
                return Outcome.blocked("only " + (sinceMs / 1000) + "s since last relaunch (cooldown " + cooldownSeconds + "s)");
            }
        } else if (lastRelaunchAt(modsDir) > 0) {

            ForbricLog.info("player-initiated launch: restart cooldown does not apply");
        }


        List<String> command;
        try {
            command = renderCommand();
        } catch (Throwable t) {
            ForbricLog.error("failed to rebuild the launch command line", t);
            return Outcome.blocked("rebuild command line failed: " + t);
        }


        String rejection = validate(command, gameDir);
        if (rejection != null) {
            return Outcome.blocked(rejection);
        }


        command.removeIf(arg -> arg.startsWith("-D" + PROP_PREFIX));
        int insertAt = command.indexOf("-cp");
        if (insertAt < 0) {
            insertAt = 1;
        }
        command.add(insertAt, "-D" + PROP_COUNT + "=" + (count + 1));
        command.add(insertAt, "-D" + PROP_AT + "=" + System.currentTimeMillis());


        long startedAt = System.nanoTime();


        preserveLogBeforeRestart(gameDir);
        boolean keepAlive = config.getBool("restart.keepAlive", true);
        Process child;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);

            builder.directory(Path.of(System.getProperty("user.dir")).toFile());
            if (keepAlive) {


                builder.inheritIO();
            } else {

                builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            }
            child = builder.start();
        } catch (Throwable t) {
            ForbricLog.error("failed to spawn the new process", t);
            return Outcome.blocked("spawn failed: " + t);
        }
        if (keepAlive) {


            try {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        if (child.isAlive()) {
                            child.destroy();
                        }
                    } catch (Throwable ignored) {

                    }
                }, "neoforbric-relaunch-guard"));
            } catch (Throwable ignored) {

            }
        }

        writeState(modsDir, count + 1, child.pid(), command);
        ForbricLog.info("spawned pid=%d (command %d chars, rebuilt in %s)", child.pid(), commandLength(command),
                com.deepseek.neoforbric.core.Timing.elapsed(startedAt));


        int confirmMs = Math.max(0, config.getInt("restart.confirmMs", 1500));
        try {
            Thread.sleep(confirmMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!child.isAlive()) {
            int code = -1;
            try {
                code = child.exitValue();
            } catch (IllegalThreadStateException ignored) {

            }
            return Outcome.blocked("child exited within " + confirmMs + " ms (code " + code + "), "
                    + "command line may be wrong, giving up");
        }
        return new Outcome(true, "ok", child.pid(), child, keepAlive);
    }


    public static void hideEarlyWindow() {
        EarlyWindowHider.hide();
    }


    public static void handOver(Outcome outcome) {
        Process child = outcome.child();
        if (child == null) {
            return;
        }
        ForbricLog.detach();
        if (!outcome.keepAlive()) {
            ForbricLog.info("restart.keepAlive=false: exiting immediately");
            Runtime.getRuntime().halt(0);
            return;
        }
        int code;
        try {
            code = child.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = 0;
        }
        try {
            System.out.println("[" + ForbricLog.TAG + "] game process pid=" + child.pid()
                    + " exited (code " + code + "); exiting with the same code");
            System.out.flush();
        } catch (Throwable ignored) {

        }


        Runtime.getRuntime().halt(code);
    }


    private static String validate(List<String> command, Path gameDir) {

        String exe = command.isEmpty() ? null : command.get(0);
        if (exe == null || exe.isBlank()) {
            return "no java executable";
        }
        Path exePath = Path.of(exe);
        if (!Files.isRegularFile(exePath) || !Files.isExecutable(exePath)) {
            return "java executable missing or not executable: " + exe;
        }


        String mainClass = mainClassOf(command);
        if (mainClass == null || mainClass.isBlank()) {
            return "no main class in the rebuilt command line";
        }
        if (!KNOWN_MAIN_CLASSES.contains(mainClass)) {
            return "main class " + mainClass + " not in the known whitelist (dev env or custom launcher), refusing to guess";
        }


        Optional<String> cmdGameDir = argValue(command, "--gameDir");
        Path expected = gameDir.toAbsolutePath().normalize();
        if (cmdGameDir.isPresent()) {
            Path actual = Path.of(cmdGameDir.get()).toAbsolutePath().normalize();
            if (!actual.equals(expected)) {
                return "rebuilt --gameDir is " + actual + ", but the current game dir is " + expected;
            }
        } else {

            Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
            if (!cwd.equals(expected)) {
                return "no --gameDir and the working dir " + cwd + " does not match the game dir " + expected;
            }
        }
        Optional<String> assetsDir = argValue(command, "--assetsDir");
        if (assetsDir.isPresent() && !Files.isDirectory(Path.of(assetsDir.get()))) {
            return "rebuilt --assetsDir does not exist: " + assetsDir.get();
        }
        Optional<String> version = argValue(command, "--version");
        if (version.isPresent() && version.get().isBlank()) {
            return "rebuilt --version is empty (an argument containing spaces may have been split)";
        }


        int length = commandLength(command);
        if (length >= MAX_COMMAND_CHARS) {
            return "rebuilt command line is " + length + " chars, too close to the Windows limit " + MAX_COMMAND_CHARS;
        }
        return null;
    }


    public static List<String> renderCommand() {
        List<String> command = new ArrayList<>();


        String exe = ProcessHandle.current().info().command().orElse(null);
        if (exe == null || exe.isBlank()) {
            Path candidate = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
            exe = Files.isExecutable(candidate) ? candidate.toString() : "java";
        }
        command.add(exe);


        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        command.addAll(jvmArgs);


        String modulePath = System.getProperty("jdk.module.path");
        String moduleMain = System.getProperty("jdk.module.main");
        boolean modulePathPresent = jvmArgs.stream()
                .anyMatch(arg -> arg.equals("-p") || arg.equals("--module-path") || arg.startsWith("--module-path="));
        if (moduleMain != null && !moduleMain.isBlank()) {

            if (!modulePathPresent && modulePath != null && !modulePath.isBlank()) {
                command.add("--module-path");
                command.add(modulePath);
            }
            command.add("--module");
            command.add(moduleMain);
            return command;
        }
        if (!modulePathPresent && modulePath != null && !modulePath.isBlank()) {
            command.add("--module-path");
            command.add(modulePath);
        }


        String classPath = System.getProperty("java.class.path");
        if (classPath != null && !classPath.isBlank()) {
            command.add("-cp");
            command.add(classPath);
        }


        String sunCommand = System.getProperty("sun.java.command", "");
        int split = sunCommand.indexOf(' ');
        command.add(split < 0 ? sunCommand : sunCommand.substring(0, split));
        command.addAll(splitProgramArgs(split < 0 ? "" : sunCommand.substring(split + 1)));
        return command;
    }


    static List<String> splitProgramArgs(String rest) {
        if (rest.isBlank()) {
            return List.of();
        }
        String[] raw = rest.trim().split(" +");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < raw.length; i++) {
            String token = raw[i];
            out.add(token);
            if (PATHY_KEYS.contains(token) && i + 1 < raw.length) {
                StringBuilder value = new StringBuilder(raw[++i]);
                while (i + 1 < raw.length && !raw[i + 1].startsWith("--")) {
                    value.append(' ').append(raw[++i]);
                }
                out.add(value.toString());
            }
        }
        return out;
    }


    private static String mainClassOf(List<String> command) {


        boolean expectValue = false;
        for (int i = 1; i < command.size(); i++) {
            String arg = command.get(i);
            if (expectValue) {
                expectValue = false;
                continue;
            }
            if (arg.equals("-cp") || arg.equals("-classpath") || arg.equals("--class-path")
                    || arg.equals("-p") || arg.equals("--module-path") || arg.equals("--upgrade-module-path")) {
                expectValue = true;
                continue;
            }
            if (arg.startsWith("-")) {
                continue;
            }
            return arg;
        }
        return null;
    }

    static Optional<String> argValue(List<String> command, String key) {
        int index = command.indexOf(key);
        return index >= 0 && index + 1 < command.size() ? Optional.of(command.get(index + 1)) : Optional.empty();
    }

    private static int commandLength(List<String> command) {
        int total = 0;
        for (String arg : command) {

            total += arg.length() + 3;
        }
        return total;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }


    private static Path stateFile(Path modsDir) {
        return modsDir.resolve("." + "neoforbric").resolve(STATE_FILE);
    }

    private static long lastRelaunchAt(Path modsDir) {
        Path file = stateFile(modsDir);
        if (!Files.isRegularFile(file)) {
            return 0L;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith("at=")) {
                    return Long.parseLong(line.substring(3).trim());
                }
            }
        } catch (IOException | NumberFormatException ignored) {

        }
        return 0L;
    }


    private static void preserveLogBeforeRestart(Path gameDir) {
        try {
            Path logs = gameDir.resolve("logs");
            Path current = logs.resolve("neoforbric.log");
            if (!Files.isRegularFile(current)) {
                return;
            }
            Path kept = logs.resolve("neoforbric-before-restart.log");
            Files.copy(current, kept, StandardCopyOption.REPLACE_EXISTING);
            ForbricLog.info("preserved this run's log: %s", kept);
        } catch (IOException e) {
            ForbricLog.warn("preserve log failed (harmless): %s", e.toString());
        }
    }


    public static void refreshPreservedLog(Path gameDir) {
        try {
            Path logs = gameDir.resolve("logs");
            Path current = logs.resolve("neoforbric.log");
            Path kept = logs.resolve("neoforbric-before-restart.log");
            if (!Files.isRegularFile(current) || !Files.isRegularFile(kept)) {
                return;
            }
            if (!isFromCurrentRun(current)) {
                ForbricLog.warn("main log is not from this run, keeping the earlier snapshot");
                return;
            }
            Files.copy(current, kept, StandardCopyOption.REPLACE_EXISTING);
            ForbricLog.info("snapshot refreshed: %s", kept);
        } catch (IOException e) {
            ForbricLog.warn("snapshot refresh failed (harmless): %s", e.toString());
        }
    }


    private static boolean isFromCurrentRun(Path log) {
        try {
            String first = Files.readAllLines(log, StandardCharsets.UTF_8).stream()
                    .filter(l -> !l.isBlank())
                    .findFirst()
                    .orElse(null);


            if (first == null || first.length() < STAMP_PREFIX.length()) {
                return false;
            }
            LocalDateTime stamp = LocalDateTime.parse(first.substring(0, STAMP_PREFIX.length()), STAMP);
            long ageSeconds = Math.abs(Duration.between(stamp, LocalDateTime.now()).getSeconds());
            return ageSeconds <= 120;
        } catch (Exception e) {


            ForbricLog.debug("log timestamp check failed (treated as not current): %s", e.toString());
            return false;
        }
    }

    private static void writeState(Path modsDir, int count, long childPid, List<String> command) {
        Path file = stateFile(modsDir);
        StringBuilder sb = new StringBuilder();
        sb.append("# written by the NeoForbric auto-restart (loop guard + diagnostics)").append(System.lineSeparator());
        sb.append("count=").append(count).append(System.lineSeparator());
        sb.append("at=").append(System.currentTimeMillis()).append(System.lineSeparator());
        sb.append("parentPid=").append(ProcessHandle.current().pid()).append(System.lineSeparator());
        sb.append("childPid=").append(childPid).append(System.lineSeparator());
        sb.append("command=").append(String.join(" ", command)).append(System.lineSeparator());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            ForbricLog.warn("state file write failed (harmless): %s", e.toString());
        }
    }
}
