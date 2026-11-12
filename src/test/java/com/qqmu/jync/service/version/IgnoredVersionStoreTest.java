package com.qqmu.jync.service.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataAccessResourceFailureException;

import com.qqmu.jync.repository.UpdateVersionDismissalRepository;

/**
 * The dashboard dialog's "ignore this version": one row, exact version semantics, and a newer
 * release brings the prompt back by itself.
 */
@DataJpaTest
class IgnoredVersionStoreTest {

    @Autowired
    private UpdateVersionDismissalRepository repository;

    private JpaIgnoredVersionStore store() {
        return new JpaIgnoredVersionStore(repository);
    }

    @Test
    void nothingIsIgnoredUntilAnAdminSaysSo() {
        JpaIgnoredVersionStore store = store();

        assertThat(store.ignoredVersion()).isNull();
        assertThat(store.isIgnored("2.1.0")).isFalse();
        assertThat(store.isIgnored(null)).isFalse();
    }

    @Test
    void theIgnoredVersionAndAnythingOlderStayQuiet() {
        JpaIgnoredVersionStore store = store();

        assertThat(store.ignore("2.1.0")).isTrue();

        assertThat(store.isIgnored("2.1.0")).isTrue();
        assertThat(store.isIgnored("v2.1.0")).isTrue();
        assertThat(store.isIgnored("2.0.9")).isTrue();
        assertThat(store.isIgnored("2.2.0")).isFalse();
    }

    @Test
    void aNewerReleaseBringsThePromptBack() {
        JpaIgnoredVersionStore store = store();
        store.ignore("v2.1.0");

        assertThat(store.isIgnored("2.2.0")).isFalse();

        store.ignore("2.2.0");
        assertThat(store.isIgnored("2.1.0")).isTrue();
        assertThat(store.isIgnored("2.2.0")).isTrue();
        assertThat(store.isIgnored("2.3.0")).isFalse();
    }

    @Test
    void onlyOneRowHoldsTheLatestAcknowledgement() {
        JpaIgnoredVersionStore store = store();

        store.ignore("2.1.0");
        store.ignore("2.2.0");

        assertThat(repository.count()).isEqualTo(1);
        assertThat(store.ignoredVersion()).isEqualTo("2.2.0");
    }

    @Test
    void blankOrMalformedVersionsAreNotRecorded() {
        JpaIgnoredVersionStore store = store();

        assertThat(store.ignore(null)).isFalse();
        assertThat(store.ignore("   ")).isFalse();
        assertThat(store.ignore("2.1 .0")).isFalse();
        assertThat(store.ignore("x".repeat(65))).isFalse();

        assertThat(repository.count()).isZero();
        assertThat(store.ignoredVersion()).isNull();
    }

    @Test
    void aDatabaseFailureWhileIgnoringDoesNotBreakTheDialog() {
        // /api/version/ignore promises success=true and the dashboard dialog closes on it.
        // A metadata-database hiccup must not turn that click into a 500; the worst case is
        // one extra prompt on a later visit, which is what "nothing ignored" means here.
        UpdateVersionDismissalRepository broken = mock(UpdateVersionDismissalRepository.class);
        when(broken.findById(any())).thenThrow(new DataAccessResourceFailureException("db down"));
        JpaIgnoredVersionStore store = new JpaIgnoredVersionStore(broken);

        assertThat(store.ignore("2.1.0")).isFalse();
        assertThat(store.isIgnored("2.1.0")).isFalse();
    }
}
