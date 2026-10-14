package com.deepseek.neoforbric.body;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;


public final class BodyLog {
    private static final String TAG = "NeoForbric";
    private static final String LOGGER_NAME = "neoforbric";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Object LOCK = new Object();

    private BodyLog() {}


    public static void game(String format, Object... args) {
        gameLine("INFO ", format, args);
    }

    public static void gameWarn(String format, Object... args) {
        gameLine("WARN ", format, args);
    }

    public static void gameError(String format, Object... args) {
        String message = format(format, args);
        gameLine("ERROR", "%s", new Object[] {message});
        write("ERROR", message);
    }

    private static void gameLine(String level, String format, Object[] args) {
        String message = format(format, args);
        if (GameChannel.log(level, message)) {
            return;
        }
        System.out.println("[" + TAG + "] " + message);
        System.out.flush();
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

    private static String format(String format, Object... args) {
        return args == null || args.length == 0 ? format : String.format(format, args);
    }

    private static void write(String level, String message) {
        String line = LocalDateTime.now().format(TIME) + " " + level + " [" + threadName() + "] " + message;
        Path logFile = resolveLogFile();
        if (logFile == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
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

    private static Path resolveLogFile() {
        try {
            Path gameDir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
            return gameDir == null ? null : gameDir.resolve("logs").resolve("neoforbric.log");
        } catch (Throwable t) {
            return null;
        }
    }


    private static final class GameChannel {
        private static final Object LOGGER;
        private static final Method INFO;
        private static final Method WARN;
        private static final Method ERROR;

        static {
            Object logger = null;
            Method info = null;
            Method warn = null;
            Method error = null;
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
            }
            LOGGER = logger;
            INFO = info;
            WARN = warn;
            ERROR = error;
        }

        private GameChannel() {}

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
