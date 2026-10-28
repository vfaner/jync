package com.qqmu.jync.it;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared real OpenGauss 5.0.0 target for the 信创-target IT family (docker 实测矩阵).
 *
 * <p>Mirrors {@link CdcMySqlContainerHarnessIT}'s family conventions: ONE container,
 * started lazily on first use, deliberately NOT {@code @Container}-managed (Ryuk reaps
 * it at JVM exit). The image needs {@code --privileged}; its initial superuser refuses
 * remote logins ("Forbid remote connection with initial user"), so the {@code jync}
 * account + {@code jync_target} database are created through a local {@code gsql} exec
 * right after startup.
 */
final class OpenGaussSupport {

    static final String USER = "jync";
    static final String PASSWORD = "Jync#test123";
    static final String DATABASE = "jync_target";
    /**
     * Separate database for the CDC source role (v1.9.0). A logical slot decodes only its
     * own database, so keeping source and target apart also keeps the apply-back writes
     * out of the decoded stream — no feedback loop.
     */
    static final String SOURCE_DATABASE = "jync_source";
    static final int PG_PORT = 5432;

    private static GenericContainer<?> shared;

    private OpenGaussSupport() { }

    static synchronized GenericContainer<?> container() {
        if (shared == null) {
            GenericContainer<?> og = new GenericContainer<>(
                    DockerImageName.parse("opengauss/opengauss:5.0.0"))
                    .withPrivilegedMode(true)
                    .withEnv("GS_PASSWORD", PASSWORD)
                    .withExposedPorts(PG_PORT)
                    .waitingFor(Wait.forListeningPort()
                            .withStartupTimeout(Duration.ofMinutes(3)));
            og.start();
            shared = og;
            initialize();
        }
        return shared;
    }

    static String host() {
        return container().getHost();
    }

    static int port() {
        return container().getMappedPort(PG_PORT);
    }

    static String jdbcUrl() {
        return "jdbc:opengauss://" + host() + ":" + port() + "/" + DATABASE;
    }

    /** JDBC to the CDC-source database (v1.9.0 IT family). */
    static String sourceJdbcUrl() {
        return "jdbc:opengauss://" + host() + ":" + port() + "/" + SOURCE_DATABASE;
    }

    /** Plain JDBC to the target database as the app user (driver must be on the classpath). */
    static Connection jdbc() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), USER, PASSWORD);
    }

    /** Plain JDBC to the source database as the app user. */
    static Connection sourceJdbc() throws SQLException {
        return DriverManager.getConnection(sourceJdbcUrl(), USER, PASSWORD);
    }

    /**
     * Creates the app user + databases via local gsql. The entrypoint briefly serves the
     * port from a bootstrap instance whose data dir is replaced by the real initdb, so a
     * gsql that "succeeds" early can write into a throwaway server — the acceptance check
     * is therefore a real TCP login as the app user, retried until it works (bounded, so
     * a broken image fails loudly instead of hanging).
     *
     * <p>v1.9.0: the probe logs in to <b>every</b> database the family needs. Checking
     * only the target's login let an early {@code CREATE DATABASE jync_source} that lost
     * its bootstrap race go unnoticed until the IT failed on its first source connect.
     */
    private static void initialize() {
        long deadline = System.currentTimeMillis() + 180_000L;
        while (true) {
            SQLException loginRefused = loginProbe();
            if (loginRefused == null) {
                return;
            }
            runGsqlInit();
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(
                        "OpenGauss did not become usable within 180s", loginRefused);
            }
            sleepQuietly();
        }
    }

    /** A real TCP login as the app user to both databases; the failure, or null. */
    private static SQLException loginProbe() {
        try (Connection target = jdbc()) {
            target.createStatement().execute("SELECT 1");
            try (Connection source = sourceJdbc()) {
                source.createStatement().execute("SELECT 1");
            }
            return null;
        } catch (SQLException e) {
            return e;
        }
    }

    /**
     * Best-effort user/database creation; "already exists" and bootstrap races are ignored.
     * The statements run as separate gsql invocations: in one multi-{@code -c} session the
     * just-created role is not visible to CREATE DATABASE … OWNER (openGauss quirk, and
     * gsql exits 0 even on ERROR — which is why acceptance is the TCP login probe). Errors
     * are echoed to stderr with a prefix so a stuck init leaves evidence in the surefire
     * output instead of vanishing.
     */
    private static void runGsqlInit() {
        String env = "export GAUSSHOME=/usr/local/opengauss"
                + "; export PATH=$GAUSSHOME/bin:$PATH"
                + "; export LD_LIBRARY_PATH=$GAUSSHOME/lib; ";
        gsql(env, "CREATE USER " + USER + " WITH PASSWORD '" + PASSWORD + "' SYSADMIN;");
        gsql(env, "CREATE DATABASE " + DATABASE + " OWNER " + USER + ";");
        // The CDC-source database (v1.9.0): a slot decodes only its own database, so the
        // source must not share a database with the apply-back target.
        gsql(env, "CREATE DATABASE " + SOURCE_DATABASE + " OWNER " + USER + ";");
        // Logical decoding needs wal_level=logical; the image's entrypoint already appends
        // exactly that line on first init, so nothing to do here — if a future image drops
        // it, the probe's ② check names the missing setting.
    }

    private static void gsql(String env, String statement) {
        try {
            var result = shared.execInContainer("su", "opengauss", "-s", "/bin/bash", "-c",
                    env + "gsql -d postgres -c \"" + statement + "\"");
            String output = result.getStdout() + result.getStderr();
            if (output.contains("ERROR")) {
                // The first round normally lands on the entrypoint's bootstrap instance
                // and fails like this; the retry loop re-runs it against the real server.
                System.err.println("[OpenGaussSupport] retrying after: " + statement
                        + " → " + output.trim());
            }
        } catch (Exception ignored) {
            // retried by the caller until the TCP login probe succeeds
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(2_000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for OpenGauss", ie);
        }
    }
}
