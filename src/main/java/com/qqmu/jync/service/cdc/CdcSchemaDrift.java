package com.qqmu.jync.service.cdc;

/**
 * The one failure shape for "the schema this stream decodes with is not the schema the row
 * images were written with".
 *
 * <p>MySQL row events carry values by <em>position</em>, not by name. The binding that names
 * them comes from the live catalog, so a DDL that ran after the resume position (add / drop /
 * rename / reorder a column) makes every replayed image line up with the wrong column. The
 * damage is silent — values land in other columns and the position still advances — so both
 * detect points fail fast instead: the binlog's own TABLE_MAP layout when the image arrives,
 * and the persisted layout signature when a binding is first built after a restart. The
 * project is parked in ERROR; the operator action is the existing one-click re-baseline,
 * which rebuilds from the current schema and resumes at the tail instead of replaying across
 * the DDL.
 */
final class CdcSchemaDrift {

    /**
     * Message fragment {@link CdcFailureClassifier} matches. Lower-cased comparison, so the
     * spelling here must survive {@code toLowerCase}.
     */
    static final String MARKER = "CDC schema drift";

    private CdcSchemaDrift() {
    }

    static IllegalStateException failure(String database, String table, String detail) {
        return new IllegalStateException(MARKER + ": table " + database + "." + table + " "
                + detail + "; re-baseline the project to rebuild it from the current source"
                + " schema");
    }
}
