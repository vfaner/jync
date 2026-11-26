package com.qqmu.jync.service.sync;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.qqmu.jync.model.ConstraintState;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.converter.SqlDialectFactory;
import com.qqmu.jync.service.task.SyncLockService;

import lombok.extern.slf4j.Slf4j;

/**
 * Re-enables SQL Server constraints left disabled by a crash. The data phase writes the
 * marker before disabling and removes it after a successful restore, so every remaining row
 * names a target that may still have foreign keys off.
 */
@Slf4j
@Component
public class ConstraintStateHealer {

    private final ConstraintStateStore states;
    private final DatabaseConfigRepository databaseConfigs;
    private final DataSourceManager dataSourceManager;
    private final SqlDialectFactory dialects;
    private final SyncLockService lockService;

    public ConstraintStateHealer(ConstraintStateStore states,
                                 DatabaseConfigRepository databaseConfigs,
                                 DataSourceManager dataSourceManager,
                                 SqlDialectFactory dialects,
                                 SyncLockService lockService) {
        this.states = states;
        this.databaseConfigs = databaseConfigs;
        this.dataSourceManager = dataSourceManager;
        this.dialects = dialects;
        this.lockService = lockService;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(90)
    public void healAtStartup() {
        heal();
    }

    /** Retries later when the target was down at startup; the marker stays until it works. */
    @Scheduled(initialDelay = 300_000L, fixedDelay = 300_000L)
    public void healPeriodically() {
        heal();
    }

    void heal() {
        List<ConstraintState> pending;
        try {
            pending = states.findAll();
        } catch (RuntimeException e) {
            log.warn("Could not read disabled-constraint markers: {}", e.toString());
            return;
        }
        for (ConstraintState state : pending) {
            try {
                // Another node may be mid-cycle with its constraints deliberately off; its
                // lease is the truth. A killed owner leaves no live lease and gets healed.
                if (lockService.isLeaseHeldByLiveOwner(state.getProjectId())) {
                    continue;
                }
                heal(state);
            } catch (Exception e) {
                log.warn("Could not restore constraints for project {}: {}",
                        state.getProjectId(), e.toString());
            }
        }
    }

    private void heal(ConstraintState state) throws SQLException {
        DatabaseConfig target = state.getTargetDbId() == null ? null
                : databaseConfigs.findById(state.getTargetDbId()).orElse(null);
        if (target == null) {
            log.warn("Dropping disabled-constraint marker for project {}: target connection "
                    + "{} no longer exists", state.getProjectId(), state.getTargetDbId());
            states.clear(state);
            return;
        }
        SqlDialect dialect = dialects.forType(target.effectiveType());
        List<String> tables = state.getTables() == null ? List.of()
                : state.getTables().lines().filter(line -> !line.isBlank()).toList();
        if (tables.isEmpty()) {
            states.clear(state);
            return;
        }
        try (Connection connection = dataSourceManager.getConnection(target)) {
            boolean failed = false;
            for (String sql : dialect.getConstraintToggleStatements(
                    state.getSchemaName(), tables, false)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                } catch (SQLException e) {
                    failed = true;
                    log.error("Could not restore constraints for project {} on {}: {}",
                            state.getProjectId(), target.getName(), e.getMessage());
                }
            }
            if (failed) {
                return;   // keep the marker; the next startup/scheduled pass retries
            }
        }
        // A cycle may have acquired the lease while the restore was running. Re-check and
        // only delete the exact marker we healed; a fresh marker belongs to that live cycle.
        if (lockService.isLeaseHeldByLiveOwner(state.getProjectId())) {
            log.debug("Keeping the disabled-constraint marker of project {}: a cycle started"
                    + " while it was being restored", state.getProjectId());
            return;
        }
        states.clearIfUnchanged(state);
        log.info("Restored target constraints left disabled by an earlier crash for project {}"
                + " ({} table(s))", state.getProjectId(), tables.size());
    }
}
