package com.qqmu.jync.service.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** URL→API-base mapping and the "bad config must not break boot" contract. */
class GithubReleaseFeedTest {

    @Test
    void mapsBrowserUrlsToApiBase() {
        assertThat(GithubReleaseFeed.toApiBase("https://github.com/vfaner/jync"))
                .isEqualTo("https://api.github.com/repos/vfaner/jync");
        assertThat(GithubReleaseFeed.toApiBase("https://github.com/vfaner/jync/"))
                .isEqualTo("https://api.github.com/repos/vfaner/jync");
        assertThat(GithubReleaseFeed.toApiBase("https://github.com/vfaner/jync.git"))
                .isEqualTo("https://api.github.com/repos/vfaner/jync");
        assertThat(GithubReleaseFeed.toApiBase("http://github.com/vfaner/jync"))
                .isEqualTo("https://api.github.com/repos/vfaner/jync");
    }

    @Test
    void mapsSshCloneUrl() {
        assertThat(GithubReleaseFeed.toApiBase("git@github.com:vfaner/jync.git"))
                .isEqualTo("https://api.github.com/repos/vfaner/jync");
    }

    @Test
    void rejectsOtherHostsAndGarbage() {
        assertThatThrownBy(() -> GithubReleaseFeed.toApiBase("https://gitlab.com/a/b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GithubReleaseFeed.toApiBase("not a url"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GithubReleaseFeed.toApiBase(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void misconfiguredUrlDisablesChecksInsteadOfFailingConstruction() {
        // The bean is created even when app.github-url points elsewhere; every fetch then fails
        // closed so a typo cannot stop the application from starting.
        GithubReleaseFeed feed = new GithubReleaseFeed("https://gitlab.example.com/a/b");
        assertThatThrownBy(feed::latest).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> feed.byTag("v1.1.1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void assetSha256IsParsedFromTheGithubDigestField() throws Exception {
        // GitHub reports the uploaded asset's digest as "sha256:<hex>". The upgrade verifies
        // the downloaded jar against it; a missing or non-sha256 digest must stay null so the
        // upgrade knows the bytes could not be verified this way.
        String body = """
                {
                  "tag_name": "v1.8.0",
                  "html_url": "https://github.com/vfaner/jync/releases/tag/v1.8.0",
                  "published_at": "2026-10-16T00:00:00Z",
                  "assets": [
                    {"name": "jync.jar", "browser_download_url": "https://x/jync.jar",
                     "size": 123,
                     "digest": "sha256:AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899"},
                    {"name": "notes.txt", "browser_download_url": "https://x/notes.txt",
                     "size": 4, "digest": "md5:00112233"},
                    {"name": "other.jar", "browser_download_url": "https://x/other.jar",
                     "size": 5}
                  ]
                }
                """;

        var release = GithubReleaseFeed.parse(body);

        assertThat(release.jarAsset("jync.jar").sha256())
                .isEqualTo("aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899");
        assertThat(release.assets()).filteredOn(a -> a.name().equals("notes.txt"))
                .singleElement().extracting(a -> a.sha256()).isNull();
        assertThat(release.assets()).filteredOn(a -> a.name().equals("other.jar"))
                .singleElement().extracting(a -> a.sha256()).isNull();
    }

    @Test
    void assetUploadTimeComesFromUpdatedAtAndFallsBackToCreatedAt() throws Exception {
        // v1.8.0 was re-uploaded under its own tag three times: the release kept its
        // published_at while the jar's updated_at moved to the day of the last upload. That
        // upload date is what the settings card shows when only the bytes changed.
        String body = """
                {
                  "tag_name": "v1.8.0",
                  "html_url": "https://github.com/vfaner/jync/releases/tag/v1.8.0",
                  "published_at": "2026-10-24T06:19:53Z",
                  "assets": [
                    {"name": "jync.jar", "browser_download_url": "https://x/jync.jar",
                     "size": 83502549, "created_at": "2026-10-25T01:07:45Z",
                     "updated_at": "2026-10-25T01:07:54Z"},
                    {"name": "only-created.jar", "browser_download_url": "https://x/oc.jar",
                     "size": 1, "created_at": "2026-10-20T00:00:00Z"},
                    {"name": "no-time.jar", "browser_download_url": "https://x/nt.jar",
                     "size": 2, "created_at": "not-a-time", "updated_at": ""}
                  ]
                }
                """;

        var release = GithubReleaseFeed.parse(body);

        assertThat(release.jarAsset("jync.jar").updatedAt())
                .isEqualTo(java.time.Instant.parse("2026-10-25T01:07:54Z"));
        assertThat(release.jarAsset("only-created.jar").updatedAt())
                .isEqualTo(java.time.Instant.parse("2026-10-20T00:00:00Z"));
        assertThat(release.jarAsset("no-time.jar").updatedAt()).isNull();
    }

    @Test
    void aMalformedDigestIsIgnoredRatherThanTrusted() throws Exception {
        String body = """
                {
                  "tag_name": "v1.8.0",
                  "html_url": "https://github.com/vfaner/jync/releases/tag/v1.8.0",
                  "assets": [
                    {"name": "jync.jar", "browser_download_url": "https://x/jync.jar",
                     "size": 123, "digest": "sha256:not-hex!!"}
                  ]
                }
                """;

        assertThat(GithubReleaseFeed.parse(body).jarAsset("jync.jar").sha256()).isNull();
    }
}
