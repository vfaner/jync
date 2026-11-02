package com.qqmu.jync.service.migration.connection;

import java.util.List;
import java.util.Set;

import com.qqmu.jync.dto.MigrationConnectionForm;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionKind;

/**
 * Kind-specific half of connection management. {@code MigrationConnectionService} keeps the
 * generic CRUD, name rules and credential encryption; an adapter owns the endpoint shape, the
 * authentication rules and the probe/discovery protocol.
 */
public interface MigrationConnectionKindAdapter {

    Set<MigrationConnectionKind> kinds();

    /** Validates and normalises the endpoint fields; throws an i18n key on bad input. */
    MigrationConnectionEndpoint normalize(MigrationConnectionForm form);

    /**
     * Checks the submitted credential combination. {@code existing} is the stored row when
     * editing (null when creating); a blank raw secret may fall back to its encrypted value.
     */
    void validateAuth(MigrationConnectionForm form, String rawPassword, String rawApiKey,
                      MigrationConnection existing);

    MigrationConnectionTestResult test(MigrationConnection connection);

    MigrationConnectionTestResult testTransient(MigrationConnection connection,
                                                String plainPassword, String plainApiKey);

    /** Discovers copyable objects; {@code pattern} is a comma/newline separated glob list. */
    List<MigrationDiscoveryItem> list(MigrationConnection connection, String pattern);
}
