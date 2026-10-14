package com.deepseek.neoforbric.core;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


public final class ProgressStage {

    private final ProgressReporter reporter;
    private final String id;
    private final String label;
    private final AtomicInteger total = new AtomicInteger();
    private final AtomicInteger done = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger kept = new AtomicInteger();
    private final AtomicBoolean ended = new AtomicBoolean();
    private final java.util.Set<String> terminalTasks = new java.util.HashSet<>();

    public ProgressStage(ProgressReporter reporter, String id, String label, int totalSteps) {
        this.reporter = reporter == null ? ProgressReporter.NOOP : reporter;
        this.id = id + ":" + java.util.UUID.randomUUID();
        this.label = label == null ? "" : label;
        this.total.set(Math.max(0, totalSteps));
        this.reporter.stageBegin(this.id, this.label, this.total.get());
    }


    public String id() {
        return id;
    }


    public String label() {
        return label;
    }


    public int total() {
        return total.get();
    }


    public int finished() {
        return done.get() + failed.get() + skipped.get() + kept.get();
    }


    public void expectMore(int additional) {
        if (additional <= 0 || ended.get() || !reporter.stageActive(id)) {
            return;
        }
        reporter.stageTotal(id, label, total.addAndGet(additional));
    }


    public void done(String taskLabel) {
        count(done, taskLabel);
    }


    public void message(String text) {
        if (!active() || text == null || text.isBlank()) {
            return;
        }
        reporter.message(text);
    }


    public void failed(String taskLabel) {
        count(failed, taskLabel);
    }


    public void skipped(String taskLabel) {
        count(skipped, taskLabel);
    }


    public void kept(String taskLabel) {
        count(kept, taskLabel);
    }


    public void done(String taskId, String taskLabel) { count(done, taskId, taskLabel); }
    public void failed(String taskId, String taskLabel) { count(failed, taskId, taskLabel); }
    public void skipped(String taskId, String taskLabel) { count(skipped, taskId, taskLabel); }
    public void kept(String taskId, String taskLabel) { count(kept, taskId, taskLabel); }

    private void count(AtomicInteger counter, String taskLabel) { count(counter, null, taskLabel); }

    private synchronized void count(AtomicInteger counter, String taskId, String taskLabel) {
        if (ended.get() || !reporter.stageActive(id)) {
            return;
        }
        if (taskId != null && !terminalTasks.add(taskId)) {
            return;
        }
        counter.incrementAndGet();
        if (taskLabel != null && !taskLabel.isBlank()) {
            reporter.message(taskLabel);
        }
        reporter.stageState(id, label, done.get(), failed.get(), skipped.get(), kept.get(), total.get());
    }


    public synchronized void end() {
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        reporter.stageEnd(id, label, done.get(), failed.get(), skipped.get(), kept.get());
    }


    public boolean active() {
        return !ended.get() && reporter.stageActive(id);
    }


    public ProgressReporter reporter() {
        return reporter;
    }


    public void downloadEvent(DownloadEvent event) {
        if (!active() || event == null) {
            return;
        }
        reporter.downloadEvent(event);
    }
}
