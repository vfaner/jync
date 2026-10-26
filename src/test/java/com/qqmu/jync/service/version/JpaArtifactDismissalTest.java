package com.qqmu.jync.service.version;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.qqmu.jync.repository.UpdateArtifactDismissalRepository;

/**
 * The dismissal is one row of state: "this published package was acknowledged". It must
 * overwrite rather than accumulate — re-dismissing a new upload is the same act, and a table
 * that grows a row per dismissal has to be pruned by something nobody will write.
 */
@DataJpaTest
@Import(JpaArtifactDismissal.class)
class JpaArtifactDismissalTest {

    @Autowired
    private UpdateArtifactDismissalRepository repository;

    @Autowired
    private JpaArtifactDismissal dismissal;

    @Test
    void nothingDismissedYetReadsAsNoKey() {
        assertThat(dismissal.dismissedKey()).isNull();
        assertThat(repository.count()).isZero();
    }

    @Test
    void dismissingTwiceKeepsOneRowWithTheLatestKey() {
        dismissal.dismiss("sha256:" + "ab".repeat(32));
        dismissal.dismiss("size:83502549");

        assertThat(repository.count()).isEqualTo(1);
        // A fresh instance sees it too: the state is in the database, not in the bean.
        assertThat(new JpaArtifactDismissal(repository).dismissedKey()).isEqualTo("size:83502549");
    }

    @Test
    void aBlankKeyIsIgnoredRatherThanStored() {
        dismissal.dismiss("  ");

        assertThat(dismissal.dismissedKey()).isNull();
        assertThat(repository.count()).isZero();
    }
}
