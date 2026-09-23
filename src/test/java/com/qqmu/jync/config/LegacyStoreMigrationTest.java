package com.qqmu.jync.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/**
 * Guards the SyncTool → Jync metadata store upgrade.
 *
 * <p>The regression these exist for: the first implementation guessed a CWD-relative
 * {@code data/synctool.mv.db}. A 1.2.x production install (old README: systemd
 * {@code WorkingDirectory=/opt/synctool}, external yml pointing at
 * {@code /opt/synctool/data/synctool}) upgrading jar-only had its store renamed out from under
 * its own unchanged URL, and H2 then created an empty database — every project appeared lost.
 * The migration must therefore key off the effective URL and leave same-named targets alone.
 */
class LegacyStoreMigrationTest {

    @TempDir
    Path tempDir;

    /* ─── URL parsing ─────────────────────────────────────────── */

    @Test
    void parsesFileUrlAndStripsSettings() {
        assertThat(LegacyStoreMigration.configuredStoreBase(
                "jdbc:h2:file:/opt/jync/data/jync;MODE=MySQL;AUTO_SERVER=TRUE"))
                .isEqualTo(Path.of("/opt/jync/data/jync"));
        assertThat(LegacyStoreMigration.configuredStoreBase("jdbc:h2:file:./data/jync"))
                .isEqualTo(Path.of("./data/jync"));
    }

    @Test
    void ignoresUrlsItMustNotTouch() {
        assertThat(LegacyStoreMigration.configuredStoreBase(null)).isNull();
        assertThat(LegacyStoreMigration.configuredStoreBase("jdbc:h2:mem:testdb")).isNull();
        assertThat(LegacyStoreMigration.configuredStoreBase("jdbc:mysql://host/db")).isNull();
        // Only H2 itself knows how to expand ~ — do not guess.
        assertThat(LegacyStoreMigration.configuredStoreBase("jdbc:h2:file:~/data/jync")).isNull();
        assertThat(LegacyStoreMigration.configuredStoreBase("jdbc:h2:file:;MODE=MySQL")).isNull();
    }

    /* ─── migration decisions ─────────────────────────────────── */

    @Test
    void renamesLegacyStoreNextToConfiguredTarget() throws IOException {
        Path legacy = Files.writeString(tempDir.resolve("synctool.mv.db"), "old-store");

        LegacyStoreMigration.migrate(tempDir.resolve("jync"));

        assertThat(legacy).doesNotExist();
        assertThat(tempDir.resolve("jync.mv.db")).hasContent("old-store");
    }

    @Test
    void leavesEverythingAloneWhenUrlStillNamesLegacyStore() throws IOException {
        // The jar-only upgrade cohort: external override still points at .../synctool.
        Path legacy = Files.writeString(tempDir.resolve("synctool.mv.db"), "old-store");

        LegacyStoreMigration.migrate(tempDir.resolve("synctool"));

        assertThat(legacy).hasContent("old-store");
        assertThat(tempDir.resolve("jync.mv.db")).doesNotExist();
    }

    @Test
    void keepsBothFilesWhenTargetStoreAlreadyExists() throws IOException {
        // A previous failed migration or a copied data dir: never overwrite, never delete.
        Files.writeString(tempDir.resolve("synctool.mv.db"), "old-store");
        Files.writeString(tempDir.resolve("jync.mv.db"), "new-store");

        LegacyStoreMigration.migrate(tempDir.resolve("jync"));

        assertThat(tempDir.resolve("synctool.mv.db")).hasContent("old-store");
        assertThat(tempDir.resolve("jync.mv.db")).hasContent("new-store");
    }

    @Test
    void skipsWhileLegacyLockFileSuggestsARunningInstance() throws IOException {
        // POSIX renames succeed even with the file held open; two H2 writers on one inode
        // corrupt the store, so a live-looking lock must stop the migration.
        Files.writeString(tempDir.resolve("synctool.mv.db"), "old-store");
        Files.writeString(tempDir.resolve("synctool.lock.db"), "lock");

        LegacyStoreMigration.migrate(tempDir.resolve("jync"));

        assertThat(tempDir.resolve("synctool.mv.db")).hasContent("old-store");
        assertThat(tempDir.resolve("jync.mv.db")).doesNotExist();
    }

    @Test
    void freshInstallIsANoOp() {
        LegacyStoreMigration.migrate(tempDir.resolve("jync"));

        assertThat(tempDir).isEmptyDirectory();
    }

    @Test
    void worksForRelativeStorePathsLikeThePackagedDefault() throws IOException {
        // ./data/jync style: the parent of the absolute path is the directory to scan.
        Path data = Files.createDirectories(tempDir.resolve("data"));
        Files.writeString(data.resolve("synctool.mv.db"), "old-store");

        LegacyStoreMigration.migrate(tempDir.resolve("./data/jync"));

        assertThat(data.resolve("jync.mv.db")).hasContent("old-store");
    }

    /* ─── post-processor wiring ───────────────────────────────── */

    @Test
    void postProcessorMigratesBasedOnEffectiveUrl() throws IOException {
        Path data = Files.createDirectories(tempDir.resolve("data"));
        Files.writeString(data.resolve("synctool.mv.db"), "old-store");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url",
                        "jdbc:h2:file:" + data.resolve("jync") + ";MODE=MySQL;AUTO_SERVER=TRUE");

        new LegacyStoreMigration().postProcessEnvironment(environment, null);

        assertThat(data.resolve("jync.mv.db")).hasContent("old-store");
    }

    @Test
    void postProcessorIgnoresNonFileUrls() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:h2:mem:testdb");

        new LegacyStoreMigration().postProcessEnvironment(environment, null);

        assertThat(tempDir).isEmptyDirectory();
    }
}
