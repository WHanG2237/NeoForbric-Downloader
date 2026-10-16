package com.deepseek.neoforbric.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


public final class DuplicateJars {

    public record Conflict(ModJarInfo winner, List<String> sharedModIds, List<String> groupModIds,
            List<String> uncoveredModIds) {

        public Conflict {
            sharedModIds = List.copyOf(sharedModIds);
            groupModIds = List.copyOf(groupModIds);
            uncoveredModIds = List.copyOf(uncoveredModIds);
        }


        public List<String> requiredIds() {
            return sharedModIds.isEmpty() ? groupModIds : sharedModIds;
        }


        public boolean needsEveryId() {
            return !sharedModIds.isEmpty();
        }


        public boolean satisfiedBy(List<String> providedIds) {
            List<String> required = requiredIds();
            if (required.isEmpty()) {
                return true;
            }
            boolean any = false;
            for (String id : required) {
                boolean found = false;
                for (String provided : providedIds) {
                    if (provided != null && provided.equalsIgnoreCase(id)) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    any = true;
                } else if (needsEveryId()) {
                    return false;
                }
            }
            return any;
        }


        public String describeIds() {
            List<String> ids = sharedModIds.isEmpty() ? groupModIds : sharedModIds;
            return ids.isEmpty() ? "no shared modid" : String.join("/", ids);
        }
    }


