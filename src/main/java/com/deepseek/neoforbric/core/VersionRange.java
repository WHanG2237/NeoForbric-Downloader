package com.deepseek.neoforbric.core;

import java.util.Locale;


public final class VersionRange {

    public enum Verdict {
        SATISFIED,
        NOT_SATISFIED,
        UNKNOWN
    }

    private VersionRange() {}


    public static Verdict check(String version, String range) {
        if (range == null || range.isBlank() || version == null || version.isBlank()) {
            return Verdict.UNKNOWN;
        }
        String spec = range.trim();
        char first = spec.charAt(0);
        char last = spec.charAt(spec.length() - 1);
        boolean lowerInclusive = first == '[';
        boolean upperInclusive = last == ']';
        if ((first != '[' && first != '(') || (last != ']' && last != ')')) {
            return Verdict.UNKNOWN;
        }
        String inner = spec.substring(1, spec.length() - 1);
        int comma = inner.indexOf(',');
        String lower;
        String upper;
        if (comma < 0) {
            lower = inner.trim();
            upper = lower;
            if (lower.isEmpty()) {
                return Verdict.UNKNOWN;
            }
            return compare(version, lower) == 0 ? Verdict.SATISFIED : Verdict.NOT_SATISFIED;
        }
        lower = inner.substring(0, comma).trim();
        upper = inner.substring(comma + 1).trim();
        if (!lower.isEmpty()) {
            int c = compare(version, lower);
            if (c < 0 || (c == 0 && !lowerInclusive)) {
                return Verdict.NOT_SATISFIED;
            }
        }
        if (!upper.isEmpty()) {
            int c = compare(version, upper);
            if (c > 0 || (c == 0 && !upperInclusive)) {
                return Verdict.NOT_SATISFIED;
            }
        }
        return Verdict.SATISFIED;
    }


    public static int compare(String a, String b) {
        String left = VersionMatch.core(a);
        String right = VersionMatch.core(b);
        if (left.equals(right)) {
            return 0;
        }
        String[] leftTokens = left.split("[.\\-_]");
        String[] rightTokens = right.split("[.\\-_]");
        int index = 0;
        while (index < leftTokens.length && index < rightTokens.length
                && isDigits(leftTokens[index]) && isDigits(rightTokens[index])) {
            int comparison = Long.compare(parse(leftTokens[index]), parse(rightTokens[index]));
            if (comparison != 0) {
                return comparison;
            }
            index++;
        }
        boolean leftNumeric = index < leftTokens.length && isDigits(leftTokens[index]);
        boolean rightNumeric = index < rightTokens.length && isDigits(rightTokens[index]);
        if (leftNumeric != rightNumeric) {
            return leftNumeric ? 1 : -1;
        }
        String leftRest = join(leftTokens, index);
        String rightRest = join(rightTokens, index);
        if (leftRest.isEmpty() && rightRest.isEmpty()) {
            return 0;
        }
        if (leftRest.isEmpty()) {
            return 1;
        }
        if (rightRest.isEmpty()) {
            return -1;
        }
        String[] leftQualifier = leftRest.split("[.\\-_]");
        String[] rightQualifier = rightRest.split("[.\\-_]");
        for (int i = 0; i < Math.max(leftQualifier.length, rightQualifier.length); i++) {
            String x = i < leftQualifier.length ? leftQualifier[i] : "";
            String y = i < rightQualifier.length ? rightQualifier[i] : "";
            if (x.equals(y)) {
                continue;
            }
            boolean xDigits = isDigits(x);
            boolean yDigits = isDigits(y);
            if (xDigits && yDigits) {
                return Long.compare(parse(x), parse(y));
            }
            if (xDigits != yDigits) {
                return xDigits ? 1 : -1;
            }
            int comparison = x.toLowerCase(Locale.ROOT).compareTo(y.toLowerCase(Locale.ROOT));
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static String join(String[] tokens, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < tokens.length; i++) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(tokens[i]);
        }
        return sb.toString();
    }

    private static long parse(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isDigits(String value) {
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
