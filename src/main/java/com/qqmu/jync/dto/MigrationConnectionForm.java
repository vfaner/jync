package com.qqmu.jync.dto;

import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;

import lombok.Getter;
import lombok.Setter;

/**
 * What the dedicated-migration connection form may submit.
 *
 * <p>No secret fields: passwords and API keys travel as separate request parameters and are
 * never bound onto an entity the browser can round-trip.
 */
@Getter
@Setter
public class MigrationConnectionForm {

    private Long id;
    private String name;
    private MigrationConnectionKind kind = MigrationConnectionKind.ELASTICSEARCH;
    private String baseUrl;
    private String databaseName;
    private String schemaName;
    private MigrationConnectionAuthType authType = MigrationConnectionAuthType.NONE;
    private String username;
    private String extraParams;
    private boolean verifyTls = true;
    private Integer connectTimeoutMs = 5000;
    private Integer requestTimeoutMs = 60000;
    private String description;

    public MigrationConnection toEntity() {
        MigrationConnection connection = new MigrationConnection();
        connection.setId(id);
        connection.setName(name);
        connection.setKind(kind);
        connection.setBaseUrl(baseUrl);
        connection.setDatabaseName(databaseName);
        connection.setSchemaName(schemaName);
        connection.setAuthType(authType);
        connection.setUsername(username);
        connection.setExtraParams(extraParams);
        connection.setVerifyTls(verifyTls);
        connection.setConnectTimeoutMs(connectTimeoutMs == null ? 5000 : connectTimeoutMs);
        connection.setRequestTimeoutMs(requestTimeoutMs == null ? 60000 : requestTimeoutMs);
        connection.setDescription(description);
        return connection;
    }

    public static MigrationConnectionForm from(MigrationConnection connection) {
        MigrationConnectionForm form = new MigrationConnectionForm();
        form.setId(connection.getId());
        form.setName(connection.getName());
        form.setKind(connection.getKind());
        form.setBaseUrl(connection.getBaseUrl());
        form.setDatabaseName(connection.getDatabaseName());
        form.setSchemaName(connection.getSchemaName());
        form.setAuthType(connection.getAuthType());
        form.setUsername(connection.getUsername());
        form.setExtraParams(connection.getExtraParams());
        form.setVerifyTls(connection.isVerifyTls());
        form.setConnectTimeoutMs(connection.getConnectTimeoutMs());
        form.setRequestTimeoutMs(connection.getRequestTimeoutMs());
        form.setDescription(connection.getDescription());
        return form;
    }
}
