package com.deepseek.neoforbric.core;


public record DownloadEvent(String stageId, String taskId, String label, int attempt, long received, long total,
        State state, String detail) {

    public enum State {
        STARTED,
        PROGRESS,
        DONE,
        FAILED,
        SKIPPED
    }


    public boolean totalKnown() {
        return total > 0;
    }


    public double fraction() {
        if (total <= 0) {
            return -1.0;
        }
        double value = (double) received / (double) total;
        if (value < 0) {
            return 0.0;
        }
        return value > 1.0 ? 1.0 : value;
    }


    public boolean terminal() {
        return state == State.DONE || state == State.FAILED || state == State.SKIPPED;
    }


    public DownloadEvent withState(State newState, long newReceived, String newDetail) {
        return new DownloadEvent(stageId, taskId, label, attempt, newReceived, total, newState, newDetail);
    }
}
