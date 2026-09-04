package com.synctool.service.ai;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.synctool.model.DatabaseConfig;
import com.synctool.model.DatabaseType;
import com.synctool.service.connection.DataSourceManager;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Checks a candidate statement by creating it on the target under a throwaway name, then dropping it.
 *
 * <p>There is no portable "parse this but do not run it" call. Some products have one
 * ({@code EXPLAIN}, {@code sp_describe_first_result_set}), none of them cover routine bodies, and
 * the ones that do exist disagree about how much they check. Creating the object is the only
 * check that answers the question actually being asked — will the target accept this DDL — so
 * that is what this does, under a name no real object can collide with, followed by a DROP in a
 * finally block.
 *
 * <p><strong>This writes to the target database.</strong> It is therefore never called by the
 * sync path and never called automatically: the UI exposes it as a button the user presses, and
 * says what it will do before they press it. A validation that ran on its own could leave a
 * {@code SYNCTOOL_AI_CHECK_*} routine behind on a production target after a crash, which is a
 * surprise no user consented to.
 *
 * <p>Two things this deliberately does not solve. A recursive routine still calls itself by its
 * original name, so validation exercises the renamed copy against whatever that name resolves to
 * on the target — reported as a caveat rather than silently accepted. And Oracle accepts invalid
 * PL/SQL, marking the object {@code INVALID} instead of failing the statement, so for Oracle-family
 * targets the compile errors are read back from {@code USER_ERRORS}; without that step every
 * Oracle validation would report success.
 */
@Service
@Slf4j
public class CandidateValidator {

    /**
     * Matches the {@code CREATE ... PROCEDURE|FUNCTION|VIEW <name>} header.
     *
     * <p>Captures the name as one token so a schema-qualified or quoted name is replaced whole.
     * Anchored at the start because a {@code CREATE} appearing later in the body — inside a
     * nested block, or in a comment the mechanical converter left alone — must not be rewritten.
     *
     * <p>The qualified alternative is listed <em>before</em> the bare one on purpose. Alternation
     * is leftmost-first, so with the bare form first {@code SCHEMA.NAME} would match only
     * {@code SCHEMA} and the rename would produce {@code CREATE VIEW <temp>.NAME} — a reference
     * into a schema that does not exist. Source exports qualify names routinely, so this is the
     * common case, not the exotic one.
     */
    private static final String IDENTIFIER =
            "[\\w$#]+|\"[^\"]+\"|`[^`]+`|\\[[^\\]]+\\]";

    private static final Pattern HEADER = Pattern.compile(
            "(?is)\\A(\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?"
                    + "(?:DEFINER\\s*=\\s*[^\\s]+\\s+)?"
                    + "(?:ALGORITHM\\s*=\\s*\\w+\\s+)?"
                    + "(?:SQL\\s+SECURITY\\s+\\w+\\s+)?"
                    + "(PROCEDURE|FUNCTION|VIEW)\\s+)"
                    + "((?:" + IDENTIFIER + ")\\s*\\.\\s*(?:" + IDENTIFIER + ")"
                    + "|" + IDENTIFIER + ")");

    /** Recursion or self-reference: the renamed copy cannot call itself. */
    private static final String CAVEAT_SELF_REFERENCE = "warn.validate.selfReference";

    /** Oracle identifiers were capped at 30 bytes before 12.2, so the temp name stays under it. */
    private static final String TEMP_PREFIX = "SYNCTOOL_AI_CHECK_";

    private final DataSourceManager dataSourceManager;

    public CandidateValidator(DataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    /**
     * Creates the candidate on the target under a temporary name and drops it again.
     *
     * @param target       the target database; the candidate is created here
     * @param candidateSql the statement to check, as the user currently has it in the editor
     * @param originalName the object's real name, used to warn about self-references
     * @return the outcome, never null; a failure carries the target's own error text
     */
    public ValidationResult validate(DatabaseConfig target, String candidateSql,
                                     String originalName) {
        if (candidateSql == null || candidateSql.isBlank()) {
            return ValidationResult.failure("error.validate.empty", null, List.of());
        }

        String tempName = TEMP_PREFIX + Long.toHexString(System.nanoTime()).toUpperCase();
        if (tempName.length() > 30) {
            tempName = tempName.substring(0, 30);
        }

        Matcher matcher = HEADER.matcher(candidateSql);
        if (!matcher.find()) {
            // Without a recognisable header the object cannot be renamed, and creating it under
            // its real name would overwrite the target's existing routine. Refuse instead.
            return ValidationResult.failure("error.validate.noHeader", null, List.of());
        }
        String objectKind = matcher.group(2).toUpperCase();
        String renamed = matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + tempName));

