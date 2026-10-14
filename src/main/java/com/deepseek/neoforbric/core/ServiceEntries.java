package com.deepseek.neoforbric.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;


public final class ServiceEntries {

    private static final Set<String> SERVICE_TYPES = Set.of(
            "cpw.mods.modlauncher.api.ITransformationService",
            "net.neoforged.neoforgespi.locating.IModFileCandidateLocator",
            "net.neoforged.neoforgespi.locating.IModFileReader",
            "net.neoforged.neoforgespi.locating.IDependencyLocator",
            "net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper",
            "net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider");


    private static final String SERVICES_PREFIX = "meta-inf/services/";

    private ServiceEntries() {}


    public record Scan(boolean readable, Map<String, String> entries) {

        public boolean declared() {
            return !entries.isEmpty();
        }

        public String describe() {
            if (!readable) {
                return "unreadable";
            }
            return entries.isEmpty() ? "none" : entries.keySet().toString();
        }
    }


    public static Set<String> serviceTypes() {
        return SERVICE_TYPES;
    }


    public static Scan scan(Path jar) {
        Map<String, String> result = new LinkedHashMap<>();
        if (jar == null || !Files.isRegularFile(jar)) {
            return new Scan(true, result);
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.toLowerCase(Locale.ROOT).startsWith(SERVICES_PREFIX)) {
                    continue;
                }
                String serviceType = name.substring(SERVICES_PREFIX.length());


                if (!SERVICE_TYPES.contains(serviceType)) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    String impl = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                    if (!impl.isBlank()) {
                        result.put(serviceType, impl);
                    }
                }
            }
            return new Scan(true, result);
        } catch (IOException | RuntimeException e) {
            ForbricLog.warn("service entry read failed, treating as unknown (%s): %s", jar.getFileName(), e.toString());
            return new Scan(false, Map.of());
        }
    }


    public static Map<String, String> of(Path jar) {
        return scan(jar).entries();
    }


    public static boolean entersServiceLayer(Path jar) {
        Scan scan = scan(jar);
        if (!scan.readable()) {
            return true;
        }
        return scan.declared();
    }


    public static boolean serviceLayerChanged(Path before, Path after) {
        Scan old = scan(before);
        Scan now = scan(after);
        if (!old.readable() || !now.readable()) {
            ForbricLog.debug("  service layer compare undecided (%s -> %s), assuming it changed",
                    old.describe(), now.describe());
            return true;
        }
        if (!old.entries().equals(now.entries())) {
            return true;
        }
        if (old.entries().isEmpty()) {
            return false;
        }
        return implementationChanged(before, after, old.entries());
    }


    private static boolean implementationChanged(Path before, Path after, Map<String, String> declarations) {
        for (Map.Entry<String, String> entry : declarations.entrySet()) {
            for (String impl : implementationClasses(entry.getValue())) {
                byte[] oldBytes = classBytes(before, impl);
                byte[] newBytes = classBytes(after, impl);
                if (oldBytes == null || newBytes == null) {
                    ForbricLog.debug("  service %s implementation %s not comparable, assuming it changed",
                            entry.getKey(), impl);
                    return true;
                }
                if (!Arrays.equals(oldBytes, newBytes)) {
                    ForbricLog.debug("  service %s implementation %s changed bytes", entry.getKey(), impl);
                    return true;
                }
            }
        }
        return false;
    }


    private static List<String> implementationClasses(String declaration) {
        List<String> result = new ArrayList<>(2);
        for (String token : declaration.split("[\\s,]+")) {
            String value = token.trim();
            if (value.isEmpty() || value.startsWith("#")) {
                continue;
            }
            int comment = value.indexOf('#');
            if (comment > 0) {
                value = value.substring(0, comment).trim();
            }
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }


    private static byte[] classBytes(Path jar, String className) {
        String entryName = className.replace('.', '/') + ".class";
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        } catch (IOException | RuntimeException e) {
            ForbricLog.debug("  class read failed (%s in %s): %s", entryName, jar.getFileName(), e.toString());
            return null;
        }
    }
}
