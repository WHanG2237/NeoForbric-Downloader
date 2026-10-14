package com.deepseek.neoforbric.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;


public final class ModJarScanner {
    private static final String NEOFORGE_METADATA = "META-INF/neoforge.mods.toml";
    private static final String FORGE_METADATA = "META-INF/mods.toml";
    private static final String FABRIC_METADATA = "fabric.mod.json";
    private static final String QUILT_METADATA = "quilt.mod.json";
    private static final String LITELOADER_METADATA = "litemod.json";

    private ModJarScanner() {}


    public static List<ModJarInfo> scan(Path modsDir) {
        List<ModJarInfo> result = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) {
            ForbricLog.warn("mods dir not found: %s", modsDir);
            return result;
        }
        List<Path> jars;
        try (var stream = Files.list(modsDir)) {
            jars = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (IOException e) {
            ForbricLog.error("mods dir list failed: " + modsDir, e);
            return result;
        }
        for (Path jar : jars) {
            try {
                result.add(inspect(jar));
            } catch (IOException e) {
                ForbricLog.warn("cannot read %s, skipped: %s", jar.getFileName(), e.toString());
            }
        }
        return result;
    }


    public static List<String> providedModIds(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            return providedModIds(zip);
        }
    }


    private static List<String> providedModIds(ZipFile zip) throws IOException {
        List<String> result = new ArrayList<>(2);
        for (String name : new String[] {NEOFORGE_METADATA, FORGE_METADATA}) {
            if (hasEntry(zip, name)) {
                result = ModJarInfo.mergeIds(result, TomlLite.modIds(readEntry(zip, name)));
            }
        }
        if (hasEntry(zip, FABRIC_METADATA)) {
            Map<String, Object> json =
                    JsonLite.asObject(JsonLite.parseQuietly(readEntry(zip, FABRIC_METADATA), FABRIC_METADATA));
            result = ModJarInfo.mergeIds(result, List.of(JsonLite.str(json, "id", "")));
        }
        if (hasEntry(zip, QUILT_METADATA)) {
            Map<String, Object> json =
                    JsonLite.asObject(JsonLite.parseQuietly(readEntry(zip, QUILT_METADATA), QUILT_METADATA));
            Map<String, Object> loader = JsonLite.asObject(json.get("quilt_loader"));
            result = ModJarInfo.mergeIds(result, List.of(JsonLite.str(loader, "id", "")));
        }
        result.removeIf(id -> id == null || id.isBlank());
        return result;
    }


    public static ModJarInfo inspect(Path jar) throws IOException {
        String fileName = jar.getFileName().toString();
        long size = Files.size(jar);
        try (ZipFile zip = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            boolean neo = hasEntry(zip, NEOFORGE_METADATA);
            boolean forge = hasEntry(zip, FORGE_METADATA);
            boolean fabric = hasEntry(zip, FABRIC_METADATA);
            boolean quilt = hasEntry(zip, QUILT_METADATA);
            boolean lite = hasEntry(zip, LITELOADER_METADATA);

            List<ModKind> markers = new ArrayList<>(3);
            if (neo) {
                markers.add(ModKind.NEOFORGE);
            }
            if (forge) {
                markers.add(ModKind.FORGE);
            }
            if (fabric) {
                markers.add(ModKind.FABRIC);
            }
            if (quilt) {
                markers.add(ModKind.QUILT);
            }
            if (lite) {
                markers.add(ModKind.LITELOADER);
            }
            List<String> provided = providedModIds(zip);

            if (neo) {
                Map<String, String> meta = TomlLite.firstModBlock(readEntry(zip, NEOFORGE_METADATA));
                return new ModJarInfo(jar, fileName, ModKind.NEOFORGE, meta.getOrDefault("modId", ""),
                        resolveVersion(meta.getOrDefault("version", ""), zip), meta.getOrDefault("displayName", ""),
                        size, markers, provided);
            }
            if (fabric) {
                Map<String, Object> json = JsonLite.asObject(JsonLite.parseQuietly(readEntry(zip, FABRIC_METADATA), fileName));
                return new ModJarInfo(jar, fileName, ModKind.FABRIC, JsonLite.str(json, "id", ""),
                        JsonLite.str(json, "version", ""), JsonLite.str(json, "name", ""), size, markers, provided);
            }
            if (quilt) {
                Map<String, Object> json = JsonLite.asObject(JsonLite.parseQuietly(readEntry(zip, QUILT_METADATA), fileName));
                Map<String, Object> quiltLoader = JsonLite.asObject(json.get("quilt_loader"));
                return new ModJarInfo(jar, fileName, ModKind.QUILT, JsonLite.str(quiltLoader, "id", ""),
                        JsonLite.str(quiltLoader, "version", ""), JsonLite.str(quiltLoader, "id", ""), size, markers,
                        provided);
            }
            if (forge) {
                Map<String, String> meta = TomlLite.firstModBlock(readEntry(zip, FORGE_METADATA));
                return new ModJarInfo(jar, fileName, ModKind.FORGE, meta.getOrDefault("modId", ""),
                        resolveVersion(meta.getOrDefault("version", ""), zip), meta.getOrDefault("displayName", ""),
                        size, markers, provided);
            }
            if (lite) {
                return new ModJarInfo(jar, fileName, ModKind.LITELOADER, "", "", "", size, markers, provided);
            }
            return new ModJarInfo(jar, fileName, ModKind.UNKNOWN, "", "", "", size, markers, provided);
        }
    }

    private static boolean hasEntry(ZipFile zip, String name) {
        return zip.getEntry(name) != null;
    }


    private static String resolveVersion(String raw, ZipFile zip) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.trim();
        if (!value.contains("${")) {
            return value;
        }
        String fromManifest = manifestVersion(zip);
        if (fromManifest != null && !fromManifest.isBlank()) {
            return fromManifest;
        }

        return value;
    }


    private static String manifestVersion(ZipFile zip) {
        String manifest;
        try {
            manifest = readEntry(zip, "META-INF/MANIFEST.MF");
        } catch (IOException e) {

            ForbricLog.debug("manifest read failed, version unknown: %s", e.toString());
            return null;
        }
        if (manifest == null || manifest.isEmpty()) {
            return null;
        }
        String implementation = null;
        String specification = null;

        for (String line : manifest.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (key.equalsIgnoreCase("Implementation-Version")) {
                implementation = value;
            } else if (key.equalsIgnoreCase("Specification-Version")) {
                specification = value;
            }
        }
        return implementation != null ? implementation : specification;
    }

    private static String readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return "";
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
