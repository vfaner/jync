package com.qqmu.jync.service.version;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.version.ReleaseFeed.Asset;
import com.qqmu.jync.service.version.ReleaseFeed.Release;

/**
 * Feed-level rules that are independent of one HTTP client: the status mapping both HTTP
 * feeds share (a 403/429 from a mirror's anonymous API is its rate limit, and the dialog has
 * to say so instead of quoting the number at an operator), and the split-jar convention.
 *
 * <p>Gitee caps one attachment at 100 MB, so a release whose jar is larger publishes
 * {@code jync.jar.part00}, {@code jync.jar.part01}, … instead of {@code jync.jar}. The
 * updater must assemble exactly that set, in numeric order, and must refuse anything with a
 * gap rather than install a jar with a hole in it.
 */
class ReleaseFeedTest {

    @Test
    void rateLimitStatusesBecomeAnExceptionTheDialogCanExplain() {
        for (int status : new int[] {403, 429}) {
            Exception failure = ReleaseFeed.apiFailure(ReleaseFeed.Source.GITEE, status);

            assertThat(failure).isInstanceOf(ReleaseFeed.RateLimitedException.class);
            ReleaseFeed.RateLimitedException limited =
                    (ReleaseFeed.RateLimitedException) failure;
            assertThat(limited.source()).isEqualTo(ReleaseFeed.Source.GITEE);
            assertThat(limited.statusCode()).isEqualTo(status);
        }
    }

    @Test
    void otherStatusesKeepThePlainStatusMessage() {
        assertThat(ReleaseFeed.apiFailure(ReleaseFeed.Source.GITHUB, 404))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GitHub API responded 404");
        assertThat(ReleaseFeed.apiFailure(ReleaseFeed.Source.GITEE, 500))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Gitee API responded 500");
    }

    private static Release release(Asset... assets) {
        return new Release("v2.0.0", "https://example.invalid", null, "",
                List.of(assets), ReleaseFeed.Source.GITEE);
    }

    private static Asset part(int index) {
        String name = "jync.jar.part" + String.format("%02d", index);
        return new Asset(name, "https://example.invalid/" + name, null);
    }

    @Test
    void findsSplitJarPartsInNumericOrder() {
        Release release = release(part(1), new Asset("jync.jar", "u", null, null), part(0));

        assertThat(release.jarParts("jync.jar"))
                .extracting(Asset::name)
                .containsExactly("jync.jar.part00", "jync.jar.part01");
    }

    @Test
    void aGapMeansTheSplitIsNotUsable() {
        assertThat(release(part(0), part(2)).jarParts("jync.jar")).isEmpty();
    }

    @Test
    void aDuplicateIndexMeansTheSplitIsNotUsable() {
        assertThat(release(part(0), part(0), part(1)).jarParts("jync.jar")).isEmpty();
    }

    @Test
    void namesOutsideTheConventionAreIgnored() {
        Release release = release(
                new Asset("jync.jar.part", "u0", null),
                new Asset("jync.jar.part00.bak", "u1", null),
                new Asset("jync.jar.partX", "u2", null),
                new Asset("other.jar.part00", "u3", null),
                part(0), part(1));

        assertThat(release.jarParts("jync.jar")).hasSize(2);
    }

    @Test
    void matchesThePrefixCaseInsensitively() {
        Release release = release(
                new Asset("JYNC.JAR.PART00", "u0", null),
                new Asset("Jync.Jar.Part01", "u1", null));

        assertThat(release.jarParts("jync.jar")).hasSize(2);
    }

    @Test
    void aPartWithoutADownloadUrlMakesTheWholeSplitUnusable() {
        Release release = release(part(0), new Asset("jync.jar.part01", "  ", null));

        assertThat(release.jarParts("jync.jar")).isEmpty();
    }

    @Test
    void noPartsIsAnEmptyList() {
        assertThat(release(new Asset("jync.jar", "u", null)).jarParts("jync.jar")).isEmpty();
    }
}
