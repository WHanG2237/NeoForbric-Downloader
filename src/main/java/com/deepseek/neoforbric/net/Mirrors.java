package com.deepseek.neoforbric.net;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;


public final class Mirrors {

    public record Rule(String officialPrefix, List<String> mirrorPrefixes, boolean mirrorFirst) {
        public Rule(String officialPrefix, List<String> mirrorPrefixes) {
            this(officialPrefix, mirrorPrefixes, false);
        }
    }


    public record Candidate(String url, boolean mirror) {}

    private static final List<Rule> RULES = List.of(

            new Rule("https://api.modrinth.com/v2", List.of(
                    "https://mod.mcimirror.top/modrinth/v2")),


            new Rule("https://cdn.modrinth.com", List.of(
                    "https://cdn-raw.modrinth.com"), true),


            new Rule("https://edge.forgecdn.net", List.of(
                    "https://mediafilez.forgecdn.net"), true),
            new Rule("https://mediafilez.forgecdn.net", List.of(
                    "https://edge.forgecdn.net")),

            new Rule("https://github.com", List.of(
                    "https://gh-proxy.com/https://github.com")),
            new Rule("https://raw.githubusercontent.com", List.of(
                    "https://gh-proxy.com/https://raw.githubusercontent.com")));

    private Mirrors() {}


    public static List<Candidate> candidates(String officialUrl, boolean enabled) {
        LinkedHashSet<String> mirrors = new LinkedHashSet<>();
        boolean mirrorFirst = false;
        if (enabled) {
            for (Rule rule : RULES) {
                if (officialUrl.startsWith(rule.officialPrefix())) {
                    for (String mirror : rule.mirrorPrefixes()) {
                        mirrors.add(mirror + officialUrl.substring(rule.officialPrefix().length()));
                    }
                    mirrorFirst |= rule.mirrorFirst();
                }
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
            result.add(new Candidate(url, mirrors.contains(url)));
        }
        return result;
    }


    public static Candidate official(String url) {
        return new Candidate(url, false);
    }
}
