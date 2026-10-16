package com.deepseek.neoforbric.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;


public final class InstallJournal {

    public static final String DIR_NAME = "journal";

    private static final String VERSION = "1";

    private static final String SUFFIX = ".properties";

    private static final String WRITING_SUFFIX = ".writing";


    public enum Kind {
        REPLACE,
        REMOVE
    }


    public enum Phase {
        PREPARED,
        PROTECTED,
        PLACED
    }


    public record Entry(Path file, Kind kind, Phase phase, Path original, Path target, Path staging, Path backup,
            Path rollback, String originalSha1, String newSha1) {}


    public record Recovery(int restored, int completed, int untouched, int records, List<String> notes,
            List<String> problems) {

        public boolean blocked() {
            return !problems.isEmpty();
        }

        public boolean changed() {
            return restored > 0 || completed > 0 || untouched > 0;
        }
    }

    private final Path workDir;

    private final Path journalDir;

    public InstallJournal(Path workDir) {
        this.workDir = workDir.toAbsolutePath().normalize();
        this.journalDir = this.workDir.resolve(DIR_NAME);
    }


    public Path dir() {
        return journalDir;
    }


    public Entry open(Kind kind, Path original, Path target, Path staging, Path backup, Path rollback,
            String originalSha1, String newSha1) throws IOException {
        Path file = journalDir.resolve(UUID.randomUUID().toString() + SUFFIX);
        Entry entry = new Entry(file, kind, Phase.PREPARED, absolute(original), absolute(target), absolute(staging),
                absolute(backup), absolute(rollback), blank(originalSha1), blank(newSha1));
        store(file, render(entry));
        return entry;
    }


    public void phase(Entry entry, Phase phase) {
        Entry updated = new Entry(entry.file(), entry.kind(), phase, entry.original(), entry.target(), entry.staging(),
                entry.backup(), entry.rollback(), entry.originalSha1(), entry.newSha1());
        try {
            store(entry.file(), render(updated));
        } catch (IOException e) {
            ForbricLog.warn("the installation journal could not be updated (%s): %s", entry.file().getFileName(),
                    e.toString());
        }
    }


    public void close(Entry entry) {
        try {
            Files.deleteIfExists(entry.file());
        } catch (IOException e) {
            ForbricLog.warn("the installation journal could not be removed (%s): %s", entry.file().getFileName(),
                    e.toString());
        }
    }


    public Recovery recover() {
        List<String> notes = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        if (!Files.exists(journalDir)) {
            return new Recovery(0, 0, 0, 0, List.copyOf(notes), List.copyOf(problems));
        }
        if (!Files.isDirectory(journalDir)) {
            problems.add("the installation journal folder is not a folder: " + journalDir);
            return new Recovery(0, 0, 0, 0, List.copyOf(notes), List.copyOf(problems));
        }
        List<Path> records;
        try (Stream<Path> stream = Files.list(journalDir)) {
            records = stream.sorted().toList();
        } catch (IOException e) {
            problems.add("the installation journal folder could not be read: " + journalDir + " (" + e + ")");
            return new Recovery(0, 0, 0, 0, List.copyOf(notes), List.copyOf(problems));
        }
        int[] counters = new int[3];
        int count = 0;
        for (Path record : records) {
            String name = record.getFileName().toString();
            if (name.endsWith(WRITING_SUFFIX)) {
                if (deleteQuietly(record)) {
                    notes.add("removed a journal record that was never finished: " + name);
                } else {
                    problems.add("an unfinished journal record could not be removed: " + record);
                }
                continue;
            }
            if (!name.endsWith(SUFFIX)) {
                continue;
            }
            count++;
            Entry entry = parse(record, problems);
            if (entry == null) {
                continue;
            }
            resolve(entry, counters, notes, problems);
        }
        if (count > 0) {
            ForbricLog.info("installation journal: %d unfinished operation(s) found (%d restored, %d already done, "
                    + "%d never started)", count, counters[0], counters[1], counters[2]);
        }
        return new Recovery(counters[0], counters[1], counters[2], count, List.copyOf(notes), List.copyOf(problems));
    }


    private void resolve(Entry entry, int[] counters, List<String> notes, List<String> problems) {
        if (entry.kind() == Kind.REMOVE) {
            resolveRemove(entry, counters, notes, problems);
            return;
        }
        resolveReplace(entry, counters, notes, problems);
    }


    private void resolveRemove(Entry entry, int[] counters, List<String> notes, List<String> problems) {
        Path original = entry.original();
        if (original == null || !Files.exists(original)) {
            if (entry.backup() != null && Files.isRegularFile(entry.backup())) {
                counters[1]++;
                notes.add("an unfinished duplicate removal had already finished: " + name(original));
                deleteJournal(entry, notes);
                return;
            }
            problems.add("the duplicate " + original + " is gone and no backup holds it; "
                    + "check " + workDir + " by hand");
            return;
        }
        counters[2]++;
        notes.add("an unfinished duplicate removal never started: " + name(original));
        deleteJournal(entry, notes);
    }


    private void resolveReplace(Entry entry, int[] counters, List<String> notes, List<String> problems) {
        Path original = entry.original();
        Path target = entry.target() == null ? original : entry.target();
        if (Files.isRegularFile(original) && matches(original, entry.originalSha1())) {
            counters[2]++;
            notes.add("an unfinished replacement never started, the original is still in place: " + name(original));
            cleanUp(entry, notes);
            return;
        }
        if (Files.isRegularFile(target) && matches(target, entry.newSha1())) {
            counters[1]++;
            notes.add("an unfinished replacement had already finished, cleaned up: " + name(target));
            cleanUp(entry, notes);
            return;
        }
        if (!Files.exists(target)) {
            restore(entry, counters, notes, problems);
            return;
        }
        problems.add("the unfinished replacement of " + name(target) + " left a file in mods/ that holds "
                + describe(target, entry) + "; both files were kept and nothing was changed this round");
    }


