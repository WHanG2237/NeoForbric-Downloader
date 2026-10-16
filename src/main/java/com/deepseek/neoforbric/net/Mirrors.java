package com.deepseek.neoforbric.net;

import com.deepseek.neoforbric.core.ForbricLog;
import com.deepseek.neoforbric.core.Hashes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;


public final class Mirrors {

    public enum Group {
        OFFICIAL_ALTERNATE,
        THIRD_PARTY_MODRINTH,
        THIRD_PARTY_GITHUB
    }


    public enum Slot {
        OFFICIAL,
        MIRROR,
        COMPONENT
    }


    public enum EndpointClass {
        OFFICIAL_API,
        CUSTOM,
        THIRD_PARTY_MCIM,
        REMOVED_SERVICE,
        INVALID
    }


    public record Endpoint(Slot slot, String key, String url, String host, EndpointClass kind, boolean allowed,
            String label, String refusal) {

        public boolean refused() {
            return !allowed;
        }
    }


    public record Rule(String officialPrefix, List<String> mirrorPrefixes, boolean mirrorFirst, Group group) {

        public Rule(String officialPrefix, List<String> mirrorPrefixes) {
            this(officialPrefix, mirrorPrefixes, false, Group.OFFICIAL_ALTERNATE);
        }
    }


    public record Candidate(String url, boolean mirror, boolean thirdParty) {

        public Candidate(String url, boolean mirror) {
            this(url, mirror, false);
        }
    }


    public record Policy(boolean officialAlternates, boolean thirdPartyModrinth, boolean thirdPartyGithub) {

        public static final Policy NONE = new Policy(false, false, false);

        public static final Policy OFFICIAL_ONLY = new Policy(true, false, false);

        public static final Policy ALL = new Policy(true, true, true);

        public static Policy of(boolean mirrorsEnabled) {
            return mirrorsEnabled ? OFFICIAL_ONLY : NONE;
        }

        public boolean allows(Group group) {
            return switch (group) {
                case OFFICIAL_ALTERNATE -> officialAlternates;
                case THIRD_PARTY_MODRINTH -> thirdPartyModrinth;
                case THIRD_PARTY_GITHUB -> thirdPartyGithub;
            };
        }
    }


    public static final String OFFICIAL_API = "https://api.modrinth.com/v2";

    public static final String LABEL_OFFICIAL = "Modrinth official";

    public static final String LABEL_USER = "Modrinth user endpoint";

    public static final String LABEL_THIRD_PARTY = "Modrinth third-party mirror (MCIM)";

    public static final String MCIM_HOST = "mod.mcimirror.top";

    private static final List<Rule> RULES = List.of(

            new Rule(OFFICIAL_API, List.of(
                    "https://" + MCIM_HOST + "/modrinth/v2"), false, Group.THIRD_PARTY_MODRINTH),

            new Rule("https://cdn.modrinth.com", List.of(
                    "https://cdn-raw.modrinth.com"), true, Group.OFFICIAL_ALTERNATE),

            new Rule("https://github.com", List.of(
                    "https://gh-proxy.com/https://github.com"), false, Group.THIRD_PARTY_GITHUB),
            new Rule("https://raw.githubusercontent.com", List.of(
                    "https://gh-proxy.com/https://raw.githubusercontent.com"), false, Group.THIRD_PARTY_GITHUB));

    private static final String[] REMOVED_HOST_SUFFIXES = {
        "curseforge.com",
        "forgecdn.net",
        "curseforge.gg",
    };

    private Mirrors() {}


    public static List<Candidate> candidates(String officialUrl, Policy policy) {
        Policy effective = policy == null ? Policy.NONE : policy;
        LinkedHashSet<String> mirrors = new LinkedHashSet<>();
        LinkedHashSet<String> thirdParty = new LinkedHashSet<>();
        boolean mirrorFirst = false;
        if (officialUrl != null && !officialUrl.isBlank()) {
            for (Rule rule : RULES) {
                if (!officialUrl.startsWith(rule.officialPrefix()) || !effective.allows(rule.group())) {
                    continue;
                }
                for (String mirror : rule.mirrorPrefixes()) {
                    String expanded = mirror + officialUrl.substring(rule.officialPrefix().length());
                    mirrors.add(expanded);
                    if (rule.group() != Group.OFFICIAL_ALTERNATE) {
                        thirdParty.add(expanded);
                    }
                }
                mirrorFirst |= rule.mirrorFirst();
            }
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (mirrorFirst) {
            ordered.addAll(mirrors);
        }
        ordered.add(officialUrl);
        ordered.addAll(mirrors);
        List<Candidate> result = new ArrayList<>(ordered.size());
        for (String url : ordered) {
            result.add(new Candidate(url, mirrors.contains(url), thirdParty.contains(url)));
        }
        return result;
    }


    public static List<Candidate> candidates(String officialUrl, boolean mirrorsEnabled) {
        return candidates(officialUrl, Policy.of(mirrorsEnabled));
    }


    public static Candidate official(String url) {
        return new Candidate(url, false, false);
    }


    public static boolean isThirdParty(String url) {
        if (url == null) {
            return false;
        }
        for (Rule rule : RULES) {
            if (rule.group() == Group.OFFICIAL_ALTERNATE) {
                continue;
            }
            for (String mirror : rule.mirrorPrefixes()) {
                if (url.startsWith(mirror)) {
                    return true;
                }
            }
        }
        return false;
    }


    public static boolean isMcimHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals(MCIM_HOST) || lower.endsWith("." + MCIM_HOST);
    }


