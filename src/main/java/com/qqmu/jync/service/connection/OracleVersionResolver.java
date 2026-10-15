package com.qqmu.jync.service.connection;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Types;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Resolves an Oracle-family target's effective major version for version-sensitive SQL.
 *
 * <p>"Effective" is the lower of two facts: the product release and the database's
 * {@code COMPATIBLE} parameter. A 19c database left at {@code compatible=11.2} still rejects
 * 12c-only syntax such as {@code AS IDENTITY}, so product metadata alone is not enough.
 *
 * <p>Both facts come from {@code DBMS_UTILITY.DB_VERSION} — executable by every account,
 * including through wrapping drivers whose {@link DatabaseMetaData} calls fail. When even that
 * is unavailable, the result is {@code null} and callers choose syntax valid on every release.
 */
@Component
@Slf4j
public class OracleVersionResolver {

    private static final String DB_VERSION_CALL = "{ call dbms_utility.db_version(?, ?) }";

    /**
     * @return effective major version, or {@code null} when neither metadata nor the PL/SQL
     *         call could answer
     */
    public Integer resolveEffectiveMajorVersion(Connection conn) {
        Integer productMajor = null;
        Integer compatibleMajor = null;

        try {
            int major = conn.getMetaData().getDatabaseMajorVersion();
            if (major > 0) {
                productMajor = major;
            }
        } catch (SQLException | RuntimeException e) {
            log.debug("JDBC metadata did not report the database version: {}", e.getMessage());
        }

        try (CallableStatement cs = conn.prepareCall(DB_VERSION_CALL)) {
            cs.registerOutParameter(1, Types.VARCHAR);
            cs.registerOutParameter(2, Types.VARCHAR);
            cs.execute();
            productMajor = firstNonNull(leadingMajor(cs.getString(1)), productMajor);
            compatibleMajor = leadingMajor(cs.getString(2));
        } catch (SQLException | RuntimeException e) {
            log.debug("DBMS_UTILITY.DB_VERSION failed: {}", e.getMessage());
        }

        if (productMajor != null && compatibleMajor != null) {
            return Math.min(productMajor, compatibleMajor);
        }
        return firstNonNull(productMajor, compatibleMajor);
    }

    /** Leading numeric component of a dotted version string, e.g. "11.2.0.4.0" → 11. */
    Integer leadingMajor(String version) {
        if (version == null) {
            return null;
        }
        String head = version.trim().replaceFirst("^(\\d+).*$", "$1");
        try {
            int value = Integer.parseInt(head);
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer firstNonNull(Integer first, Integer second) {
        return first != null ? first : second;
    }
}
