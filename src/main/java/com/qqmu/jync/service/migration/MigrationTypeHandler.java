package com.qqmu.jync.service.migration;

import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;

/**
 * Everything that makes one migration type different from another.
 *
 * <p>The generic module owns connections, tasks, runs, the scheduler and the pages. A new
 * migration type is added by implementing this interface and declaring its type as available;
 * the registry then routes every task of that type to it.
 */
public interface MigrationTypeHandler {

    MigrationType type();

    Set<MigrationConnectionKind> sourceKinds();

    Set<MigrationConnectionKind> targetKinds();

    JsonNode defaultOptions();

    /** Form {@code opt.*} parameters to canonical JSON; throws an i18n key on invalid input. */
    JsonNode parseOptions(ObjectNode formOptions);

    /** Read-only probing; must not leave anything behind on the target. */
    MigrationPrecheckResult precheck(MigrationTask task, JsonNode options,
                                     MigrationConnection source,
                                     MigrationConnection target);

    /** Executes the migration; checks {@code cancellation} between pages and bulk batches. */
    MigrationRunResult run(MigrationTask task, JsonNode options,
                           MigrationConnection source,
                           MigrationConnection target,
                           MigrationCancellation cancellation,
                           MigrationProgressListener progress);
}
