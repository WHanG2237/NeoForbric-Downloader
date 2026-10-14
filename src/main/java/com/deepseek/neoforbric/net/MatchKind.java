package com.deepseek.neoforbric.net;


public enum MatchKind {

    HASH(true, "sha1 lookup"),
    FINGERPRINT(true, "fingerprint match"),
    PORT_MAP(false, "port map"),
    EXACT_NAME(false, "exact name"),
    PREFIX(false, "name prefix"),
    TOP_HIT(false, "top hit"),
    MODID_LOOKUP(false, "modid lookup"),
    LATEST(false, "latest"),
    DEPENDENCY(false, "pinned by dependency");

    private final boolean contentProof;
    private final String label;

    MatchKind(boolean contentProof, String label) {
        this.contentProof = contentProof;
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean contentProof() {
        return contentProof;
    }

    public boolean needsIdentityCheck() {
        return !contentProof && this != PORT_MAP;
    }
}
