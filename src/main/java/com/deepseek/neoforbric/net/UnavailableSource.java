package com.deepseek.neoforbric.net;

import com.deepseek.neoforbric.core.ModJarInfo;


public final class UnavailableSource implements ModSource {

    private final String reason;

    public UnavailableSource(String reason) {
        this.reason = reason == null || reason.isBlank()
                ? "no download source is enabled, so nothing was queried"
                : reason;
    }

    public String reason() {
        return reason;
    }

    @Override
    public String name() {
        return "no enabled source";
    }

    @Override
    public QueryResult<TranspileTarget> find(ModJarInfo jar, boolean fallbackToLatest, String knownSha1,
            boolean strictSearch) {
        return QueryResult.configError(reason);
    }

    @Override
    public QueryResult<TranspileTarget> latest(String projectIdOrSlug, String loader) {
        return QueryResult.configError(reason);
    }

    @Override
    public QueryResult<TranspileTarget> forModId(String modId, String displayName, boolean strict) {
        return QueryResult.configError(reason);
    }
}
