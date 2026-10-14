package com.deepseek.neoforbric.net;

import com.deepseek.neoforbric.core.ModJarInfo;


public interface ModSource {

    String name();


    QueryResult<TranspileTarget> find(ModJarInfo jar, boolean fallbackToLatest, String knownSha1,
            boolean strictSearch);


    QueryResult<TranspileTarget> latest(String projectIdOrSlug, String loader);


    default QueryResult<TranspileTarget> forModId(String modId, String displayName, boolean strict) {
        return QueryResult.notFound(name() + " cannot look up a project by modid");
    }
}
