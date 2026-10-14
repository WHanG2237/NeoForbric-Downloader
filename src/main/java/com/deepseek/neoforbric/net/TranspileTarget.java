package com.deepseek.neoforbric.net;


public record TranspileTarget(ProjectRef project, String versionNumber, String fileName, String downloadUrl,
        String sha1, long size, boolean sameVersion, String referenceVersion, MatchKind howFound) {

    public String describe() {
        return String.format("%s (file %s, %d bytes, source %s)", versionNumber, fileName, size, project.source());
    }

    public TranspileTarget withHowFound(MatchKind kind) {
        return new TranspileTarget(project, versionNumber, fileName, downloadUrl, sha1, size, sameVersion,
                referenceVersion, kind);
    }
}
