package com.deepseek.neoforbric.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;


public final class ModDependencies {

    public static final String CONNECTOR_ID = "connector";

    public static final String FABRIC_API_ID = "fabric_api";

    private static final String NEOFORGE_TOML = "META-INF/neoforge.mods.toml";
    private static final String FORGE_TOML = "META-INF/mods.toml";
    private static final String FABRIC_JSON = "fabric.mod.json";

    private static final String SIDE_UNKNOWN = "UNKNOWN";

    private ModDependencies() {}


    public static List<String> of(java.nio.file.Path jar) {
        List<String> result = new ArrayList<>();
        if (jar == null || !java.nio.file.Files.isRegularFile(jar)) {
            return result;
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String name : new String[] {NEOFORGE_TOML, FORGE_TOML}) {
                String toml = read(zip, name);
                if (toml != null) {
                    addAll(result, TomlLite.dependencyModIds(toml));
                }
            }
            String json = read(zip, FABRIC_JSON);
            if (json != null) {
                addAll(result, fabricDependencies(json));
            }
        } catch (IOException | RuntimeException e) {
            ForbricLog.warn("dependency read failed, treating as none (%s): %s", jar.getFileName(), e.toString());
        }
        return result;
    }


    public static boolean needsConnector(java.nio.file.Path jar) {
        for (String id : of(jar)) {
            String lower = id.toLowerCase(Locale.ROOT);
            if (CONNECTOR_ID.equals(lower) || FABRIC_API_ID.equals(lower)) {
                return true;
            }
        }
        return false;
    }


    public static String currentSide() {
        try {
            Class<?> loader = Class.forName("net.neoforged.fml.loading.FMLLoader");
            Object dist = loader.getMethod("getDist").invoke(null);
            return dist == null ? SIDE_UNKNOWN : String.valueOf(dist).toUpperCase(Locale.ROOT);
        } catch (Throwable t) {
            return SIDE_UNKNOWN;
        }
    }


    public static boolean sideApplies(String declared, String current) {
        if (declared == null || declared.isBlank() || DependencyRequirement.SIDE_BOTH.equalsIgnoreCase(declared)) {
            return true;
        }
        if (current == null || current.isBlank() || SIDE_UNKNOWN.equals(current)) {
            return true;
        }
        String want = declared.trim().toUpperCase(Locale.ROOT);
        boolean client = current.contains("CLIENT");
        if ("CLIENT".equals(want)) {
            return client;
        }
        if ("SERVER".equals(want)) {
            return !client;
        }
        return true;
    }


    public static List<DependencyRequirement> requirements(java.nio.file.Path jar) {
        List<DependencyRequirement> result = new ArrayList<>();
        if (jar == null || !java.nio.file.Files.isRegularFile(jar)) {
            return result;
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String name : new String[] {NEOFORGE_TOML, FORGE_TOML}) {
                String toml = read(zip, name);
                if (toml != null) {
                    merge(result, TomlLite.dependencies(toml));
                }
            }
            String json = read(zip, FABRIC_JSON);
            if (json != null) {
                merge(result, fabricRequirements(json));
            }
        } catch (IOException | RuntimeException e) {
            ForbricLog.warn("required dependency read failed, treating as none (%s): %s", jar.getFileName(),
                    e.toString());
        }
        return result;
    }


    public static List<DependencyRequirement> required(java.nio.file.Path jar) {
        List<DependencyRequirement> result = new ArrayList<>();
        String side = currentSide();
        for (DependencyRequirement requirement : requirements(jar)) {
            if (isPlatform(requirement.modId())) {
                continue;
            }
            if (!sideApplies(requirement.side(), side)) {
                ForbricLog.debug("  dependency %s is for side %s, current side is %s -> not installed",
                        requirement.modId(), requirement.side(), side);
                continue;
            }
            result.add(requirement);
        }
        return result;
    }


    private static void merge(List<DependencyRequirement> target, List<DependencyRequirement> values) {
        for (DependencyRequirement value : values) {
            boolean seen = false;
            for (DependencyRequirement existing : target) {
                if (existing.modId().equalsIgnoreCase(value.modId())) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                target.add(value);
            }
        }
    }


    private static final java.util.Set<String> PLATFORM_IDS = java.util.Set.of(
            "minecraft", "java", "neoforge", "forge", "fml", "fabricloader", "quilt_loader", "quilted_fabric",
            "fabric", "connector", "fabric_api", "fabric-api", "fabric_api_base", "neoforbric");


    public static boolean isPlatform(String modId) {
        if (modId == null) {
            return true;
        }
        String id = modId.trim().toLowerCase(Locale.ROOT);
        return PLATFORM_IDS.contains(id) || id.startsWith("fabric-") || id.startsWith("fabric_");
    }


    private static List<DependencyRequirement> fabricRequirements(String json) {
        List<DependencyRequirement> result = new ArrayList<>();
        Map<String, Object> root = JsonLite.asObject(JsonLite.parseQuietly(json, FABRIC_JSON));
        Map<String, Object> block = JsonLite.asObject(root.get("depends"));
        for (Map.Entry<String, Object> entry : block.entrySet()) {
            String id = entry.getKey();
            int colon = id.indexOf(':');
            String modId = colon > 0 ? id.substring(0, colon) : id;
            String range = entry.getValue() instanceof String text ? text : "";
            if (!modId.isBlank()) {
                result.add(new DependencyRequirement(modId, range, DependencyRequirement.SIDE_BOTH, true, "required"));
            }
        }
        return result;
    }


    private static List<String> fabricDependencies(String json) {
        List<String> result = new ArrayList<>();
        Map<String, Object> root = JsonLite.asObject(JsonLite.parseQuietly(json, FABRIC_JSON));
        for (String key : new String[] {"depends", "recommends", "suggests"}) {
            Map<String, Object> block = JsonLite.asObject(root.get(key));
            for (String id : block.keySet()) {

                int colon = id.indexOf(':');
                addAll(result, List.of(colon > 0 ? id.substring(0, colon) : id));
            }
        }
        return result;
    }

    private static void addAll(List<String> target, List<String> values) {
        for (String v : values) {
            if (v != null && !v.isBlank() && !target.contains(v)) {
                target.add(v);
            }
        }
    }

    private static String read(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