        List<String> caveats = new ArrayList<>();
        if (referencesItself(candidateSql, originalName, matcher.end())) {
            caveats.add(CAVEAT_SELF_REFERENCE);
        }

        log.info("Validating a {} candidate on target '{}' as {}",
                objectKind, target.getName(), tempName);

        try (Connection conn = dataSourceManager.getConnection(target)) {
            try {
                try (Statement st = conn.createStatement()) {
                    st.execute(renamed);
                }
            } catch (SQLException e) {
                return ValidationResult.failure(e.getMessage(), tempName, caveats);
            }

            // Oracle-family targets compile invalid bodies into INVALID objects rather than
            // rejecting the CREATE, so a clean execute() above proves nothing on its own.
            if (target.getType() != null
                    && target.getType().getFamily() == DatabaseType.DialectFamily.ORACLE) {
                List<String> compileErrors = readCompileErrors(conn, tempName);
                if (!compileErrors.isEmpty()) {
                    return ValidationResult.failure(String.join("; ", compileErrors),
                            tempName, caveats);
                }
            }
            return ValidationResult.success(tempName, caveats);

        } catch (SQLException e) {
            // Could not even connect; distinct from "the target rejected the DDL".
            return ValidationResult.failure("error.validate.unreachable: " + e.getMessage(),
                    tempName, caveats);
        } finally {
            dropQuietly(target, objectKind, tempName);
        }
    }

    /**
     * Whether the body calls the object by its own name.
     *
     * <p>Searched from the end of the header onwards so the header's own occurrence, which is
     * what gets renamed, is not mistaken for a recursive call.
     */
    private boolean referencesItself(String sql, String originalName, int bodyStart) {
        if (originalName == null || originalName.isBlank() || bodyStart >= sql.length()) {
            return false;
        }
        return Pattern.compile("(?i)\\b" + Pattern.quote(originalName) + "\\b")
                .matcher(sql.substring(bodyStart))
                .find();
    }

    /**
     * Reads Oracle's compile errors for the temporary object.
     *
     * <p>Failure to read them is logged and ignored: a missing {@code USER_ERRORS} view means
     * this is an Oracle-compatible product that does not have it, and reporting a spurious
     * validation failure would be worse than reporting the {@code CREATE} that did succeed.
     */
    private List<String> readCompileErrors(Connection conn, String tempName) {
        List<String> errors = new ArrayList<>();
        String sql = "SELECT LINE, POSITION, TEXT FROM USER_ERRORS WHERE NAME = ? ORDER BY SEQUENCE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tempName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    errors.add("line " + rs.getInt(1) + ":" + rs.getInt(2) + " " + rs.getString(3));
                }
            }
        } catch (SQLException e) {
            log.debug("Could not read USER_ERRORS for {}: {}", tempName, e.getMessage());
        }
        return errors;
    }

    /**
     * Drops the temporary object, tolerating every failure.
     *
     * <p>Uses its own connection because the one in {@code validate} is already closing, and a
     * failed CREATE can leave the original in a state where reuse is unreliable. A leaked
     * {@code SYNCTOOL_AI_CHECK_*} object is harmless but untidy, so the attempt is always made
     * and always logged when it does not succeed.
     */
    private void dropQuietly(DatabaseConfig target, String objectKind, String tempName) {
        String drop = "DROP " + objectKind + " " + tempName;
        try (Connection conn = dataSourceManager.getConnection(target);
             Statement st = conn.createStatement()) {
            st.execute(drop);
        } catch (SQLException e) {
            // Expected whenever the CREATE failed: there is nothing to drop.
            log.debug("Cleanup of {} was not needed or not possible: {}", tempName, e.getMessage());
        }
    }

    /** Outcome of a syntax check against the real target. */
    @Getter
    public static class ValidationResult {
        private final boolean success;
        /** The target's own error text on failure, or null on success. */
        private final String message;
        /** The throwaway name used, so the user can clean up if a drop was ever missed. */
        private final String tempName;
        /**
         * Reasons this check is weaker than it looks. Entries are message keys.
         *
         * <p>Populated even on success, because "the target accepted it" and "it will behave
         * the same" are different claims and only the first one was tested.
         */
        private final List<String> caveats;

        private ValidationResult(boolean success, String message, String tempName,
                                 List<String> caveats) {
            this.success = success;
            this.message = message;
            this.tempName = tempName;
            this.caveats = List.copyOf(caveats);
        }

        static ValidationResult success(String tempName, List<String> caveats) {
            return new ValidationResult(true, null, tempName, caveats);
        }

        static ValidationResult failure(String message, String tempName, List<String> caveats) {
            return new ValidationResult(false, message, tempName, caveats);
        }
    }
}