    private boolean restore(Entry entry, int[] counters, List<String> notes, List<String> problems) {
        Path original = entry.original();
        Path rollback = entry.rollback();
        Path backup = entry.backup();
        if (rollback != null && Files.isRegularFile(rollback)) {
            if (!matches(rollback, entry.originalSha1())) {
                problems.add("the rollback file " + rollback + " does not hold the original content of "
                        + name(original) + ", so it was left alone and nothing was changed this round");
                return false;
            }
            try {
                Files.move(rollback, original, StandardCopyOption.REPLACE_EXISTING);
                counters[0]++;
                notes.add("restored " + name(original) + " from the temporary rollback file");
                deleteJournal(entry, notes);
                return true;
            } catch (IOException e) {
                problems.add("could not restore " + name(original) + " from " + rollback + ": " + e);
                return false;
            }
        }
        if (backup != null && Files.isRegularFile(backup)) {
            if (!matches(backup, entry.originalSha1())) {
                problems.add("the backup " + backup + " does not hold the original content of " + name(original)
                        + ", so it was left alone and nothing was changed this round");
                return false;
            }
            try {
                Files.copy(backup, original, StandardCopyOption.REPLACE_EXISTING);
                counters[0]++;
                notes.add("restored " + name(original) + " from its backup (the backup itself was kept)");
                deleteJournal(entry, notes);
                return true;
            } catch (IOException e) {
                problems.add("could not restore " + name(original) + " from " + backup + ": " + e);
                return false;
            }
        }
        problems.add("the original " + name(original) + " is missing and neither the backup folder nor the "
                + "rollback folder holds a copy of it");
        return false;
    }


    private void cleanUp(Entry entry, List<String> notes) {
        Path rollback = entry.rollback();
        if (rollback != null && Files.isRegularFile(rollback)) {
            if (matches(rollback, entry.originalSha1())) {
                deleteQuietly(rollback);
            } else {
                notes.add("a rollback file that does not match the original was left alone: " + rollback);
            }
        }
        deleteJournal(entry, notes);
    }


    private void deleteJournal(Entry entry, List<String> notes) {
        try {
            Files.deleteIfExists(entry.file());
        } catch (IOException e) {
            notes.add("a finished journal record could not be removed: " + entry.file() + " (" + e + ")");
        }
    }


    private Entry parse(Path record, List<String> problems) {
        Map<String, String> values;
        try {
            values = read(record);
        } catch (IOException e) {
            problems.add("a journal record could not be read: " + record + " (" + e + ")");
            return null;
        }
        Kind kind;
        try {
            kind = Kind.valueOf(values.getOrDefault("kind", "").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problems.add("a journal record has no usable kind: " + record);
            return null;
        }
        Phase phase;
        try {
            phase = Phase.valueOf(values.getOrDefault("phase", "").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            phase = Phase.PREPARED;
        }
        Path original = path(values.get("original"));
        if (original == null) {
            problems.add("a journal record has no original path: " + record);
            return null;
        }
        return new Entry(record, kind, phase, original, path(values.get("target")), path(values.get("staging")),
                path(values.get("backup")), path(values.get("rollback")), values.getOrDefault("originalSha1", ""),
                values.getOrDefault("newSha1", ""));
    }


    private String render(Entry entry) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("# ").append(ForbricLog.TAG).append(" (formerly NeoForbric) file installation record."
                + " One file per unfinished operation.\n");
        sb.append("# Deleting this file removes the record but leaves the files on disk untouched.\n");
        sb.append("version=").append(VERSION).append('\n');
        sb.append("kind=").append(entry.kind().name()).append('\n');
        sb.append("phase=").append(entry.phase().name()).append('\n');
        sb.append("original=").append(text(entry.original())).append('\n');
        sb.append("target=").append(text(entry.target())).append('\n');
        sb.append("staging=").append(text(entry.staging())).append('\n');
        sb.append("backup=").append(text(entry.backup())).append('\n');
        sb.append("rollback=").append(text(entry.rollback())).append('\n');
        sb.append("originalSha1=").append(blank(entry.originalSha1())).append('\n');
        sb.append("newSha1=").append(blank(entry.newSha1())).append('\n');
        return sb.toString();
    }


    private void store(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + WRITING_SUFFIX);
        Files.writeString(temp, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }


    private static Map<String, String> read(Path file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            values.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
        return values;
    }


    private static boolean matches(Path path, String expectedSha1) {
        if (expectedSha1 == null || expectedSha1.isBlank()) {
            return Files.isRegularFile(path);
        }
        try {
            return Hashes.sha1(path).equalsIgnoreCase(expectedSha1);
        } catch (IOException e) {
            return false;
        }
    }


    private static String describe(Path path, Entry entry) {
        String actual;
        try {
            actual = Hashes.sha1(path);
        } catch (IOException e) {
            actual = "unreadable (" + e + ")";
        }
        return "sha1 " + actual + ", which is neither the original (" + orUnknown(entry.originalSha1()) + ") nor the "
                + "new file (" + orUnknown(entry.newSha1()) + ")";
    }


    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }


    private static String name(Path path) {
        return path == null ? "?" : path.getFileName().toString();
    }


    private static String text(Path path) {
        return path == null ? "" : path.toString();
    }


    private static Path absolute(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }


    private static Path path(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Path.of(raw);
    }


    private static String blank(String value) {
        return value == null ? "" : value.trim();
    }


    private static boolean deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
