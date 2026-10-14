package com.deepseek.neoforbric.core;


public interface ProgressReporter {

    ProgressReporter NOOP = new ProgressReporter() {
        @Override
        public void start(String label, int totalSteps) {}

        @Override
        public void message(String englishText) {}

        @Override
        public void step(String englishText) {}

        @Override
        public void phaseDone(String englishText) {}

        @Override
        public void finish() {}
    };


    void start(String label, int totalSteps);


    void message(String englishText);


    void step(String englishText);


    void phaseDone(String englishText);


    void finish();


    default boolean stageActive(String stageId) {
        return true;
    }


    default void stageBegin(String stageId, String label, int totalSteps) {
        start(label, totalSteps);
    }


    default void stageTotal(String stageId, String label, int totalSteps) {}


    default void stageState(String stageId, String label, int done, int failed, int skipped, int kept, int total) {}


    default void stageEnd(String stageId, String label, int done, int failed, int skipped) {
        phaseDone(label);
    }

    default void stageEnd(String stageId, String label, int done, int failed, int skipped, int kept) {
        stageEnd(stageId, label, done, failed, skipped);
    }


    default void downloadEvent(DownloadEvent event) {}


    default void finishAll() {
        finish();
    }
}
