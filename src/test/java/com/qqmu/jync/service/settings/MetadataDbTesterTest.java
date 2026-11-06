package com.qqmu.jync.service.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.MetadataDbTestForm;

/**
 * The metadata-database test button.
 *
 * <p>It dials a host the administrator types, so it is deliberately narrow: four JDBC schemes,
 * that vendor's driver, no line breaks, and vendor timeouts rather than
 * {@code DriverManager.setLoginTimeout} (which is JVM-wide and would shorten every sync
 * connection too). The password goes to the connection and back to the page, never to a log or the
 * audit trail, and an error message that quotes it is scrubbed before it is shown.
 */
class MetadataDbTesterTest {

    private static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";

    private static MetadataDbTestForm form(String url, String driver, String password) {
        MetadataDbTestForm form = new MetadataDbTestForm();
        form.setUrl(url);
        form.setDriverClass(driver);
        form.setUsername("app");
        form.setPassword(password);
        return form;
    }

    private static Connection connection(String product, String version) throws SQLException {
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(meta.getDatabaseProductName()).thenReturn(product);
        when(meta.getDatabaseProductVersion()).thenReturn(version);
        Connection connection = mock(Connection.class);
        when(connection.getMetaData()).thenReturn(meta);
        return connection;
    }

    @Test
    void onlyTheFourSupportedSchemesAreAccepted() {
        MetadataDbTester tester = new MetadataDbTester((url, user, password) -> {
            throw new AssertionError("must not connect");
        });

        assertThatThrownBy(() -> tester.test(form("jdbc:h2:file:./data/jync", "org.h2.Driver", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.dbTest.urlInvalid");
        assertThatThrownBy(() -> tester.test(form("file:/etc/passwd", MYSQL_DRIVER, "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.dbTest.urlInvalid");
        assertThatThrownBy(() -> tester.test(form("", MYSQL_DRIVER, "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.dbTest.urlRequired");
    }

    @Test
    void theDriverMustBeKnownAndMatchTheScheme() {
        MetadataDbTester tester = new MetadataDbTester((url, user, password) -> {
            throw new AssertionError("must not connect");
        });

        assertThatThrownBy(() -> tester.test(
                form("jdbc:mysql://db:3306/jync", "com.example.Driver", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.dbTest.driverUnknown");
        assertThatThrownBy(() -> tester.test(
                form("jdbc:mysql://db:3306/jync", "org.postgresql.Driver", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.dbTest.driverMismatch");
        assertThatThrownBy(() -> tester.test(
                form("jdbc:mysql://db:3306/jync", "", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.dbTest.driverRequired");
    }

    @Test
    void aUrlCannotSmuggleExtraLines() {
        MetadataDbTester tester = new MetadataDbTester((url, user, password) -> {
            throw new AssertionError("must not connect");
        });

        assertThatThrownBy(() -> tester.test(
                form("jdbc:mysql://db:3306/jync\njdbc:h2:mem:x", MYSQL_DRIVER, "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.dbTest.urlInvalid");
    }

    @Test
    void aReachableDatabaseReportsItsProductAndVersion() {
        MetadataDbTester tester = new MetadataDbTester(
                (url, user, password) -> connection("MySQL", "8.0.36"));

        MetadataDbTester.Result result = tester.test(
                form("jdbc:mysql://db:3306/jync", MYSQL_DRIVER, "pw"));

        assertThat(result.success()).isTrue();
        assertThat(result.product()).isEqualTo("MySQL");
        assertThat(result.version()).isEqualTo("8.0.36");
    }

    @Test
    void vendorTimeoutsAreAppendedRatherThanSetGlobally() {
        AtomicReference<String> used = new AtomicReference<>();
        MetadataDbTester tester = new MetadataDbTester((url, user, password) -> {
            used.set(url);
            return connection("PostgreSQL", "16.2");
        });

        tester.test(form("jdbc:postgresql://db:5432/jync", "org.postgresql.Driver", "pw"));

        assertThat(used.get()).contains("connectTimeout=5").contains("socketTimeout=5");
    }

    @Test
    void theEnvironmentBlockIsSafeToPasteIntoAPosixShell() {
        MetadataDbTester tester = new MetadataDbTester(
                (url, user, password) -> connection("MySQL", "8.0"));

        MetadataDbTester.Result result = tester.test(
                form("jdbc:mysql://db:3306/jync", MYSQL_DRIVER, "pa'ss word"));

        assertThat(result.envBlock())
                .contains("SPRING_DATASOURCE_URL=")
                .contains("SPRING_DATASOURCE_DRIVER_CLASS_NAME='" + MYSQL_DRIVER + "'")
                .contains("'pa'\\''ss word'");
    }

    @Test
    void aDriverErrorMessageNeverQuotesThePassword() {
        MetadataDbTester tester = new MetadataDbTester((url, user, password) -> {
            throw new SQLException("Access denied for user 'app' using password 'p@ss word'");
        });

        MetadataDbTester.Result result = tester.test(
                form("jdbc:mysql://db:3306/jync", MYSQL_DRIVER, "p@ss word"));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).doesNotContain("p@ss word");
    }

    @Test
    void aTimeoutIsReportedByKey() {
        MetadataDbTester tester = new MetadataDbTester((url, user, password) -> {
            throw new SQLTimeoutException("connect timed out");
        });

        MetadataDbTester.Result result = tester.test(
                form("jdbc:mysql://db:3306/jync", MYSQL_DRIVER, "pw"));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("error.dbTest.timeout");
    }
}
