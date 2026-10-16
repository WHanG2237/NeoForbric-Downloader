package com.deepseek.neoforbric.core;

import java.util.LinkedHashMap;
import java.util.Map;


public final class TomlLite {
    private TomlLite() {}


    public static java.util.List<String> dependencyModIds(String toml) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (toml == null) {
            return result;
        }
        boolean inDependencyBlock = false;
        for (String rawLine : toml.split("\\R")) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[")) {

                inDependencyBlock = line.startsWith("[[dependencies.");
                continue;
            }
            if (!inDependencyBlock) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (!"modId".equals(line.substring(0, eq).trim())) {
                continue;
            }
            String value = unquote(line.substring(eq + 1).trim());
            if (!value.isBlank() && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }


    public static java.util.List<String> requiredDependencyModIds(String toml) {
        java.util.List<String> result = new java.util.ArrayList<>();
        for (DependencyRequirement requirement : dependencies(toml)) {
            if (!result.contains(requirement.modId())) {
                result.add(requirement.modId());
            }
        }
        return result;
    }


    public static java.util.List<DependencyRequirement> dependencies(String toml) {
        java.util.List<DependencyRequirement> result = new java.util.ArrayList<>();
        if (toml == null) {
            return result;
        }
        boolean inDependencyBlock = false;
        String modId = null;
        String range = null;
        String side = null;
        String type = null;
        Boolean mandatory = null;
        for (String rawLine : toml.split("\\R")) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[")) {
                addRequirement(result, modId, range, side, type, mandatory);
                inDependencyBlock = line.startsWith("[[dependencies.");
                modId = null;
                range = null;
                side = null;
                type = null;
                mandatory = null;
                continue;
            }
            if (!inDependencyBlock) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = unquote(line.substring(eq + 1).trim());
            switch (key) {
                case "modId" -> modId = value;
                case "versionRange" -> range = value;
                case "side" -> side = value;
                case "type" -> type = value;
                case "mandatory" -> mandatory = Boolean.parseBoolean(value);
                default -> { }
            }
        }
        addRequirement(result, modId, range, side, type, mandatory);
        return result;
    }


    private static void addRequirement(java.util.List<DependencyRequirement> result, String modId, String range,
            String side, String type, Boolean mandatory) {
        if (modId == null || modId.isBlank()) {
            return;
        }
        boolean required;
        if (type != null) {
            required = "required".equalsIgnoreCase(type.trim());
        } else {
            required = mandatory == null || mandatory;
        }
        if (!required) {
            return;
        }
        for (DependencyRequirement existing : result) {
            if (existing.modId().equalsIgnoreCase(modId)) {
                return;
            }
        }
        result.add(new DependencyRequirement(modId, range == null ? "" : range.trim(),
                side == null || side.isBlank() ? DependencyRequirement.SIDE_BOTH : side.trim(), true,
                type == null ? "required" : type.trim()));
    }


    public static java.util.List<String> modIds(String toml) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (toml == null) {
            return result;
        }
        boolean inModsBlock = false;
        for (String rawLine : toml.split("\\R")) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[")) {
                inModsBlock = line.replace(" ", "").equalsIgnoreCase("[[mods]]");
                continue;
            }
            if (!inModsBlock) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (!"modId".equals(line.substring(0, eq).trim())) {
                continue;
            }
            String value = unquote(line.substring(eq + 1).trim());
            if (!value.isBlank() && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }


    public static Map<String, String> modIdVersions(String toml) {
        Map<String, String> result = new LinkedHashMap<>();
        if (toml == null) {
            return result;
        }
        String id = null;
        String version = null;
        boolean inModsBlock = false;
        for (String rawLine : toml.split("\\R")) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[")) {
                if (inModsBlock) {
                    addModVersion(result, id, version);
                    id = null;
                    version = null;
                }
                inModsBlock = line.replace(" ", "").equalsIgnoreCase("[[mods]]");
                continue;
            }
            if (!inModsBlock) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = unquote(line.substring(eq + 1).trim());
            if ("modId".equals(key)) {
                if (id == null) {
                    id = value;
                }
            } else if ("version".equals(key) && version == null) {
                version = value;
            }
        }
        addModVersion(result, id, version);
        return result;
    }


    private static void addModVersion(Map<String, String> result, String id, String version) {
        if (id == null || id.isBlank()) {
            return;
        }
        for (String key : result.keySet()) {
            if (key.equalsIgnoreCase(id)) {
                return;
            }
        }
        result.put(id, version == null ? "" : version);
    }


    public static Map<String, String> firstModBlock(String toml) {
        Map<String, String> result = new LinkedHashMap<>();
        if (toml == null) {
            return result;
        }
        boolean inModsBlock = false;
        for (String rawLine : toml.split("\\R")) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[")) {

                if (inModsBlock) {
                    break;
                }
                inModsBlock = line.replace(" ", "").equalsIgnoreCase("[[mods]]");
                continue;
            }
            if (!inModsBlock) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = unquote(line.substring(eq + 1).trim());
            result.putIfAbsent(key, value);
        }
        return result;
    }


    public static String topLevelValue(String toml, String key) {
        if (toml == null) {
            return null;
        }
        for (String rawLine : toml.split("\\R")) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty() || line.startsWith("[")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (line.substring(0, eq).trim().equals(key)) {
                return unquote(line.substring(eq + 1).trim());
            }
        }
        return null;
    }

    private static String stripComment(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
