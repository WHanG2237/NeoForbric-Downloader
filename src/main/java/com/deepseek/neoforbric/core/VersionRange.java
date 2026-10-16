package com.deepseek.neoforbric.core;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;


public final class VersionRange {

    public enum Verdict {
        SATISFIED,
        NOT_SATISFIED,
        UNKNOWN
    }


    public record Restriction(String lower, boolean lowerInclusive, String upper, boolean upperInclusive,
            boolean exact) {}


    private static final List<String> QUALIFIERS =
            List.of("alpha", "beta", "milestone", "rc", "snapshot", "", "sp");
    private static final int RELEASE_RANK = QUALIFIERS.indexOf("");
    private static final int UNKNOWN_RANK = QUALIFIERS.size();


    private VersionRange() {}


    public static Verdict check(String version, String range) {
        if (version == null || version.isBlank() || range == null || range.isBlank()) {
            return Verdict.UNKNOWN;
        }
        String candidate = version.trim();
        if (!isComparableVersion(candidate)) {
            return Verdict.UNKNOWN;
        }
        List<Restriction> restrictions = parse(range);
        if (restrictions == null) {
            return Verdict.UNKNOWN;
        }
        VersionItem subject = versionOf(candidate);
        boolean unknown = false;
        for (Restriction restriction : restrictions) {
            Boolean hit = contains(subject, restriction);
            if (Boolean.TRUE.equals(hit)) {
                return Verdict.SATISFIED;
            }
            if (hit == null) {
                unknown = true;
            }
        }
        return unknown ? Verdict.UNKNOWN : Verdict.NOT_SATISFIED;
    }


    public static List<Restriction> parse(String range) {
        if (range == null) {
            return null;
        }
        String process = range.trim();
        if (process.isEmpty()) {
            return null;
        }
        List<Restriction> result = new ArrayList<>(2);
        while (!process.isEmpty()) {
            char open = process.charAt(0);
            if (open != '[' && open != '(') {
                return null;
            }
            int close = -1;
            for (int i = 1; i < process.length(); i++) {
                char c = process.charAt(i);
                if (c == ']' || c == ')') {
                    close = i;
                    break;
                }
                if (c == '[' || c == '(') {
                    return null;
                }
            }
            if (close < 0) {
                return null;
            }
            Restriction restriction = restrictionOf(process.substring(1, close), open == '[',
                    process.charAt(close) == ']');
            if (restriction == null) {
                return null;
            }
            result.add(restriction);
            process = process.substring(close + 1).trim();
            if (process.startsWith(",")) {
                process = process.substring(1).trim();
            }
        }
        return result.isEmpty() ? null : List.copyOf(result);
    }


    private static Restriction restrictionOf(String body, boolean lowerInclusive, boolean upperInclusive) {
        String inner = body.trim();
        if (inner.isEmpty()) {
            return null;
        }
        int comma = inner.indexOf(',');
        if (comma < 0) {
            if (!lowerInclusive || !upperInclusive || !isComparableVersion(inner)) {
                return null;
            }
            return new Restriction(inner, true, inner, true, true);
        }
        if (inner.indexOf(',', comma + 1) >= 0) {
            return null;
        }
        String lower = inner.substring(0, comma).trim();
        String upper = inner.substring(comma + 1).trim();
        if (lower.isEmpty() && upper.isEmpty()) {
            return null;
        }
        if (!lower.isEmpty() && !isComparableVersion(lower)) {
            return null;
        }
        if (!upper.isEmpty() && !isComparableVersion(upper)) {
            return null;
        }
        return new Restriction(lower, lowerInclusive, upper, upperInclusive, false);
    }


    private static Boolean contains(VersionItem subject, Restriction restriction) {
        if (restriction.exact()) {
            Integer same = compareItems(subject, versionOf(restriction.lower()));
            return same == null ? null : same == 0;
        }
        if (!restriction.lower().isEmpty()) {
            Integer comparison = compareItems(subject, versionOf(restriction.lower()));
            if (comparison == null) {
                return null;
            }
            if (comparison < 0 || (comparison == 0 && !restriction.lowerInclusive())) {
                return Boolean.FALSE;
            }
        }
        if (!restriction.upper().isEmpty()) {
            Integer comparison = compareItems(subject, versionOf(restriction.upper()));
            if (comparison == null) {
                return null;
            }
            if (comparison > 0 || (comparison == 0 && !restriction.upperInclusive())) {
                return Boolean.FALSE;
            }
        }
        return Boolean.TRUE;
    }


    static Integer compareOrNull(String left, String right) {
        if (!isComparableVersion(left) || !isComparableVersion(right)) {
            return null;
        }
        return compareItems(versionOf(left), versionOf(right));
    }


