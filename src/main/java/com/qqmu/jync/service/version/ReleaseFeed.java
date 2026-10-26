package com.qqmu.jync.service.version;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Source of published-release metadata. Split out from {@link VersionService} so tests can
 * feed canned releases without opening a socket.
 */
public interface ReleaseFeed {

    /**
     * The fixed release asset name; the version lives in the tag, not the file name, so an
     * upgrade can always find "the jar" without knowing which version it is replacing.
     */
    String JAR_ASSET_NAME = "jync.jar";

    /** Which hosting platform a feed talks to; also drives the in-app upgrade download. */
    enum Source {
        GITHUB,
        GITEE;

        /** Brand name for user-facing text; a proper noun, so it is never translated. */
        public String displayName() {
            return this == GITHUB ? "GitHub" : "Gitee";
        }
    }

    /**
     * A mirror refused an anonymous API call. Both platforms rate-limit unauthenticated
     * requests per IP (Gitee answers 403 "Rate Limit Exceeded", GitHub 403/429), and a bare
     * "Gitee API responded 403" gives an operator nothing to act on — this type lets the
     * upgrade dialog name the mirror and point at the other download source instead.
     */
    class RateLimitedException extends Exception {
        private final Source source;
        private final int statusCode;

        public RateLimitedException(Source source, int statusCode) {
            super(source.displayName() + " API refused the anonymous request (HTTP "
                    + statusCode + ")");
            this.source = source;
            this.statusCode = statusCode;
        }

        public Source source() {
            return source;
        }

        public int statusCode() {
            return statusCode;
        }
    }

    /**
     * Maps a non-200 response onto the exception the caller should see. Shared by the HTTP
     * feeds so the classification cannot drift between them.
     */
    static Exception apiFailure(Source source, int statusCode) {
        if (statusCode == 403 || statusCode == 429) {
            return new RateLimitedException(source, statusCode);
        }
        return new IllegalStateException(source.displayName() + " API responded " + statusCode);
    }

    /**
     * One downloadable release asset. Size is known on GitHub but absent from Gitee's API; the
     * same goes for {@code sha256}, which GitHub reports in each asset's {@code digest} field.
     * A null {@code sha256} means "this feed published no checksum", not "the checksum is wrong".
     *
     * <p>{@code updatedAt} is the asset's own upload time, not the release's publish time: a
     * re-upload under an existing tag moves the former and leaves the latter behind, which is
     * exactly the case this field exists to surface.
     */
    record Asset(String name, String url, Long sizeBytes, String sha256, Instant updatedAt) {

        /** Back-compat ctor for feeds/tests that carry no checksum. */
        public Asset(String name, String url, Long sizeBytes) {
            this(name, url, sizeBytes, null, null);
        }

        /** Back-compat ctor for feeds/tests that carry a checksum but no upload time. */
        public Asset(String name, String url, Long sizeBytes, String sha256) {
            this(name, url, sizeBytes, sha256, null);
        }

        /**
         * Stable identity of the published bytes: the digest when the feed reports one, the
         * byte count otherwise. Used both to compare against the running jar and to remember
         * what an admin already dismissed — so that a dismissal silences this upload only, and
         * the next upload (a new digest, or a new size on Gitee) speaks up again.
         *
         * @return {@code "sha256:<hex>"}, {@code "size:<bytes>"}, or null when neither is known
         */
        public String identity() {
            if (sha256 != null && !sha256.isBlank()) {
                return "sha256:" + sha256.trim().toLowerCase(Locale.ROOT);
            }
            return sizeBytes == null ? null : "size:" + sizeBytes;
        }
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
