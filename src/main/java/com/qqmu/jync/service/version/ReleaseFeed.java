package com.qqmu.jync.service.version;

import java.time.Instant;
import java.util.List;

/**
 * Source of published-release metadata. Split out from {@link VersionService} so tests can
 * feed canned releases without opening a socket.
 */
public interface ReleaseFeed {

    /** Which hosting platform a feed talks to; also drives the in-app upgrade download. */
    enum Source {
        GITHUB,
        GITEE
    }

    /** One downloadable release asset. Size is known on GitHub but absent from Gitee's API. */
    record Asset(String name, String url, Long sizeBytes) {
    }

    /** One release's relevant fields. {@code source} says which feed produced it. */
    record Release(String tag, String htmlUrl, Instant publishedAt, String body,
                   List<Asset> assets, Source source) {

        /** Back-compact ctor for feeds/tests without assets (source stays unknown). */
        public Release(String tag, String htmlUrl, Instant publishedAt, String body) {
            this(tag, htmlUrl, publishedAt, body, List.of(), null);
        }

        /** The fixed-name jar asset ({@code jync.jar}), case-insensitive, or {@code null}. */
        public Asset jarAsset(String jarName) {
            for (Asset asset : assets) {
                if (asset.name() != null && asset.name().equalsIgnoreCase(jarName)) {
                    return asset;
                }
            }
            return null;
        }
    }

    /** The newest published release (regardless of prerelease/draft flags the API applies). */
    Release latest() throws Exception;

    /** A specific release identified by its tag, e.g. {@code v1.1.1}. */
    Release byTag(String tag) throws Exception;
}