    private static boolean isComparableVersion(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        boolean digit = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isDigit(c)) {
                digit = true;
            } else if (!isLetter(c) && c != '.' && c != '-' && c != '_' && c != '+') {
                return false;
            }
        }
        return digit;
    }


    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }


    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }


    private abstract static class VersionItem {

        abstract boolean isNull();

        abstract Integer compare(VersionItem other);
    }


    private static final class NumberItem extends VersionItem {

        private static final NumberItem ZERO = new NumberItem(BigInteger.ZERO);

        private final BigInteger value;

        NumberItem(BigInteger value) {
            this.value = value;
        }

        @Override
        boolean isNull() {
            return value.signum() == 0;
        }

        @Override
        Integer compare(VersionItem other) {
            if (other == null) {
                return isNull() ? 0 : 1;
            }
            if (other instanceof NumberItem number) {
                return Integer.signum(value.compareTo(number.value));
            }
            return 1;
        }
    }


    private static final class TextItem extends VersionItem {

        private final String value;

        TextItem(String value, boolean followedByDigit) {
            this.value = alias(value, followedByDigit);
        }

        @Override
        boolean isNull() {
            return value.isEmpty();
        }

        @Override
        Integer compare(VersionItem other) {
            if (other == null) {
                return rank() == UNKNOWN_RANK ? 1 : Integer.compare(rank(), RELEASE_RANK);
            }
            if (other instanceof TextItem text) {
                if (rank() != text.rank()) {
                    return Integer.compare(rank(), text.rank());
                }
                if (rank() == UNKNOWN_RANK && !value.equals(text.value)) {
                    return null;
                }
                return 0;
            }
            return -1;
        }

        private int rank() {
            int found = QUALIFIERS.indexOf(value);
            return found < 0 ? UNKNOWN_RANK : found;
        }
    }


    private static final class ListItem extends VersionItem {

        private final List<VersionItem> items = new ArrayList<>(4);

        @Override
        boolean isNull() {
            return items.isEmpty();
        }

        @Override
        Integer compare(VersionItem other) {
            if (other instanceof ListItem list) {
                int length = Math.max(items.size(), list.items.size());
                for (int i = 0; i < length; i++) {
                    VersionItem left = i < items.size() ? items.get(i) : null;
                    VersionItem right = i < list.items.size() ? list.items.get(i) : null;
                    Integer comparison = compareItems(left, right);
                    if (comparison == null) {
                        return null;
                    }
                    if (comparison != 0) {
                        return comparison;
                    }
                }
                return 0;
            }
            if (other == null) {
                for (VersionItem item : items) {
                    Integer comparison = compareItems(item, null);
                    if (comparison != null && comparison != 0) {
                        return comparison;
                    }
                }
                return 0;
            }
            return other instanceof NumberItem ? -1 : 1;
        }

        void normalize() {
            for (int i = items.size() - 1; i >= 0; i--) {
                VersionItem item = items.get(i);
                if (item.isNull()) {
                    items.remove(i);
                } else if (!(item instanceof ListItem)) {
                    break;
                }
            }
        }
    }


    private static Integer compareItems(VersionItem left, VersionItem right) {
        if (left == null) {
            if (right == null) {
                return 0;
            }
            Integer comparison = right.compare(null);
            return comparison == null ? null : -comparison;
        }
        return left.compare(right);
    }


    private static String alias(String value, boolean followedByDigit) {
        if (followedByDigit && value.length() == 1) {
            switch (value) {
                case "a":
                    return "alpha";
                case "b":
                    return "beta";
                case "m":
                    return "milestone";
                default:
                    break;
            }
        }
        switch (value) {
            case "ga":
            case "final":
            case "release":
                return "";
            case "cr":
                return "rc";
            default:
                return value;
        }
    }


    private static VersionItem versionOf(String value) {
        String text = value.trim().toLowerCase(Locale.ROOT);
        ListItem root = new ListItem();
        ListItem current = root;
        Deque<ListItem> pending = new ArrayDeque<>();
        pending.push(current);
        boolean digits = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.') {
                current.items.add(i == start ? NumberItem.ZERO : itemOf(digits, text.substring(start, i)));
                start = i + 1;
            } else if (c == '-') {
                current.items.add(i == start ? NumberItem.ZERO : itemOf(digits, text.substring(start, i)));
                start = i + 1;
                current = descend(current, pending);
            } else if (isDigit(c)) {
                if (!digits && i > start) {
                    current.items.add(new TextItem(text.substring(start, i), true));
                    start = i;
                    current = descend(current, pending);
                }
                digits = true;
            } else {
                if (digits && i > start) {
                    current.items.add(itemOf(true, text.substring(start, i)));
                    start = i;
                    current = descend(current, pending);
                }
                digits = false;
            }
        }
        if (text.length() > start) {
            current.items.add(itemOf(digits, text.substring(start)));
        }
        while (!pending.isEmpty()) {
            pending.pop().normalize();
        }
        return root;
    }


    private static ListItem descend(ListItem current, Deque<ListItem> pending) {
        ListItem created = new ListItem();
        current.items.add(created);
        pending.push(created);
        return created;
    }


    private static VersionItem itemOf(boolean digits, String text) {
        if (!digits) {
            return new TextItem(text, false);
        }
        String trimmed = stripLeadingZeros(text);
        return trimmed.isEmpty() ? NumberItem.ZERO : new NumberItem(new BigInteger(trimmed));
    }


    private static String stripLeadingZeros(String value) {
        int index = 0;
        while (index < value.length() && value.charAt(index) == '0') {
            index++;
        }
        return value.substring(index);
    }
}
