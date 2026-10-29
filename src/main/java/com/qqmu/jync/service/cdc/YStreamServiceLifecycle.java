package com.qqmu.jync.service.cdc;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.service.cdc.YStreamPositionCodec.YStreamPosition;
import com.qqmu.jync.service.cdc.YStreamServiceAdmin.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * The YStream service decision table (spec 5.3): given what the source holds and what Jync
 * has stored, decide whether this project resumes or rebuilds — then leave the service
 * started and the position row holding the anchor to resume from.
 *
 * <p>Why this is a separate decision and not part of the loop: resuming a stored position
 * against a service that no longer covers it silently loses every change in between, so
 * "may I resume?" must be answered from evidence (service exists, position row exists and
 * decodes, baseline completed), never assumed. Every branch that cannot prove continuity
 * drops what is there and re-baselines.
 *
 * <p>Ruling (no stored token): the spec's "服务名带令牌" is enforced by treating a service as
 * admissible only when it is the ONE service of this project and a stored position exists.
 * The token cannot be compared across restarts because Jync stores none — but a rebuilt
 * service always leaves either a second service (a create raced the drop) or no position
 * continuity, and both fall into the rebuild branch below. A rebuild that leaves exactly
 * one service and an intact position row is indistinguishable from an ordinary restart;
 * the client then rejects the stale position at {@code open()} instead of replaying.
 */
@Slf4j
@Component
public class YStreamServiceLifecycle {

    /**
     * What the consumer needs to attach.
     *
     * @param serviceName      the service now running for this project
     * @param attachFrom       the position to open {@code RECOVER} at
     * @param baselineRequired whether the full baseline must run before attaching
     */
    public record Outcome(String serviceName, YStreamPosition attachFrom,
                          boolean baselineRequired) {
    }

    private final CdcPositionStore positionStore;

    public YStreamServiceLifecycle(CdcPositionStore positionStore) {
        this.positionStore = positionStore;
    }

    public Outcome ensure(long projectId, List<String> tables, YStreamServiceOps ops)
            throws SQLException {
        if (tables == null || tables.isEmpty()) {
            throw new IllegalArgumentException("YStream CDC requires at least one selected"
                    + " table: an empty service would capture nothing and still hold a slot");
        }
        List<String> wanted = new ArrayList<>(tables);
        CdcPosition row = positionStore.find(projectId).orElse(null);
        YStreamPosition stored = storedPosition(row);
        List<Service> mine = ops.services(projectId);

        // Adopt only the unambiguous case: exactly one service and a position that decodes.
        // Everything else (a leftover pair, a service whose row is gone, a row whose
        // service is gone) is rebuilt — a re-baseline costs one full read, resuming from a
        // position that may not belong to this service costs silent data loss.
        if (mine.size() != 1 || stored == null) {
            for (Service service : mine) {
                log.info("Dropping YStream service {} of project {} ({} service(s), stored"
                        + " position {}) before rebuilding", service.name(), projectId,
                        mine.size(), stored == null ? "absent" : "present but not usable");
                ops.drop(service);
            }
            if (row != null) {
                positionStore.delete(projectId);
            }
            return createFresh(projectId, wanted, ops);
        }

        Service service = mine.get(0);
        reconcileTables(service.name(), wanted, ops);
        resetIfRunning(service, ops);
        boolean baselineRequired = !Boolean.TRUE.equals(row.isBaselineDone());
        log.info("Attaching to YStream service {} for project {} at {} (baseline {})",
                service.name(), projectId, stored.text(),
                baselineRequired ? "required" : "done");
        return new Outcome(service.name(), stored, baselineRequired);
    }

    /**
     * CREATE (fresh token) → ADD_TABLES → START → record the anchor. The anchor comes from
     * {@code RESTART_POSITION} AFTER {@code START} because that is the earliest position the
     * service will deliver from — the baseline's own changes are inside it, so nothing
     * between baseline and stream is lost.
     */
    private Outcome createFresh(long projectId, List<String> tables, YStreamServiceOps ops)
            throws SQLException {
        String name = YStreamServiceAdmin.serviceName(projectId, YStreamServiceAdmin.newToken());
        ops.create(name);
        ops.addTables(name, tables);
        ops.start(name);
        Service created = ops.services(projectId).stream()
                .filter(service -> name.equals(service.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("YStream service " + name
                        + " is gone right after CREATE — another session is racing this"
                        + " project"));
        YStreamPosition anchor = anchorOf(created);
        positionStore.recordBaselineStartLsn(projectId,
                YStreamPositionCodec.encode(anchor));
        log.info("Created YStream service {} for project {}; baseline anchor {}",
                name, projectId, anchor.text());
        return new Outcome(name, anchor, true);
    }

    private static YStreamPosition anchorOf(Service created) {
        String restart = created.restartPosition();
        if (restart == null || restart.isBlank()) {
            throw new IllegalStateException("YStream service " + created.name()
                    + " reports no RESTART_POSITION after START; refusing to baseline"
                    + " without a resume anchor");
        }
        try {
            return YStreamPositionCodec.parseText(restart.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("YStream service " + created.name()
                    + " reported an unreadable RESTART_POSITION '" + restart + "'", e);
        }
    }

    /**
     * ADD_TABLES for whatever the service does not capture yet. Only ever adds: a table the
     * user removed stays in scope and its rows are filtered out downstream, because
     * shrinking the scope would force a re-baseline of every remaining table.
     */
    private static void reconcileTables(String serviceName, List<String> wanted,
                                        YStreamServiceOps ops) throws SQLException {
        Set<String> already = ops.captured(serviceName);
        List<String> missing = new ArrayList<>();
        for (String table : wanted) {
            if (!containsIgnoreCase(already, table)) {
                missing.add(table);
            }
        }
        if (!missing.isEmpty()) {
            ops.addTables(serviceName, missing);
        }
    }

    private static boolean containsIgnoreCase(Set<String> set, String wanted) {
        for (String entry : set) {
            if (entry != null && entry.equalsIgnoreCase(wanted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A {@code RUNNING} service means a client is still attached although no consumer of
     * this project runs here — a leaked client from a previous process. STOP+START resets it
     * without moving {@code START_SCN} or {@code RESTART_POSITION}, so the stored position
     * stays valid. {@code CREATED} (never started, or stopped by the release path) is
     * started; {@code STARTED} is left alone.
     */
    private static void resetIfRunning(Service service, YStreamServiceOps ops)
            throws SQLException {
        String status = service.effectiveStatus();
        if (YStreamServiceAdmin.STATUS_RUNNING.equals(status)) {
            log.warn("YStream service {} is RUNNING without a consumer of this project;"
                    + " resetting it with STOP+START", service.name());
            ops.stop(service.name());
            ops.start(service.name());
        } else if (!YStreamServiceAdmin.STATUS_STARTED.equals(status)) {
            log.info("Starting YStream service {} (status {})", service.name(),
                    status.isEmpty() ? "unknown" : status.toUpperCase(Locale.ROOT));
            ops.start(service.name());
        }
    }

    /**
     * The stored resume position, or null when the row is missing or holds a value this
     * codec did not write — a foreign or corrupt position must never be silently decoded
     * into a different point and resumed from.
     */
    private YStreamPosition storedPosition(CdcPosition row) {
        if (row == null || row.getLsn() == null || row.getLsn().isBlank()) {
            return null;
        }
        try {
            return YStreamPositionCodec.decode(row.getLsn());
        } catch (IllegalArgumentException e) {
            log.warn("Stored YStream position of project {} is not resumable ({});"
                    + " discarding it", row.getProjectId(), e.getMessage());
            return null;
        }
    }
}