    public record NativeOverlap(ModJarInfo jar, List<String> sharedIds, List<String> uncoveredIds,
            List<ModJarInfo> providers) {

        public NativeOverlap {
            sharedIds = List.copyOf(sharedIds);
            uncoveredIds = List.copyOf(uncoveredIds);
            providers = List.copyOf(providers);
        }


        public String describeIds() {
            return sharedIds.isEmpty() ? "no shared modid" : String.join("/", sharedIds);
        }


        public String describeProviders() {
            StringBuilder sb = new StringBuilder();
            for (ModJarInfo provider : providers) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(provider.fileName()).append(" [").append(provider.kind().display()).append(' ')
                        .append(provider.version() == null || provider.version().isBlank() ? "?" : provider.version())
                        .append(", id=")
                        .append(provider.allModIds().isEmpty() ? "?" : String.join("/", provider.allModIds()))
                        .append("] at ").append(provider.path());
            }
            return sb.length() == 0 ? "no native file" : sb.toString();
        }
    }


    public record Plan(Set<String> nativeModIds, List<ModJarInfo> skipped, List<ModJarInfo> processable,
            Map<String, Conflict> losers, Map<String, NativeOverlap> nativeOverlaps) {


        public boolean isSkipped(String fileName) {
            for (ModJarInfo jar : skipped) {
                if (jar.fileName().equals(fileName)) {
                    return true;
                }
            }
            return false;
        }


        public boolean isLoser(String fileName) {
            return losers.containsKey(fileName);
        }

        public ModJarInfo winnerOf(String fileName) {
            Conflict conflict = losers.get(fileName);
            return conflict == null ? null : conflict.winner();
        }

        public Conflict conflictOf(String fileName) {
            return losers.get(fileName);
        }
    }

    private DuplicateJars() {}


    public static Plan plan(List<ModJarInfo> jars) {
        return plan(jars, jar -> null);
    }


    public static Plan plan(List<ModJarInfo> jars, java.util.function.Function<ModJarInfo, String> contentHash) {
        Set<String> nativeModIds = new LinkedHashSet<>();
        for (ModJarInfo jar : jars) {
            if (jar.kind() != ModKind.NEOFORGE) {
                continue;
            }
            for (String id : jar.allModIds()) {
                if (id != null && !id.isBlank()) {
                    nativeModIds.add(lower(id));
                }
            }
        }

        List<ModJarInfo> natives = new ArrayList<>();
        for (ModJarInfo jar : jars) {
            if (jar.kind() == ModKind.NEOFORGE && !jar.allModIds().isEmpty()) {
                natives.add(jar);
            }
        }

        List<ModJarInfo> skipped = new ArrayList<>();
        Map<String, NativeOverlap> nativeOverlaps = new LinkedHashMap<>();
        List<ModJarInfo> candidates = new ArrayList<>();
        for (ModJarInfo jar : jars) {
            if (!transpilable(jar)) {
                continue;
            }
            if (sharesAnyId(jar, nativeModIds)) {
                skipped.add(jar);
                nativeOverlaps.put(jar.fileName(), overlapOf(jar, natives, nativeModIds));
                continue;
            }
            candidates.add(jar);
        }

        Map<String, Conflict> losers = new LinkedHashMap<>();
        addTranspiledDuplicates(candidates, losers);
        addNativeDuplicates(jars, contentHash, losers);

        List<ModJarInfo> processable = new ArrayList<>();
        for (ModJarInfo jar : candidates) {
            if (!losers.containsKey(jar.fileName())) {
                processable.add(jar);
            }
        }
        return new Plan(Set.copyOf(nativeModIds), List.copyOf(skipped), List.copyOf(processable),
                Map.copyOf(losers), Map.copyOf(nativeOverlaps));
    }


    private static NativeOverlap overlapOf(ModJarInfo jar, List<ModJarInfo> natives, Set<String> nativeModIds) {
        List<String> shared = new ArrayList<>();
        List<String> uncovered = new ArrayList<>();
        for (String id : jar.allModIds()) {
            if (id == null || id.isBlank()) {
                continue;
            }
            if (nativeModIds.contains(lower(id))) {
                shared.add(id);
            } else {
                uncovered.add(id);
            }
        }
        List<ModJarInfo> providers = new ArrayList<>();
        for (ModJarInfo nativeJar : natives) {
            if (nativeJar.fileName().equals(jar.fileName())) {
                continue;
            }
            for (String id : shared) {
                if (nativeJar.provides(id)) {
                    providers.add(nativeJar);
                    break;
                }
            }
        }
        return new NativeOverlap(jar, shared, uncovered, providers);
    }


    private static void addTranspiledDuplicates(List<ModJarInfo> candidates, Map<String, Conflict> losers) {
        for (List<ModJarInfo> group : connectedGroups(candidates)) {
            if (group.size() < 2) {
                continue;
            }
            ModJarInfo winner = pickWinner(group);
            List<String> winnerIds = winner.allModIds();
            List<String> groupIds = overlappingIds(group);
            for (ModJarInfo jar : group) {
                if (jar == winner) {
                    continue;
                }
                losers.put(jar.fileName(), new Conflict(winner, intersect(jar.allModIds(), winnerIds), groupIds,
                        subtract(jar.allModIds(), winnerIds)));
            }
        }
    }


    private static void addNativeDuplicates(List<ModJarInfo> jars,
            java.util.function.Function<ModJarInfo, String> contentHash, Map<String, Conflict> losers) {
        List<ModJarInfo> natives = new ArrayList<>();
        for (ModJarInfo jar : jars) {
            if (jar.kind() == ModKind.NEOFORGE && !jar.allModIds().isEmpty()) {
                natives.add(jar);
            }
        }
        for (List<ModJarInfo> group : connectedGroups(natives)) {
            if (group.size() < 2) {
                continue;
            }
            Map<String, List<ModJarInfo>> byHash = new LinkedHashMap<>();
            for (ModJarInfo jar : group) {
                String hash = contentHash.apply(jar);
                if (hash == null || hash.isBlank()) {
                    continue;
                }
                byHash.computeIfAbsent(hash, key -> new ArrayList<>()).add(jar);
            }
            for (List<ModJarInfo> same : byHash.values()) {
                if (same.size() < 2) {
                    continue;
                }
                ModJarInfo winner = pickNativeWinner(same);
                List<String> winnerIds = winner.allModIds();
                List<String> groupIds = overlappingIds(same);
                for (ModJarInfo jar : same) {
                    if (jar == winner || losers.containsKey(jar.fileName())) {
                        continue;
                    }
                    losers.put(jar.fileName(), new Conflict(winner, intersect(jar.allModIds(), winnerIds), groupIds,
                            subtract(jar.allModIds(), winnerIds)));
                }
            }
        }
    }


    private static List<List<ModJarInfo>> connectedGroups(List<ModJarInfo> jars) {
        Map<String, List<Integer>> byId = new LinkedHashMap<>();
        for (int i = 0; i < jars.size(); i++) {
            for (String id : jars.get(i).allModIds()) {
                if (id == null || id.isBlank()) {
                    continue;
                }
                byId.computeIfAbsent(lower(id), key -> new ArrayList<>()).add(i);
            }
        }
        int[] parent = new int[jars.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (List<Integer> members : byId.values()) {
            for (int i = 1; i < members.size(); i++) {
                union(parent, members.get(0), members.get(i));
            }
        }
        Map<Integer, List<ModJarInfo>> groups = new LinkedHashMap<>();
        for (int i = 0; i < jars.size(); i++) {
            groups.computeIfAbsent(root(parent, i), key -> new ArrayList<>()).add(jars.get(i));
        }
        return new ArrayList<>(groups.values());
    }


    private static int root(int[] parent, int index) {
        int current = index;
        while (parent[current] != current) {
            parent[current] = parent[parent[current]];
            current = parent[current];
        }
        return current;
    }


    private static void union(int[] parent, int first, int second) {
        int left = root(parent, first);
        int right = root(parent, second);
        if (left != right) {
            parent[Math.max(left, right)] = Math.min(left, right);
        }
    }


    private static List<String> overlappingIds(List<ModJarInfo> group) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> original = new LinkedHashMap<>();
        for (ModJarInfo jar : group) {
            for (String id : jar.allModIds()) {
                if (id == null || id.isBlank()) {
                    continue;
                }
                String key = lower(id);
                counts.merge(key, 1, Integer::sum);
                original.putIfAbsent(key, id);
            }
        }
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > 1) {
                result.add(original.get(entry.getKey()));
            }
        }
        return result;
    }


    private static List<String> intersect(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>();
        for (String id : first) {
            if (id == null || id.isBlank()) {
                continue;
            }
            for (String other : second) {
                if (other != null && other.equalsIgnoreCase(id)) {
                    result.add(id);
                    break;
                }
            }
        }
        return result;
    }


    private static List<String> subtract(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>();
        for (String id : first) {
            if (id == null || id.isBlank()) {
                continue;
            }
            boolean found = false;
            for (String other : second) {
                if (other != null && other.equalsIgnoreCase(id)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                result.add(id);
            }
        }
        return result;
    }


    private static boolean sharesAnyId(ModJarInfo jar, Set<String> ids) {
        for (String id : jar.allModIds()) {
            if (id != null && !id.isBlank() && ids.contains(lower(id))) {
                return true;
            }
        }
        return false;
    }


    private static ModJarInfo pickNativeWinner(List<ModJarInfo> group) {
        ModJarInfo best = group.get(0);
        for (ModJarInfo jar : group) {
            if (looksFabricNamed(jar) != looksFabricNamed(best) && !looksFabricNamed(jar)) {
                best = jar;
            }
        }
        return best;
    }

    private static boolean looksFabricNamed(ModJarInfo jar) {
        String name = jar.fileName().toLowerCase(Locale.ROOT);
        return name.contains("fabric") || name.contains("quilt");
    }

    private static boolean transpilable(ModJarInfo jar) {
        return jar.kind() == ModKind.FORGE || jar.kind() == ModKind.FABRIC || jar.kind() == ModKind.QUILT;
    }


    private static ModJarInfo pickWinner(List<ModJarInfo> group) {
        ModJarInfo best = group.get(0);
        for (ModJarInfo jar : group) {
            if (rank(jar) < rank(best)) {
                best = jar;
            }
        }
        return best;
    }

    private static int rank(ModJarInfo jar) {
        return switch (jar.kind()) {
            case FORGE -> 0;
            case FABRIC -> 1;
            default -> 2;
        };
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
