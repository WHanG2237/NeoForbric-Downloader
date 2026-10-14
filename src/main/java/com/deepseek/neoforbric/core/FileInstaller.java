package com.deepseek.neoforbric.core;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public final class FileInstaller {

    public enum Step {
        VALIDATE,
        CONFLICT,
        PROTECT,
        PLACE,
        FINALIZE
    }


    @FunctionalInterface
    interface Move {

        void move(Path from, Path to, CopyOption... options) throws IOException;
    }


    public record Request(Path staging, Path original, String targetFileName, boolean keepName, boolean backup) {

        public static Request replacing(Path staging, Path original, String targetFileName, boolean keepName,
                boolean backup) {
            return new Request(staging, original, targetFileName, keepName, backup);
        }
    }


    public record Restore(boolean ok, Path location, String detail) {}


    public record Outcome(boolean ok, boolean reused, Path installedPath, Path backupPath, Path rollbackLeftover,
            Step failedStep, String detail, List<String> steps, Path unrestored) {

        public String describeSteps() {
            return String.join("; ", steps);
        }
    }

    private final Path modsDir;
    private final Path workDir;
    private final InstallJournal journal;
    private final Move mover;

    public FileInstaller(Path modsDir, Path workDir) {
        this(modsDir, workDir, new InstallJournal(workDir), Files::move);
    }

    public FileInstaller(Path modsDir, Path workDir, InstallJournal journal) {
        this(modsDir, workDir, journal, Files::move);
    }

    FileInstaller(Path modsDir, Path workDir, InstallJournal journal, Move mover) {
        this.modsDir = modsDir.toAbsolutePath().normalize();
        this.workDir = workDir.toAbsolutePath().normalize();
        this.journal = journal == null ? new InstallJournal(workDir) : journal;
        this.mover = mover == null ? Files::move : mover;
    }


    public InstallJournal journal() {
        return journal;
    }


    public Outcome install(Request request) {
        List<String> steps = new ArrayList<>(4);
        Path staging = request.staging();
        Path original = request.original();
        if (staging == null || !Files.isRegularFile(staging)) {
            return failure(Step.VALIDATE, "the staged file is missing: " + staging, steps);
        }
        if (original == null || !Files.isRegularFile(original)) {
            return failure(Step.VALIDATE, "the original file is missing: " + original, steps);
        }

        Path destination;
        if (request.keepName() || request.targetFileName() == null || request.targetFileName().isBlank()) {
            destination = original;
        } else {
            destination = resolveTarget(modsDir, request.targetFileName());
            if (destination == null) {
                return failure(Step.VALIDATE, "the source returned an unusable file name: '"
                        + request.targetFileName() + "'", steps);
            }
        }
        steps.add("target path " + destination.getFileName());

        if (!destination.equals(original) && Files.exists(destination)) {
            if (sameContent(destination, staging)) {
                deleteQuietly(staging);
                steps.add("the target already holds the same file, reused it");
                return new Outcome(true, true, destination, null, null, null, "", List.copyOf(steps), null);
            }
            return failure(Step.CONFLICT, "target " + destination.getFileName()
                    + " already exists and is a different file (it provides " + identity(destination)
                    + ", the new file provides " + identity(staging)
                    + "); both files were left untouched", steps);
        }

        Path backupPath = null;
        Path rollbackPath = null;
        try {
            if (request.backup()) {
                backupPath = workDir.resolve("backup").resolve(original.getFileName());
                Files.createDirectories(backupPath.getParent());
            } else {
                rollbackPath = workDir.resolve("rollback").resolve(original.getFileName());
                Files.createDirectories(rollbackPath.getParent());
            }
        } catch (IOException e) {
            return new Outcome(false, false, null, backupPath, rollbackPath, Step.PROTECT,
                    "could not move the original out of the way: " + e + "; the original is still in place",
                    List.copyOf(steps), null);
        }

        InstallJournal.Entry transaction;
        try {
            transaction = journal.open(InstallJournal.Kind.REPLACE, original, destination, staging, backupPath,
                    rollbackPath, Hashes.sha1(original), Hashes.sha1(staging));
        } catch (IOException e) {
            return new Outcome(false, false, null, backupPath, rollbackPath, Step.PROTECT,
                    "could not record the installation before touching any file: " + e
                            + "; the original is still in place",
                    List.copyOf(steps), null);
        }
        steps.add("recorded the installation, it can be recovered after a crash");

        try {
            if (request.backup()) {
                mover.move(original, backupPath, StandardCopyOption.REPLACE_EXISTING);
                steps.add("moved the original to " + backupPath.getFileName());
            } else {
                mover.move(original, rollbackPath, StandardCopyOption.REPLACE_EXISTING);
                steps.add("moved the original to a temporary rollback file");
            }
        } catch (IOException e) {
            if (Files.isRegularFile(original)) {
                journal.close(transaction);
                return new Outcome(false, false, null, backupPath, rollbackPath, Step.PROTECT,
                        "could not move the original out of the way: " + e + "; the original is still in place",
                        List.copyOf(steps), null);
            }
            Path where = protectedLocation(original, backupPath, rollbackPath);
            boolean known = !where.equals(original);
            steps.add("kept the installation record: the original is no longer in place");
            return new Outcome(false, false, null, backupPath, rollbackPath, Step.PROTECT,
                    "could not move the original out of the way: " + e + "; the original is no longer at "
                            + original + (known ? ", it is at " + where : " and no copy of it was found")
                            + "; the installation record was kept and the next start will try to put it back",
                    List.copyOf(steps), where);
        }
        journal.phase(transaction, InstallJournal.Phase.PROTECTED);

        try {
            mover.move(staging, destination);
            steps.add("placed the new file at " + destination.getFileName());
        } catch (IOException e) {
            Restore restored = restore(original, backupPath, rollbackPath);
            if (restored.ok()) {
                journal.close(transaction);
                return new Outcome(false, false, null, backupPath, rollbackPath, Step.PLACE,
                        "could not place the new file: " + e + "; " + restored.detail(),
                        List.copyOf(steps), null);
            }
            steps.add("kept the installation record: the original could not be put back");
            return new Outcome(false, false, null, backupPath, rollbackPath, Step.PLACE,
                    "could not place the new file: " + e + "; the original could not be restored: "
                            + restored.detail() + "; the installation record was kept and the next start will try "
                            + "to restore it again",
                    List.copyOf(steps), restored.location());
        }
        journal.phase(transaction, InstallJournal.Phase.PLACED);

        Path leftover = null;
        if (rollbackPath != null) {
            if (deleteQuietly(rollbackPath)) {
                steps.add("removed the temporary rollback file");
            } else {
                leftover = rollbackPath;
                steps.add("the temporary rollback file could not be removed, it is still at " + rollbackPath);
            }
        }
        journal.close(transaction);
        return new Outcome(true, false, destination, backupPath, leftover, null, "", List.copyOf(steps), null);
    }


    public Outcome installNew(Path staging, String targetFileName) {
        List<String> steps = new ArrayList<>(3);
        if (staging == null || !Files.isRegularFile(staging)) {
            return failure(Step.VALIDATE, "the staged file is missing: " + staging, steps);
        }
        Path destination = resolveTarget(modsDir, targetFileName);
        if (destination == null) {
            return failure(Step.VALIDATE, "the source returned an unusable file name: '" + targetFileName + "'", steps);
        }
        if (Files.exists(destination)) {
            if (sameContent(destination, staging)) {
                deleteQuietly(staging);
                steps.add("the target already holds the same file, reused it");
                return new Outcome(true, true, destination, null, null, null, "", List.copyOf(steps), null);
            }
            return failure(Step.CONFLICT, "target " + destination.getFileName()
                    + " already exists and is a different file (it provides " + identity(destination)
                    + ", the new file provides " + identity(staging) + "); nothing was overwritten", steps);
        }
        try {
            Files.move(staging, destination);
            steps.add("placed the new file at " + destination.getFileName());
        } catch (IOException e) {
            return new Outcome(false, false, null, null, null, Step.PLACE,
                    "could not place the new file: " + e, List.copyOf(steps), null);
        }
        return new Outcome(true, false, destination, null, null, null, "", List.copyOf(steps), null);
    }


    private Restore restore(Path original, Path backupPath, Path rollbackPath) {
        Path source = rollbackPath != null ? rollbackPath : backupPath;
        if (source == null || !Files.exists(source)) {
            return new Restore(false, original, "no rollback copy was available, the original is gone");
        }
        try {
            mover.move(source, original, StandardCopyOption.REPLACE_EXISTING);
            return new Restore(true, original, "the original was restored from " + source);
        } catch (IOException e) {
            return new Restore(false, source, "the original is still at " + source
                    + " (restoring it failed: " + e + ")");
        }
    }


    private static Path protectedLocation(Path original, Path backupPath, Path rollbackPath) {
        if (rollbackPath != null && Files.isRegularFile(rollbackPath)) {
            return rollbackPath;
        }
        if (backupPath != null && Files.isRegularFile(backupPath)) {
            return backupPath;
        }
        return original;
    }


    private static Outcome failure(Step step, String detail, List<String> steps) {
        return new Outcome(false, false, null, null, null, step, detail, List.copyOf(steps), null);
    }


    public static Path resolveTarget(Path modsDir, String fileName) {
        if (!isValidFileName(fileName)) {
            return null;
        }
        Path base = modsDir.toAbsolutePath().normalize();
        Path candidate = base.resolve(fileName).normalize();
        if (candidate.getParent() == null || !candidate.getParent().equals(base)) {
            return null;
        }
        if (!candidate.getFileName().toString().equals(fileName)) {
            return null;
        }
        return candidate;
    }


    public static boolean isValidFileName(String name) {
        if (name == null || name.isEmpty() || !name.equals(name.trim())) {
            return false;
        }
        if (name.equals(".") || name.equals("..")) {
            return false;
        }
        if (name.length() > 200) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>'
                    || c == '|') {
                return false;
            }
        }
        return name.toLowerCase(Locale.ROOT).endsWith(".jar");
    }


    public static boolean sameContent(Path first, Path second) {
        try {
            String left = Hashes.sha1(first);
            String right = Hashes.sha1(second);
            return left.equalsIgnoreCase(right);
        } catch (IOException e) {
            ForbricLog.warn("content compare failed (%s vs %s): %s", first.getFileName(), second.getFileName(),
                    e.toString());
            return false;
        }
    }


    private static String identity(Path jar) {
        try {
            List<String> ids = ModJarScanner.providedModIds(jar);
            return ids.isEmpty() ? "no declared modid" : String.join("/", ids);
        } catch (IOException e) {
            return "unreadable (" + e + ")";
        }
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
