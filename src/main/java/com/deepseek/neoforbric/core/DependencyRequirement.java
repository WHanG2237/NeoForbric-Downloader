package com.deepseek.neoforbric.core;


public record DependencyRequirement(String modId, String versionRange, String side, boolean mandatory, String type) {

    public static final String SIDE_BOTH = "BOTH";

    public DependencyRequirement(String modId) {
        this(modId, "", SIDE_BOTH, true, "required");
    }

    public boolean hasVersionRange() {
        return versionRange != null && !versionRange.isBlank();
    }
}
