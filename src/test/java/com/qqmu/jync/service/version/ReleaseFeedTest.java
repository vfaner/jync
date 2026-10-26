package com.qqmu.jync.service.version;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The status mapping both HTTP feeds share. A 403/429 from a mirror's anonymous API is its
 * rate limit; the dialog has to say so instead of quoting the number at an operator.
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
}
