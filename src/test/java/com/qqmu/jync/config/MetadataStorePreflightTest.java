package com.qqmu.jync.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the pre-Hibernate warning for the most common file-store startup failure: the running
 * user can read and even write the {@code .mv.db} and {@code .trace.db} files, but cannot create
 * a new entry in their directory. H2 then fails to create {@code <store>.lock.db} with
 * "Could not save properties ...lock.db" and leaves no lock file behind, which sends operators
 * hunting for lock contention that never happened.
 */
class MetadataStorePreflightTest {

    @TempDir
    Path tempDir;

    @Test
    void aWritableStoreDirectoryPassesSilently() throws IOException {
        Path store = tempDir.resolve("data/jync");
        Files.createDirectories(store.getParent());
        Files.createFile(Path.of(store + ".mv.db"));

        assertThat(MetadataStorePreflight.startupProblem(url(store), tempDir)).isEmpty();
    }

    @Test
    void anExternalOrNonFileUrlIsIgnored() {
        assertThat(MetadataStorePreflight.startupProblem(
                "jdbc:postgresql://db.internal:5432/jync", tempDir)).isEmpty();
        assertThat(MetadataStorePreflight.startupProblem("jdbc:h2:mem:test", tempDir)).isEmpty();
        assertThat(MetadataStorePreflight.startupProblem(null, tempDir)).isEmpty();
    }

    @Test
    void aDirectoryWithoutWriteAccessIsReportedWithTheLockFileName() throws IOException {
        Path store = tempDir.resolve("data/jync");
        Files.createDirectories(store.getParent());
        Files.createFile(Path.of(store + ".mv.db"));
        Path dir = store.getParent();
        makeReadOnly(dir);
        try {
            assumeFalse(Files.isWritable(dir), "running as a user that bypasses directory permissions");

            String problem = MetadataStorePreflight.startupProblem(url(store), tempDir).orElseThrow();

            assertThat(problem)
                    .contains("不可写")
                    .contains(dir.toString())
                    .contains("lock.db")
                    .contains("chown");
        } finally {
            makeWritable(dir);
        }
    }

    @Test
    void aStoreFileWithoutWriteAccessIsReportedAsForcedReadOnly() throws IOException {
        Path store = tempDir.resolve("data/jync");
        Files.createDirectories(store.getParent());
        Path data = Files.createFile(Path.of(store + ".mv.db"));
        makeReadOnly(data);
        try {
            assumeFalse(Files.isWritable(data), "running as a user that bypasses file permissions");

            String problem = MetadataStorePreflight.startupProblem(url(store), tempDir).orElseThrow();

            assertThat(problem)
                    .contains("只读")
                    .contains(data.toString())
                    .contains("AUTO_SERVER");
        } finally {
            makeWritable(data);
        }
    }

    @Test
    void aMissingStoreUnderAnUnwritableParentIsReportedBeforeH2TriesToCreateIt() throws IOException {
        Path parent = Files.createDirectories(tempDir.resolve("data"));
        makeReadOnly(parent);
        try {
            assumeTrue(Files.isDirectory(parent));
            assumeFalse(Files.isWritable(parent), "running as a user that bypasses directory permissions");

            String problem = MetadataStorePreflight
                    .startupProblem(url(parent.resolve("jync")), tempDir).orElseThrow();

            assertThat(problem)
                    .contains("不可写")
                    .contains(parent.toString());
        } finally {
            makeWritable(parent);
        }
    }

    private static String url(Path store) {
        return "jdbc:h2:file:" + store + ";MODE=MySQL;AUTO_SERVER=TRUE";
    }

    private static void makeReadOnly(Path path) throws IOException {
        Files.setPosixFilePermissions(path,
                Files.isDirectory(path)
                        ? PosixFilePermissions.fromString("r-xr-xr-x")
                        : PosixFilePermissions.fromString("r--r--r--"));
    }

    private static void makeWritable(Path path) throws IOException {
        Files.setPosixFilePermissions(path,
                Files.isDirectory(path)
                        ? PosixFilePermissions.fromString("rwxr-xr-x")
                        : PosixFilePermissions.fromString("rw-r--r--"));
    }
}
