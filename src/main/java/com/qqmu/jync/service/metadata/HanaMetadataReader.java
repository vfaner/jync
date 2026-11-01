package com.qqmu.jync.service.metadata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.stereotype.Component;

import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.model.DatabaseType;

import lombok.extern.slf4j.Slf4j;

/**
 * SAP HANA metadata.
 *
 * <p>HANA has no INFORMATION_SCHEMA, so the generic reader silently loses every view body.
 * SYS.VIEWS.DEFINITION holds the SELECT body (without a CREATE header) and
 * SYS.PROCEDURES / SYS.FUNCTIONS.DEFINITION hold the full CREATE statement. Table and column
 * comments already arrive through JDBC REMARKS (verified on HANA Express 2.00.082), so they
 * are not re-read from the dictionary.
 */
@Component
@Slf4j
public class HanaMetadataReader extends GenericMetadataReader {

    @Override
    public boolean supports(DatabaseType type) {
        return type == DatabaseType.HANA;
    }

    @Override
    protected String readViewDefinitionFromInformationSchema(Connection conn, String schema,
                                                             String viewName) {
        return lookupDefinition(conn, "SYS.VIEWS", "VIEW_NAME", schema, viewName);
    }

    @Override
    public ProcedureMeta readProcedure(Connection conn, String schema, String procedureName)
            throws SQLException {
        ProcedureMeta proc = super.readProcedure(conn, schema, procedureName);
        String body = lookupDefinition(conn, "SYS.PROCEDURES", "PROCEDURE_NAME",
                schema, procedureName);
        if (body == null) {
            body = lookupDefinition(conn, "SYS.FUNCTIONS", "FUNCTION_NAME", schema, procedureName);
            if (body != null) {
                proc.setRoutineType("FUNCTION");
            }
        }
        proc.setDefinition(body);
        return proc;
    }

    private String lookupDefinition(Connection conn, String dictionary, String nameColumn,
                                    String schema, String name) {
        String sql = "SELECT DEFINITION FROM " + dictionary + " WHERE " + nameColumn + " = ?"
                + " AND (? IS NULL OR SCHEMA_NAME = ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            MetadataTimeouts.apply(ps);
            String schemaArg = schema == null || schema.isBlank() ? null : schema;
            ps.setString(1, name);
            ps.setString(2, schemaArg);
            ps.setString(3, schemaArg);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String definition = rs.getString(1);
                    return definition == null || definition.isBlank() ? null : definition;
                }
            }
        } catch (SQLException e) {
            log.debug("{} lookup failed for {}: {}", dictionary, name, e.getMessage());
        }
        return null;
    }
}
