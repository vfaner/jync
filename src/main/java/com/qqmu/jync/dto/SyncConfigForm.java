package com.qqmu.jync.dto;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * What the project detail page's configuration form submits.
 *
 * <p>Bound by both the plain form POST and its background (AJAX) twin, so the two entry
 * points cannot drift apart field by field. Lists stay {@code null} when the field is
 * absent — the save logic distinguishes "picker never rendered / old client" (no data)
 * from "user unchecked everything" (empty list) through {@link #objectSelectionExplicit}.
 */
@Getter
@Setter
public class SyncConfigForm {

    private List<String> tables;
    private List<String> views;
    private List<String> procedures;
    /** Hidden source name per checked table row; pairs with {@link #mappingTarget}. */
    private List<String> mappingSource;
    private List<String> mappingTarget;

    private boolean syncStructure;
    private boolean syncData;
    private boolean syncIndexes;
    private boolean syncViews;
    private boolean syncProcedures;
    private boolean allowDrop;
    private boolean syncDeletes;
    private boolean disableTargetConstraints;
    private boolean truncateBeforeInitialLoad;

    private Long pollIntervalMs;
    private String cronExpression;
    private Integer batchSize;
    private Integer fullLoadParallelism;
    private boolean customSchedule;

    /**
     * Set whenever the object pickers were rendered: what turns an empty submission from
     * "never configured" into "the user unchecked everything".
     */
    private boolean objectSelectionExplicit;

    private String captureStrategy;
    private Long cdcIdlePollMs;

    // --- Redis cache projection (rendered only when the target kind is REDIS) ---------------
    // Kept flat like the rest of this form: the template submits what it renders, and the
    // controller decides whether these values mean anything for the project's target kind.
    private String keyPrefix;
    private String valueFormat;
    private Long ttlSeconds;
    /** Comma-separated column names; a cache row omits them. */
    private String excludedColumns;
    private Integer maxValueBytes;
    private Integer maxCommandsPerTransaction;
}
