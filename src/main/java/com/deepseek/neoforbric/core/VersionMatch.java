package com.deepseek.neoforbric.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public final class VersionMatch {

    private static final List<String> LOADER_TAGS = List.of(
            "fabric", "forge", "neoforge", "neo", "quilt", "common", "universal", "all", "mc");

    private VersionMatch() {}


    public static String core(String version) {
        if (version == null) {
            return "";
        }
        String value = version.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return "";
        }

        if (value.length() > 1 && value.charAt(0) == 'v' && Character.isDigit(value.charAt(1))) {
            value = value.substring(1);
        }

        int plus = value.indexOf('+');
        if (plus >= 0) {
            value = value.substring(0, plus);
        }
        value = trimSeparators(value);
        if (value.isEmpty()) {
            return "";
        }


        List<String> tokens = new ArrayList<>(4);
        for (String part : value.split("-")) {
            String token = trimSeparators(part);
            if (!token.isEmpty() && !isLoaderTag(token)) {
                tokens.add(token);
            }
        }
        if (tokens.isEmpty()) {
            return value;
        }


        List<String> withoutMc = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            if (!isMinecraftVersion(token)) {
                withoutMc.add(token);
            }
        }
        List<String> kept = withoutMc.isEmpty() ? tokens : withoutMc;
        return String.join("-", kept);
    }


    public static boolean same(String a, String b) {
        String coreA = core(a);
        String coreB = core(b);
        if (coreA.isEmpty() || coreB.isEmpty()) {
            return false;
        }
        if (!mcGuard(a, b)) {
            return false;
        }
        if (coreA.equals(coreB)) {
            return true;
        }
        return numericallyEqual(coreA, coreB);
    }


    private static boolean mcGuard(String a, String b) {
        String left = primaryMinecraftToken(a);
        String right = primaryMinecraftToken(b);
        if (left == null || right == null) {
            return true;
        }
        return prefixCompatible(left, right);
    }


    private static String primaryMinecraftToken(String version) {
        if (version == null) {
            return null;
        }
        String value = version.trim().toLowerCase(Locale.ROOT);
        String[] tokens = value.split("[-+]");
        for (String part : tokens) {
            String token = trimSeparators(part);
            if (token.startsWith("mc") && isMinecraftVersion(token)) {
                return token.substring(2);
            }
        }
        int plus = value.indexOf('+');
        if (plus >= 0) {
            for (String part : value.substring(plus + 1).split("[-+]")) {
                String token = trimSeparators(part);
                if (isMinecraftVersion(token)) {
                    return token;
                }
            }
        }
        for (String part : tokens) {
            String token = trimSeparators(part);
            if (isMinecraftVersion(token)) {
                return token;
            }
        }
        return null;
    }


    private static boolean prefixCompatible(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        int shared = Math.min(left.length, right.length);
        for (int i = 0; i < shared; i++) {
            if (!left[i].equals(right[i])) {
                return false;
            }
        }
        return true;
    }


    public static List<String> variants(String version) {
        String core = core(version);
        List<String> result = new ArrayList<>(2);
        if (core.isEmpty()) {
            return result;
        }
        result.add(core);
        String trimmed = core;
        while (trimmed.endsWith(".0")) {
            trimmed = trimmed.substring(0, trimmed.length() - 2);
            if (!trimmed.isEmpty() && !result.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }


    public static boolean containsVersion(String haystack, String reference) {
        if (haystack == null || haystack.isEmpty()) {
            return false;
        }
        String lower = haystack.toLowerCase(Locale.ROOT);
        for (String variant : variants(reference)) {
            if (containsBounded(lower, variant)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsBounded(String haystack, String needle) {
        int from = 0;
        while (true) {
            int index = haystack.indexOf(needle, from);
            if (index < 0) {
                return false;
            }
            int before = index - 1;
            int after = index + needle.length();
            boolean okBefore = before < 0 || !isVersionChar(haystack.charAt(before));
            boolean okAfter = after >= haystack.length() || !isVersionChar(haystack.charAt(after));
            if (okBefore && okAfter) {
                return true;
            }
            from = index + 1;
        }
    }


    private static boolean isVersionChar(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == '_';
    }


    private static boolean isMinecraftVersion(String token) {
        if (token.startsWith("mc")) {
            String rest = token.substring(2);
            return rest.matches("\\d+(\\.\\d+){1,2}");
        }
        return token.matches("1\\.\\d+(\\.\\d+)?");
    }

    private static boolean isLoaderTag(String token) {
        return LOADER_TAGS.contains(token);
    }

    private static String trimSeparators(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isSeparator(value.charAt(start))) {
            start++;
        }
        while (end > start && isSeparator(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isSeparator(char c) {
        return c == '-' || c == '_' || c == '.' || c == '+' || c == ' ';
    }


    private static boolean numericallyEqual(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (String part : left) {
            if (!isAllDigits(part)) {
                return false;
            }
        }
        for (String part : right) {
            if (!isAllDigits(part)) {
                return false;
            }
        }
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            if (component(left, i) != component(right, i)) {
                return false;
            }
        }
        return true;
    }

    private static long component(String[] parts, int index) {
        return index >= parts.length ? 0L : Long.parseLong(parts[index]);
    }

    private static boolean isAllDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
