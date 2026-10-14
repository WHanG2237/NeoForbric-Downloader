package com.deepseek.neoforbric.net;

import com.deepseek.neoforbric.core.JsonLite;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;


public record VersionCandidate(String versionId, String projectId, String versionNumber, String name, String versionType,
        String datePublished, List<String> gameVersions, List<String> loaders, String fileName, String downloadUrl,
        String sha1, long size, List<Dependency> dependencies) {


    public record Dependency(String versionId, String projectId, String dependencyType) {}


    public static Optional<VersionCandidate> fromJson(Map<String, Object> json) {
        List<Object> files = JsonLite.asArray(json.get("files"));
        if (files.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> primary = null;
        for (Object item : files) {
            Map<String, Object> file = JsonLite.asObject(item);
            if (Boolean.TRUE.equals(file.get("primary"))) {
                primary = file;
                break;
            }
        }
        if (primary == null) {
            primary = JsonLite.asObject(files.get(0));
        }
        Map<String, Object> hashes = JsonLite.asObject(primary.get("hashes"));

        List<Dependency> dependencies = new ArrayList<>();
        for (Object item : JsonLite.asArray(json.get("dependencies"))) {
            Map<String, Object> dependency = JsonLite.asObject(item);
            dependencies.add(new Dependency(
                    JsonLite.str(dependency, "version_id", null),
                    JsonLite.str(dependency, "project_id", null),
                    JsonLite.str(dependency, "dependency_type", "required")));
        }

        return Optional.of(new VersionCandidate(
                JsonLite.str(json, "id", ""),
                JsonLite.str(json, "project_id", ""),
                JsonLite.str(json, "version_number", ""),
                JsonLite.str(json, "name", ""),
                JsonLite.str(json, "version_type", "release"),
                JsonLite.str(json, "date_published", ""),
                JsonLite.strList(json, "game_versions"),
                JsonLite.strList(json, "loaders"),
                JsonLite.str(primary, "filename", ""),
                JsonLite.str(primary, "url", ""),
                JsonLite.str(hashes, "sha1", null),
                (long) (double) ((Double) primary.getOrDefault("size", 0.0)),
                dependencies));
    }


    public static String normalize(String version) {
        return com.deepseek.neoforbric.core.VersionMatch.core(version);
    }

    public String describe() {
        return String.format("%s (%s, %s, file %s, %d bytes)", versionNumber, versionType, datePublished, fileName, size);
    }
}
