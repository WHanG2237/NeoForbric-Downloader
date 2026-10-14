package com.deepseek.neoforbric.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;


public final class ConnectorJar {

    public static final String LOCATOR_CLASS = "org.sinytra.connector.locator.ConnectorLocator";


    private static final String SERVICE_ENTRY =
            "META-INF/services/net.neoforged.neoforgespi.locating.IDependencyLocator";

    private static final String LOCATOR_ENTRY = "org/sinytra/connector/locator/ConnectorLocator.class";

    private static final String JARJAR_METADATA = "META-INF/jarjar/metadata.json";

    private static final Pattern JARJAR_NAME = Pattern.compile("connector-(.+?)-mod\\.jar$");

    private ConnectorJar() {}


    public static Optional<Path> find(Path modsDir, boolean thorough) {
        Optional<Path> enabled = findEnabled(modsDir, thorough);
        if (enabled.isPresent()) {
            return enabled;
        }

        List<Path> disabled = DisabledMods.listDisabled(modsDir);
        if (disabled.isEmpty()) {
            return Optional.empty();
        }
        for (Path jar : disabled) {
            if (looksLikeConnectorByName(jar) && isConnectorJar(jar)) {
                return Optional.of(jar);
            }
        }
        if (!thorough) {
            return Optional.empty();
        }
        for (Path jar : disabled) {
            if (!looksLikeConnectorByName(jar) && isConnectorJar(jar)) {
                return Optional.of(jar);
            }
        }
        return Optional.empty();
    }


    public static Optional<Path> findEnabled(Path modsDir, boolean thorough) {
        List<Path> jars = listJars(modsDir);
        if (jars.isEmpty()) {
            return Optional.empty();
        }

        for (Path jar : jars) {
            if (looksLikeConnectorByName(jar) && isConnectorJar(jar)) {
                return Optional.of(jar);
            }
        }
        if (!thorough) {
            return Optional.empty();
        }

        long startedAt = System.nanoTime();
        for (Path jar : jars) {
            if (looksLikeConnectorByName(jar)) {
                continue;
            }
            if (isConnectorJar(jar)) {
                return Optional.of(jar);
            }
        }
        ForbricLog.debug("  opened %d jar(s), no connector (took %s)", jars.size(),
                Timing.elapsed(startedAt));
        return Optional.empty();
    }


    private static List<Path> listJars(Path modsDir) {
        if (modsDir == null || !Files.isDirectory(modsDir)) {
            return List.of();
        }
        List<Path> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(modsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .forEach(result::add);
        } catch (IOException e) {
            ForbricLog.warn("mods dir list failed: %s", e.toString());
        }
        return result;
    }

    private static boolean looksLikeConnectorByName(Path jar) {

        String name = DisabledMods.enabledNameOf(jar.getFileName().toString());
        return name.toLowerCase(Locale.ROOT).contains("connector");
    }


    public static boolean isConnectorJar(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry service = zip.getEntry(SERVICE_ENTRY);
            if (service != null) {
                try (InputStream in = zip.getInputStream(service)) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    if (content.contains(LOCATOR_CLASS)) {
                        return true;
                    }
                }
            }

            return zip.getEntry(LOCATOR_ENTRY) != null;
        } catch (IOException | RuntimeException e) {


            ForbricLog.debug("connector probe failed, treating as not-connector: %s -> %s", jar.getFileName(), e.toString());
            return false;
        }
    }


    public static Optional<String> versionOf(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry metadata = zip.getEntry(JARJAR_METADATA);
            if (metadata != null) {
                String text;
                try (InputStream in = zip.getInputStream(metadata)) {
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                Optional<String> parsed = versionFromMetadata(text);
                if (parsed.isPresent()) {
                    return parsed;
                }
            }

            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                Matcher matcher = JARJAR_NAME.matcher(name);
                if (matcher.find()) {
                    return Optional.of(matcher.group(1));
                }
            }
        } catch (IOException | RuntimeException e) {
            ForbricLog.warn("connector version read failed (%s): %s", jar.getFileName(), e.toString());
        }
        return Optional.empty();
    }

    private static Optional<String> versionFromMetadata(String text) {
        Map<String, Object> root = JsonLite.asObject(JsonLite.parseQuietly(text, "connector jarjar metadata"));
        for (Object item : JsonLite.asArray(root.get("jars"))) {
            Map<String, Object> entry = JsonLite.asObject(item);
            Map<String, Object> identifier = JsonLite.asObject(entry.get("identifier"));
            if (!"org.sinytra".equals(JsonLite.str(identifier, "group", ""))) {
                continue;
            }
            String version = JsonLite.str(JsonLite.asObject(entry.get("version")), "artifactVersion", "");
            if (!version.isBlank()) {
                return Optional.of(version);
            }
        }
        return Optional.empty();
    }
}