    public static Endpoint classify(String key, Slot slot, String url, boolean thirdPartyAllowed) {
        Slot where = slot == null ? Slot.OFFICIAL : slot;
        String name = key == null || key.isBlank() ? "the endpoint address" : key.trim();
        String label = where == Slot.MIRROR ? LABEL_USER : LABEL_OFFICIAL;
        if (url == null || url.isBlank()) {
            return new Endpoint(where, name, url == null ? "" : url.trim(), null, EndpointClass.INVALID, false, label,
                    name + " is empty, so there is no address to use. Set it to the official Modrinth API ("
                            + OFFICIAL_API + "), to an endpoint of your own, or turn that source off.");
        }
        String trimmed = url.trim();
        java.net.URI uri;
        try {
            uri = java.net.URI.create(trimmed);
        } catch (RuntimeException e) {
            uri = null;
        }
        String scheme = uri == null ? null : uri.getScheme();
        String host = uri == null ? null : uri.getHost();
        boolean absolute = host != null && !host.isEmpty() && scheme != null
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        if (!absolute) {
            return new Endpoint(where, name, trimmed, null, EndpointClass.INVALID, false, label,
                    name + " is not an absolute http or https address, so it cannot be used. Set it to the"
                            + " official Modrinth API (" + OFFICIAL_API + "), to an endpoint of your own, or"
                            + " turn that source off.");
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        String removed = removedServiceOf(trimmed);
        if (removed != null) {
            return new Endpoint(where, name, trimmed, lowerHost, EndpointClass.REMOVED_SERVICE, false, label,
                    name + " points at a host under " + removed + ", a service this build no longer talks to:"
                            + " CurseForge access was removed in 0.0.10. That endpoint is refused in every slot"
                            + " and no request is sent to it. Set the key to the official Modrinth API ("
                            + OFFICIAL_API + "), to an endpoint of your own, or turn that source off.");
        }
        if (isMcimHost(lowerHost)) {
            if (!thirdPartyAllowed) {
                return new Endpoint(where, name, trimmed, lowerHost, EndpointClass.THIRD_PARTY_MCIM, false, label,
                        name + " points at the community MCIM mirror (" + MCIM_HOST + "), a third-party service."
                                + " It is used only when mirror.thirdparty.modrinth.enable and"
                                + " source.modrinth.mirror.enable are both on, and both are off, so that endpoint"
                                + " is refused and no request is sent to it. Turn both switches on to accept that"
                                + " mirror, or set the key to the official Modrinth API (" + OFFICIAL_API
                                + ") or to an endpoint of your own.");
            }
            ForbricLog.warn("%s is the community MCIM mirror (%s), a third-party service, and it is used only"
                    + " because both switches were turned on by hand. It is labelled and treated as a third-party"
                    + " mirror, never as the official source; this project's registration and access scope with"
                    + " that service are still being confirmed and its terms are not confirmed here.", name,
                    MCIM_HOST);
            return new Endpoint(where, name, trimmed, lowerHost, EndpointClass.THIRD_PARTY_MCIM, true,
                    LABEL_THIRD_PARTY, null);
        }
        if (identityOf(trimmed).equals(OFFICIAL_API)) {
            return new Endpoint(where, name, trimmed, lowerHost, EndpointClass.OFFICIAL_API, true, LABEL_OFFICIAL,
                    null);
        }
        ForbricLog.debug("%s is a user-supplied endpoint (%s), used as configured; only the addresses this build"
                + " knows about are recognised, so an unknown proxy is not vouched for", name, identityOf(trimmed));
        return new Endpoint(where, name, trimmed, lowerHost, EndpointClass.CUSTOM, true, label, null);
    }


    public static String identityOf(String url) {
        if (url == null || url.isBlank()) {
            return "unset";
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(url.trim());
        } catch (RuntimeException e) {
            return "invalid";
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isEmpty()
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return "invalid";
        }
        String lowerScheme = scheme.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        sb.append(lowerScheme).append("://");
        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            sb.append('#').append(shortDigest(userInfo)).append('@');
        }
        sb.append(host.toLowerCase(Locale.ROOT));
        int port = uri.getPort();
        int defaultPort = "https".equals(lowerScheme) ? 443 : 80;
        if (port > 0 && port != defaultPort) {
            sb.append(':').append(port);
        }
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        sb.append(path);
        String query = uri.getRawQuery();
        if (query != null && !query.isEmpty()) {
            List<String> pairs = new ArrayList<>();
            for (String pair : query.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int equals = pair.indexOf('=');
                String parameter = equals < 0 ? pair : pair.substring(0, equals);
                String value = equals < 0 ? "" : pair.substring(equals + 1);
                pairs.add(parameter + "=#" + shortDigest(value));
            }
            Collections.sort(pairs);
            sb.append('?').append(String.join("&", pairs));
        }
        return sb.toString();
    }


    private static String shortDigest(String value) {
        return Hashes.sha256(value).substring(0, 12);
    }


    public static String removedServiceOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String host;
        try {
            host = java.net.URI.create(url.trim()).getHost();
        } catch (RuntimeException e) {
            return null;
        }
        if (host == null) {
            return null;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        for (String suffix : REMOVED_HOST_SUFFIXES) {
            if (lower.equals(suffix) || lower.endsWith("." + suffix)) {
                return suffix;
            }
        }
        return null;
    }
}
