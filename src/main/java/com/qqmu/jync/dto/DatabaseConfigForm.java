package com.qqmu.jync.dto;

import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;

import lombok.Getter;
import lombok.Setter;

/**
 * What the connection form is allowed to submit.
 *
 * <p>Deliberately not the entity: binding {@link DatabaseConfig} directly let a crafted POST reach
 * {@code password} / {@code minerPassword} (stored ciphertext, which the service is supposed to
 * set from the raw values) and the audit columns. Enumerating the form's own fields here keeps
 * every future column off the wire by default.
 */
@Getter
@Setter
public class DatabaseConfigForm {

    private Long id;
    private String name;
    private DatabaseType type = DatabaseType.MYSQL;
    private DatabaseType.DialectFamily dialectFamily;
    private ConnectionRole role = ConnectionRole.SOURCE;
    private String host;
    private Integer port;
    private String databaseName;
    private String schemaName;
    private String username;
    private String minerUsername;
    private String customUrl;
    private String customDriver;
    private String customJarPath;
    private String extraParams;

    /** A fresh entity carrying only the submitted fields. */
    public DatabaseConfig toEntity() {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setName(name);
        config.setType(type);
        config.setDialectFamily(dialectFamily);
        config.setRole(role);
        config.setHost(host);
        config.setPort(port);
        config.setDatabaseName(databaseName);
        config.setSchemaName(schemaName);
        config.setUsername(username);
        config.setMinerUsername(minerUsername);
        config.setCustomUrl(customUrl);
        config.setCustomDriver(customDriver);
        config.setCustomJarPath(customJarPath);
        config.setExtraParams(extraParams);
        return config;
    }
}
