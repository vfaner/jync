package com.qqmu.jync.service.version;

/**
 * Remembers which published package an admin already acknowledged.
 *
 * <p>Exists because the same-version "the package was re-published" notice has no natural end:
 * a locally rebuilt jar or a mirror that lags never converges on the published bytes, so
 * without an acknowledgement the notice would be permanent — and a badge that never goes away
 * stops being read. The stored key is {@link ReleaseFeed.Asset#identity()}, so the next upload
 * (new digest, or a new size on Gitee) is announced again without any cleanup step.
 *
 * <p>One accepted wrinkle: GitHub's key is a digest and Gitee's is a byte count, so if a host
 * dismisses while only Gitee answers and GitHub comes back later, the notice appears once more
 * under the new key. Re-dismissing silences it; that is cheaper than inventing a key both
 * platforms can agree on.
 */
public interface ArtifactDismissal {

    /** The identity an admin dismissed, or null when nothing has been dismissed. */
    String dismissedKey();

    /** Records {@code artifactKey} as acknowledged, replacing any earlier dismissal. */
    void dismiss(String artifactKey);
}
