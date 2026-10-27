package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcPositionStore;

/**
 * End-to-end proof that a large CDC row survives the transaction buffer: a 2 MB
 * {@code LONGTEXT} + 2 MB {@code LONGBLOB} streamed through the binlog reaches the target
 * intact, and the spill files written for the buffered transaction are gone once the XID
 * boundary commits.
 *
 * <p>The threshold is lowered to 1 KB so an ordinary INSERT exercises the spill path. The
 * container runs with {@code --max-allowed-packet=64M} (see
 * {@link CdcMySqlContainerHarnessIT#configuredMySql()}), without which the server would
 * truncate the row before our code ever sees it.
 *
 * <p>Run explicitly: {@code mvn test -Dtest=CdcLobSpillIT -Dapi.version=1.44}.
 */
@TestPropertySource(properties = {
        "sync.lob.cdc-spill-threshold-bytes=1024",
        "sync.lob.spill-dir=${java.io.tmpdir}/jync-lob-it"
})
/*
 * This class' @TestPropertySource gives it a Spring context of its own, and Spring keeps
 * cached contexts alive for the rest of the JVM. Every live context runs its own scheduled
 * CDC supervise tick against the same metadata store, so left open it competes with the
 * harness family's context for project leases: running this class before CdcResilienceIT
 * made the failover test hand its forged lease to the other context's manager and then wait
 * 60s for an owner id that never held it. Closing the context here keeps the IT suite
 * honest when several classes share one JVM.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CdcLobSpillIT extends CdcMySqlContainerHarnessIT {

    private static final int TEXT_CHARS = 700_000;       // ≈ 2.1 MB of UTF-8
    private static final int BLOB_BYTES = 2 * 1024 * 1024;

    @Autowired private CdcConsumerManager manager;
    @Autowired private CdcPositionStore positionStore;

    @Test
    void aTwoMegabyteRowCrossesTheBufferedTransactionAndLeavesNoSpillFiles() throws Exception {
        deleteSpillFiles();
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("CREATE TABLE blobdocs (id BIGINT PRIMARY KEY,"
                    + " body LONGTEXT, payload LONGBLOB)");
            st.execute("INSERT INTO blobdocs VALUES (1, 'seed', 'seed')");
        }
        try (Connection tgt = targetJdbc(); Statement st = tgt.createStatement()) {
            st.execute("CREATE TABLE blobdocs (id BIGINT PRIMARY KEY,"
                    + " body LONGTEXT, payload LONGBLOB)");
        }
        Project project = newProject("cdc-lob-spill", cdcConfig("blobdocs"));
        manager.ensureStarted(project);
        waitFor("baseline done", 60_000, () -> positionStore.find(project.getId())
                .map(p -> p.isBaselineDone()).orElse(false));
        waitFor("seed row", 30_000, () -> countTarget("blobdocs") == 1);

        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("INSERT INTO blobdocs VALUES (2, REPEAT('中', " + TEXT_CHARS + "),"
                    + " REPEAT('A', " + BLOB_BYTES + "))");
        }
        waitFor("2 MB row streamed", 60_000, () -> queryTarget(
                "SELECT id FROM blobdocs WHERE id = 2").size() == 1);

        List<Map<String, Object>> checks = queryTarget(
                "SELECT CHAR_LENGTH(body) AS chars, OCTET_LENGTH(payload) AS bytes"
                        + " FROM blobdocs WHERE id = 2");
        assertThat(checks).hasSize(1);
        assertThat(((Number) checks.get(0).get("chars")).longValue()).isEqualTo(TEXT_CHARS);
        assertThat(((Number) checks.get(0).get("bytes")).longValue()).isEqualTo(BLOB_BYTES);

        // The transaction's files are released at the commit boundary, before the
        // position is persisted — by the time the row is visible they must be gone.
        waitFor("spill files released", 10_000, () -> spillFiles() == 0);

        manager.ensureStopped(project.getId());
        disableProject(project);
    }

    private static Path spillDir() {
        return Paths.get(System.getProperty("java.io.tmpdir"), "jync-lob-it");
    }

    private static long spillFiles() {
        Path dir = spillDir();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("jync-lob-"))
                    .count();
        } catch (IOException e) {
            return -1;
        }
    }

    private static void deleteSpillFiles() throws IOException {
        Path dir = spillDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                if (file.getFileName().toString().startsWith("jync-lob-")) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private SyncConfig cdcConfig(String... tables) {
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        for (String t : tables) {
            config.getTables().add(t);
        }
        return config;
    }
}
