package com.qqmu.jync.service.migration.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.dto.MigrationConnectionForm;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;

@ExtendWith(MockitoExtension.class)
class KingbaseConnectionAdapterTest {

    @Mock private KingbaseConnectionFactory factory;
    @Mock private Connection connection;
    @Mock private DatabaseMetaData metadata;
    @Mock private ResultSet tables;

    private KingbaseConnectionAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new KingbaseConnectionAdapter(factory);
    }

    private MigrationConnectionForm form() {
        MigrationConnectionForm form = new MigrationConnectionForm();
        form.setKind(MigrationConnectionKind.KINGBASE);
        form.setBaseUrl("jdbc:kingbase8://db.internal:54321/orders");
        form.setDatabaseName("orders");
        form.setSchemaName("public");
        form.setAuthType(MigrationConnectionAuthType.BASIC);
        form.setUsername("app");
        return form;
    }

    private MigrationConnection connection() {
        MigrationConnection connection = new MigrationConnection();
        connection.setId(4L);
        connection.setKind(MigrationConnectionKind.KINGBASE);
        connection.setBaseUrl("jdbc:kingbase8://db.internal:54321/orders");
        connection.setDatabaseName("orders");
        connection.setSchemaName("public");
        connection.setAuthType(MigrationConnectionAuthType.BASIC);
        connection.setUsername("app");
        connection.setPasswordEnc("enc:pw");
        connection.setConnectTimeoutMs(5000);
        connection.setRequestTimeoutMs(60000);
        return connection;
    }

    @Test
    void itOwnsOnlyTheKingbaseKind() {
        assertThat(adapter.kinds()).containsExactly(MigrationConnectionKind.KINGBASE);
    }

    @Test
    void aKingbaseJdbcUrlIsAccepted() {
        MigrationConnectionEndpoint endpoint = adapter.normalize(form());

        assertThat(endpoint.baseUrl()).isEqualTo("jdbc:kingbase8://db.internal:54321/orders");
        assertThat(endpoint.databaseName()).isEqualTo("orders");
        assertThat(endpoint.schemaName()).isEqualTo("public");
    }

    @Test
    void aNonKingbaseUrlIsRejected() {
        MigrationConnectionForm form = form();
        form.setBaseUrl("jdbc:postgresql://db.internal:5432/orders");

        assertThatThrownBy(() -> adapter.normalize(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.kingbase.url.invalid");
    }

    @Test
    void credentialsCannotHideInTheUrlOrTheExtraParameters() {
        MigrationConnectionForm inUrl = form();
        inUrl.setBaseUrl("jdbc:kingbase8://db.internal:54321/orders?user=root");
        assertThatThrownBy(() -> adapter.normalize(inUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.kingbase.params.invalid");

        MigrationConnectionForm inParams = form();
        inParams.setExtraParams("sslmode=require&password=plain");
        assertThatThrownBy(() -> adapter.normalize(inParams))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.kingbase.params.invalid");
    }

    @Test
    void theUriPathAndTheDatabaseFieldMustAgree() {
        MigrationConnectionForm form = form();
        form.setDatabaseName("analytics");

        assertThatThrownBy(() -> adapter.normalize(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.kingbase.database.mismatch");
    }

    @Test
    void driverParametersAreStoredWithoutALeadingSeparator() {
        MigrationConnectionForm form = form();
        form.setExtraParams(" &sslmode=require ");

        assertThat(adapter.normalize(form).extraParams()).isEqualTo("sslmode=require");
    }

    @Test
    void aSchemaLongerThanSixtyThreeUtf8BytesIsRejected() {
        MigrationConnectionForm form = form();
        form.setSchemaName("库".repeat(22));

        assertThatThrownBy(() -> adapter.normalize(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.kingbase.schema.too.long");
    }

    @Test
    void onlyBasicAuthenticationIsSupported() {
        MigrationConnectionForm none = form();
        none.setAuthType(MigrationConnectionAuthType.NONE);
        assertThatThrownBy(() -> adapter.validateAuth(none, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.kingbase.auth.unsupported");

        MigrationConnectionForm apiKey = form();
        apiKey.setAuthType(MigrationConnectionAuthType.API_KEY);
        assertThatThrownBy(() -> adapter.validateAuth(apiKey, null, "id:key", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.kingbase.auth.unsupported");
    }

    @Test
    void basicAuthNeedsAUsernameAndAPassword() {
        MigrationConnectionForm noUser = form();
        noUser.setUsername(" ");
        assertThatThrownBy(() -> adapter.validateAuth(noUser, "pw", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.username.required");

        MigrationConnectionForm noPassword = form();
        assertThatThrownBy(() -> adapter.validateAuth(noPassword, "", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.connection.password.required");
    }

    @Test
    void probingReportsProductVersionAndSchema() throws Exception {
        when(factory.open(any(MigrationConnection.class))).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("KingbaseES");
        when(metadata.getDatabaseProductVersion()).thenReturn("V009R001C002B0014");
        when(connection.getSchema()).thenReturn("public");

        MigrationConnectionTestResult result = adapter.test(connection());

        assertThat(result.success()).isTrue();
        assertThat(result.product()).isEqualTo("KingbaseES");
        assertThat(result.version()).isEqualTo("V009R001C002B0014");
        assertThat(result.clusterName()).isEqualTo("public");
    }

    @Test
    void discoveryReturnsTablesInTheConfiguredSchemaAndHonoursThePattern() throws Exception {
        when(factory.open(any(MigrationConnection.class))).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getTables(eq(null), eq("public"), eq("%"), any(String[].class)))
                .thenReturn(tables);
        when(tables.next()).thenReturn(true, true, true, false);
        when(tables.getString("TABLE_NAME"))
                .thenReturn("orders", "users", "audit_log");

        List<MigrationDiscoveryItem> items = adapter.list(connection(), "orders,users");

        assertThat(items).extracting(MigrationDiscoveryItem::name)
                .containsExactly("orders", "users");
    }
}
