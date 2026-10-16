package com.deepseek.neoforbric.net;

import com.deepseek.neoforbric.core.ModJarInfo;


public final class RefusedSource implements ModSource {

    private final String key;
    private final String reason;

    public RefusedSource(String key, String reason) {
        this.key = key == null || key.isBlank() ? "the endpoint address" : key.trim();
        this.reason = reason == null || reason.isBlank() ? "the configured endpoint is refused" : reason;
    }

    @Override
    public String name() {
        return "refused endpoint (" + key + ")";
    }

    public String reason() {
        return reason;
    }

    @Override
    public QueryResult<TranspileTarget> find(ModJarInfo jar, boolean fallbackToLatest, String knownSha1,
            boolean strictSearch) {
        return QueryResult.restricted(reason);
    }

    @Override
    public QueryResult<TranspileTarget> latest(String projectIdOrSlug, String loader) {
        return QueryResult.restricted(reason);
    }

    @Override
    public QueryResult<TranspileTarget> forModId(String modId, String displayName, boolean strict) {
        return QueryResult.restricted(reason);
    }
}
