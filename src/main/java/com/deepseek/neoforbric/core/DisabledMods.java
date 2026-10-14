package com.deepseek.neoforbric.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;


public final class DisabledMods {

    private static final List<String> SUFFIXES = List.of(".disabled", ".disable");

    private DisabledMods() {}


    public static boolean isDisabledName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String suffix : SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }


    public static String enabledNameOf(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String suffix : SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return fileName.substring(0, fileName.length() - suffix.length());
            }
        }
        return fileName;
    }


    public static List<Path> listDisabled(Path modsDir) {
        if (modsDir == null || !Files.isDirectory(modsDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(modsDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> isDisabledName(p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            ForbricLog.warn("disabled file list failed: %s", e.toString());
            return List.of();
        }
    }


    public static Optional<Path> enable(Path disabled) {
        Path parent = disabled.getParent();
        if (parent == null) {
            return Optional.empty();
        }
        String enabledName = enabledNameOf(disabled.getFileName().toString());
        Path target = parent.resolve(enabledName);
        if (Files.exists(target)) {
            ForbricLog.info("  enabled copy %s exists, keeping disabled %s", enabledName, disabled.getFileName());
            return Optional.empty();
        }
        try {
            Files.move(disabled, target);
            return Optional.of(target);
        } catch (IOException e) {
            ForbricLog.warn("enable %s failed: %s", disabled.getFileName(), e.toString());
            return Optional.empty();
        }
    }
}
