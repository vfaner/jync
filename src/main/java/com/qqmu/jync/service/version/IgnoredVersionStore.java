package com.qqmu.jync.service.version;

/**
 * Remembers the release an admin chose to ignore on the dashboard's "new version" dialog.
 *
 * <p>The acknowledgement is by version, not by package bytes: once 2.1.0 is ignored, every
 * check that still finds 2.1.0 (or an older release) stays quiet, and the first release above
 * it brings the dialog back. That is deliberately different from {@link ArtifactDismissal},
 * which tracks the exact published asset under an unchanged version.
 */
public interface IgnoredVersionStore {

    /** The ignored version without its leading {@code v}, or null when nothing is ignored. */
    String ignoredVersion();

    /**
     * True when {@code latestVersion} is the ignored version or older, so the dialog must not
     * appear. A newer release answers false and is announced again.
     */
    boolean isIgnored(String latestVersion);

    /** Records {@code version} as ignored. Blank or malformed input records nothing. */
    boolean ignore(String version);
}
