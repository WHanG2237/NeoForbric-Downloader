package com.deepseek.neoforbric.core;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;


public final class ForbricLog {

    public static final String TAG = "NeoForbric";


    private static final String LOGGER_NAME = "neoforbric";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Object LOCK = new Object();

    private static volatile Path logFile;
    private static volatile boolean bannerDone;

    private ForbricLog() {}


    public static void game(String format, Object... args) {
        gameLine("INFO ", format, args);
    }


    public static void gameWarn(String format, Object... args) {
        gameLine("WARN ", format, args);
    }


    public static void gameError(String format, Object... args) {
        gameLine("ERROR", format, args);
    }

    private static void gameLine(String level, String format, Object[] args) {
        String message = format(format, args);

        write(level, message);
        if (GameChannel.log(level, message)) {
            return;
        }

        System.out.println("[" + TAG + "] " + message);
        System.out.flush();
    }


    public static boolean probeGameChannel() {
        boolean ok = GameChannel.available();
        if (ok) {
            write("INFO ", "gameLogChannel=" + GameChannel.describe());
        } else {
            write("INFO ", "gameLogChannel=unavailable (stdout fallback): " + GameChannel.failureReason());
        }
        return ok;
    }


    public static void attach(Path gameDir) {
        if (logFile != null || gameDir == null) {
            return;
        }
        synchronized (LOCK) {
            if (logFile != null) {
                return;
            }
            try {
                Path dir = gameDir.resolve("logs");
                Files.createDirectories(dir);
                Path target = dir.resolve("neoforbric.log");
                if (Files.isRegularFile(target) && Files.size(target) > 0) {
                    Files.copy(target, dir.resolve("neoforbric-previous.log"), StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(target, "", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                logFile = target;
            } catch (IOException e) {
                System.out.println("[" + TAG + "] cannot create log file, console only: " + e);
            }
        }
    }

    public static Path logFile() {
        return logFile;
    }


    public static void detach() {
        synchronized (LOCK) {
            logFile = null;
        }
    }


    public static void banner(String version, String gameDir, String modsDir, String platform) {
        if (bannerDone) {
            return;
        }
        bannerDone = true;
        write("INFO ", TAG + " " + version + " platform=" + platform
                + " java=" + System.getProperty("java.version", "?")
                + " os=" + System.getProperty("os.name", "?") + "/" + System.getProperty("os.arch", "?"));
        write("INFO ", "gameDir=" + gameDir + " modsDir=" + modsDir);
    }

    public static void info(String format, Object... args) {
        write("INFO ", format(format, args));
    }

    public static void warn(String format, Object... args) {
        write("WARN ", format(format, args));
    }

    public static void error(String format, Object... args) {
        write("ERROR", format(format, args));
    }


    public static void debug(String format, Object... args) {
        write("DEBUG", format(format, args));
    }

    public static void error(String message, Throwable cause) {
        write("ERROR", message + " -> " + cause);
        writeCauseChain(cause, 0);
    }


    private static void writeCauseChain(Throwable cause, int depth) {
        if (cause == null || depth > 8) {
            return;
        }
        for (StackTraceElement element : cause.getStackTrace()) {
            write("ERROR", "    at " + element);
        }
        Throwable inner = cause.getCause();
        if (inner != null) {
            write("ERROR", "  caused by: " + inner);
            writeCauseChain(inner, depth + 1);
        }
        for (Throwable suppressed : cause.getSuppressed()) {
            write("ERROR", "  suppressed: " + suppressed);
            writeCauseChain(suppressed, depth + 1);
        }
    }

    private static String format(String format, Object... args) {
        return args == null || args.length == 0 ? format : String.format(format, args);
    }

    private static void write(String level, String message) {
        String line = LocalDateTime.now().format(TIME) + " " + level + " [" + threadName() + "] " + message;
        Path target = logFile;
        if (target == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                Files.writeString(target, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {

            }
        }
    }

    private static String threadName() {
        try {
            return Thread.currentThread().getName();
        } catch (Throwable t) {
            return "?";
        }
    }


    private static final class GameChannel {
        private static final Object LOGGER;
        private static final Method INFO;
        private static final Method WARN;
        private static final Method ERROR;
        private static final String FAILURE;

        static {
            Object logger = null;
            Method info = null;
            Method warn = null;
            Method error = null;
            String failure = null;
            try {
                Class<?> factoryClass = Class.forName("org.slf4j.LoggerFactory");
                Object factory = factoryClass.getMethod("getLogger", String.class).invoke(null, LOGGER_NAME);
                Class<?> loggerClass = Class.forName("org.slf4j.Logger");
                info = loggerClass.getMethod("info", String.class);
                warn = loggerClass.getMethod("warn", String.class);
                error = loggerClass.getMethod("error", String.class);

                Object name = loggerClass.getMethod("getName").invoke(factory);
                if (!LOGGER_NAME.equals(name)) {
                    throw new IllegalStateException("logger name mismatch: " + name);
                }
                logger = factory;
            } catch (Throwable t) {
                logger = null;
                failure = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            LOGGER = logger;
            INFO = info;
            WARN = warn;
            ERROR = error;
            FAILURE = failure;
        }

        private GameChannel() {}

        static boolean available() {
            return LOGGER != null;
        }

        static String describe() {
            return LOGGER == null ? "unavailable" : LOGGER.getClass().getName();
        }

        static String failureReason() {
            return FAILURE == null ? "unknown" : FAILURE;
        }


        static boolean log(String level, String message) {
            if (LOGGER == null) {
                return false;
            }
            Method method = switch (level) {
                case "WARN " -> WARN;
                case "ERROR" -> ERROR;
                default -> INFO;
            };
            if (method == null) {
                return false;
            }
            try {
                method.invoke(LOGGER, message);
                return true;
            } catch (Throwable t) {

                return false;
            }
        }
    }
}
