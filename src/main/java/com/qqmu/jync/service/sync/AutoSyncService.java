package com.qqmu.jync.service.sync;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import org.springframework.stereotype.Service;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * Adds source tables that appear after the project's 「自动同步新增表」 switch was enabled.
 *
 * <p>The first cycle after enabling snapshots the current source table list; only tables seen
 * later are added, so flipping the switch on an existing project never sweeps in tables the
 * operator deliberately left unselected. Auto-added names are persisted into the project's
 * selection and the snapshot, so a restart (or the operator removing one by hand) does not
 * add the same table again.
 *
 * <p>This service only owns discovery/persistence. The polling engine then reads the enlarged
 * selection and gives each new table its normal first full load; log-CDC projects reconcile
 * through their consumer's per-table baseline path instead.
 */
@Slf4j
@Service
public class AutoSyncService {

    /** Safety valve: one bad migration must not enqueue hundreds of tables in a single cycle. */
    static final int MAX_PER_CYCLE = 20;

    private final ProjectRepository projects;
    private final ObjectMapper objectMapper;
    private final SyncStateWriter stateWriter;

    public AutoSyncService(ProjectRepository projects, ObjectMapper objectMapper,
                           SyncStateWriter stateWriter) {
        this.projects = projects;
        this.objectMapper = objectMapper;
        this.stateWriter = stateWriter;
    }

    /**
     * @return the source-spelling names added this call (empty when disabled, on the snapshot
     *     cycle, or when nothing new appeared)
     */
    public synchronized List<String> reconcile(Project project, SyncConfig config,
                                                List<String> sourceTables) {
        return reconcile(project, config, sourceTables, table -> true);
    }

    /**
     * @param eligible decides whether a newly seen table may be auto-added (for example, the
     *     CDC path requires a primary key); rejected names are simply not added
     */
    public synchronized List<String> reconcile(Project project, SyncConfig config,
                                                List<String> sourceTables,
                                                Predicate<String> eligible) {
        if (!config.isAutoSyncNewTables() || sourceTables == null) {
            return List.of();
        }
        // A legacy "sync everything" project already picks new tables up by definition.
        if (!config.isObjectSelectionExplicit() && config.getTables().isEmpty()) {
            return List.of();
        }
        List<String> known = config.getAutoSyncKnownTables();
        if (known == null) {
            config.setAutoSyncKnownTables(new ArrayList<>(sourceTables));
            persist(project, config);
            log.info("Project '{}': auto-sync enabled; snapshotted {} existing source table(s)",
                    project.getName(), sourceTables.size());
            return List.of();
        }

        Set<String> knownUpper = upper(known);
        Set<String> selectedUpper = upper(config.getTables());
        Set<String> ignoredUpper = upper(config.getAutoSyncIgnoredTables());
        List<String> added = new ArrayList<>();
        for (String table : sourceTables) {
            if (table == null || table.isBlank()) {
                continue;
            }
            String key = table.toUpperCase(Locale.ROOT);
            if (knownUpper.contains(key) || selectedUpper.contains(key)
                    || ignoredUpper.contains(key)) {
                continue;
            }
            if (!eligible.test(table)) {
                continue;
            }
            if (added.size() >= MAX_PER_CYCLE) {
                log.warn("Project '{}': more than {} new source tables appeared in one cycle;"
                                + " the rest will be added on a later cycle",
                        project.getName(), MAX_PER_CYCLE);
                break;
            }
            added.add(table);
            knownUpper.add(key);
            selectedUpper.add(key);
        }
        if (added.isEmpty()) {
            return List.of();
        }
        config.getTables().addAll(added);
        known.addAll(added);
        persist(project, config);
        String names = String.join(", ", added);
        log.info("Project '{}': auto-sync added {} new source table(s): {}",
                project.getName(), added.size(), names);
        stateWriter.recordLog(project.getId(), ObjectType.PROJECT, project.getName(),
                ChangeType.UPDATE, "auto-sync added " + added.size() + " new table(s): "
                        + names, true, 0L);
        return added;
    }

    /** Undoes a batch whose baseline failed, so the next attempt sees the tables as new. */
    public synchronized void rollback(Project project, SyncConfig config, List<String> added) {
        if (added == null || added.isEmpty()) {
            return;
        }
        Set<String> upper = upper(added);
        config.getTables().removeIf(t -> t != null && upper.contains(t.toUpperCase(Locale.ROOT)));
        if (config.getAutoSyncKnownTables() != null) {
            config.getAutoSyncKnownTables().removeIf(t -> t != null
                    && upper.contains(t.toUpperCase(Locale.ROOT)));
        }
        persist(project, config);
    }

    /** Records a candidate as permanently ignored (for example, a keyless table). */
    public synchronized void ignore(Project project, SyncConfig config, String table) {
        if (table == null || table.isBlank()) {
            return;
        }
        List<String> ignored = config.getAutoSyncIgnoredTables();
        if (ignored.stream().anyMatch(t -> t.equalsIgnoreCase(table))) {
            return;
        }
        ignored.add(table);
        persist(project, config);
    }

    private void persist(Project project, SyncConfig config) {
        try {
            project.setSyncConfig(objectMapper.writeValueAsString(config));
            projects.save(project);
        } catch (RuntimeException e) {
            // The in-memory config still carries the new names, so the current cycle uses
            // them; the next cycle retries the snapshot/write.
            log.warn("Project '{}': could not persist the auto-sync table list: {}",
                    project.getName(), e.getMessage());
        }
    }

    private static Set<String> upper(Collection<String> names) {
        Set<String> out = new HashSet<>();
        if (names != null) {
            for (String name : names) {
                if (name != null) {
                    out.add(name.toUpperCase(Locale.ROOT));
                }
            }
        }
        return out;
    }
}
