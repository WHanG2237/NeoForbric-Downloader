package com.deepseek.neoforbric.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


public record ModJarInfo(Path path, String fileName, ModKind kind, String modId, String version, String displayName,
        long size, List<ModKind> markers, List<String> providedModIds, Map<String, String> providedVersions) {

    public ModJarInfo(Path path, String fileName, ModKind kind, String modId, String version, String displayName,
            long size, List<ModKind> markers, List<String> providedModIds) {
        this(path, fileName, kind, modId, version, displayName, size, markers, providedModIds, Map.of());
    }


    public ModJarInfo {
        markers = markers == null ? List.of() : List.copyOf(markers);
        providedModIds = providedModIds == null ? List.of() : List.copyOf(providedModIds);
        providedVersions = providedVersions == null ? Map.of() : Map.copyOf(providedVersions);
    }


    public boolean multiLoader() {
        return markers.size() > 1;
    }


    public List<String> allModIds() {
        if (!providedModIds.isEmpty()) {
            return providedModIds;
        }
        return modId == null || modId.isBlank() ? List.of() : List.of(modId);
    }


    public boolean provides(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        for (String provided : allModIds()) {
            if (provided.equalsIgnoreCase(id.trim())) {
                return true;
            }
        }
        return false;
    }


    public String providedVersion(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String wanted = id.trim();
        for (Map.Entry<String, String> entry : providedVersions.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(wanted)) {
                return entry.getValue();
            }
        }
        return null;
    }


    public String markerSummary() {
        StringBuilder sb = new StringBuilder();
        for (ModKind marker : markers) {
            if (sb.length() > 0) {
                sb.append('+');
            }
            sb.append(marker.name().toLowerCase(java.util.Locale.ROOT));
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }


    public String describe() {
        String id = modId == null || modId.isEmpty() ? "?" : modId;
        String ver = version == null || version.isEmpty() ? "?" : version;
        return String.format("%s [%s%s] id=%s version=%s (%d bytes)", fileName, kind.display(),
                multiLoader() ? ",multi=" + markerSummary() : "", id, ver, size);
    }


    public static List<String> mergeIds(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>(first);
        for (String id : second) {
            if (id == null || id.isBlank()) {
                continue;
            }
            boolean seen = false;
            for (String existing : result) {
                if (existing.equalsIgnoreCase(id)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                result.add(id);
            }
        }
        return result;
    }
}
