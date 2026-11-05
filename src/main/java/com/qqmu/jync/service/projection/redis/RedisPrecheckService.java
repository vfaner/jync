package com.qqmu.jync.service.projection.redis;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.qqmu.jync.dto.PrecheckReport;
import com.qqmu.jync.dto.RedisProjectionConfig;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.cdc.CdcProbeResult;
import com.qqmu.jync.service.cdc.CdcTableBinding;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.VendorTypeMap;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.task.SyncContextFactory;

import lombok.extern.slf4j.Slf4j;

/**
 * The precheck for a project whose target is Redis.
 *
 * <p>Separate from {@code SyncPrecheckService} because almost nothing is shared: a cache has no
 * target DDL, no columns to compare and no cursor to index, while it does have TLS, cluster
 * slot coverage, eviction policy, key-prefix ownership and a value format that a database
 * precheck has never heard of. It returns the same {@link PrecheckReport.Check} type so both
 * reports render through one page and one API.
 *
 * <p>Read-only: it opens a source connection, asks the server questions and stops there. No
 * repository save, no audit record, no CDC position advance, no key written.
 */
@Service
@Slf4j
public class RedisPrecheckService {

    private static final String REDIS = "REDIS";
    private static final String PROJECT = "PROJECT";
    private static final String CONNECTION = "CONNECTION";
    private static final String TABLE = "TABLE";
    private static final String CDC = "CDC";

    /** A Redis cluster has exactly this many hash slots. */
    private static final int CLUSTER_SLOTS = 16_384;

    private final RedisProbeFactory probes;
    private final DataSourceManager dataSourceManager;
    private final SyncContextFactory contextFactory;
    private final RedisProjectionPolicy policy;
    private final CdcOperationsService cdcOperations;
    private final CdcPositionStore cdcPositions;

    public RedisPrecheckService(RedisProbeFactory probes,
                                DataSourceManager dataSourceManager,
                                SyncContextFactory contextFactory,
                                RedisProjectionPolicy policy,
                                CdcOperationsService cdcOperations,
                                CdcPositionStore cdcPositions) {
        this.probes = probes;
        this.dataSourceManager = dataSourceManager;
        this.contextFactory = contextFactory;
        this.policy = policy;
        this.cdcOperations = cdcOperations;
        this.cdcPositions = cdcPositions;
    }

    /**
     * Every check a Redis projection has to pass, in a stable order: endpoint, projection
     * contract, source shape, then CDC readiness.
     */
    public List<PrecheckReport.Check> precheck(Project project, RedisTarget target,
                                               SyncConfig config) {
        List<PrecheckReport.Check> checks = new ArrayList<>();
        RedisProjectionConfig projection = projectionOf(project, config);

        checkEndpoint(target, checks);
        checkPrefix(project, projection, checks);
        checkValueFormat(projection, checks);
        checkTtl(projection, checks);

        SyncContext ctx = sourceContext(project, config, checks);
        DatabaseMeta sourceMeta = ctx == null ? null : readSource(ctx, checks);
        if (sourceMeta != null && ctx != null) {
            List<TableMeta> tables = selectedTables(sourceMeta, config);
            checkExcludedColumns(projection, tables, checks);
            checkLobColumns(projection, tables, checks);
            checkPrimaryKeys(tables, checks);
        }
        checkCdc(project, checks);
        checkBaselineAndLayout(project, ctx, sourceMeta, config, projection, checks);
        return List.copyOf(checks);
    }

    private static RedisProjectionConfig projectionOf(Project project, SyncConfig config) {
        RedisProjectionConfig projection = config == null ? null : config.getRedisProjection();
        return projection == null ? RedisProjectionConfig.defaultFor(project.getId()) : projection;
    }

    // —— endpoint -----------------------------------------------------------------------

    private void checkEndpoint(RedisTarget target, List<PrecheckReport.Check> checks) {
        RedisProbe probe;
        try {
            probe = probes.open(target);
        } catch (RuntimeException e) {
            checks.add(error("redis.ping", REDIS, target.getName(),
                    "precheck.redis.pingFailed", messageOf(e)));
            checks.add(tlsVerdict(target, e));
            return;
        }
        try {
            probe.ping();
            checks.add(ok("redis.ping", REDIS, target.getName(), "precheck.redis.pingOk", null));
            checks.add(tlsVerdict(target, null));
            checkDatabase(target, checks);
            checkCluster(target, probe, checks);
            checkMemoryPolicy(target, probe, checks);
        } catch (RuntimeException e) {
            // The handshake succeeded but the command did not (NOPERM, OOM, timeout): the
            // endpoint is not usable for a projection either way.
            checks.add(error("redis.ping", REDIS, target.getName(),
                    "precheck.redis.pingFailed", messageOf(e)));
            checks.add(tlsVerdict(target, e));
        } finally {
            closeQuietly(probe);
        }
    }

    private PrecheckReport.Check tlsVerdict(RedisTarget target, Throwable failure) {
        if (!target.isTlsEnabled()) {
            return skip("redis.tls", REDIS, target.getName(), "precheck.redis.tlsDisabled", null);
        }
        if (failure == null) {
            return ok("redis.tls", REDIS, target.getName(), "precheck.redis.tlsOk", target.getHost());
        }
        if (isTlsFailure(failure)) {
            return error("redis.tls", REDIS, target.getName(), "precheck.redis.tlsFailed",
                    messageOf(failure));
        }
        // TLS was not the reason the connection failed, so the handshake could not be judged.
        return skip("redis.tls", REDIS, target.getName(), "precheck.redis.tlsUnknown", null);
    }

    private void checkDatabase(RedisTarget target, List<PrecheckReport.Check> checks) {
        int database = target.getDatabaseIndex() == null ? 0 : target.getDatabaseIndex();
        if (target.isClusterMode() && database != 0) {
            checks.add(error("redis.database", REDIS, target.getName(),
                    "precheck.redis.database.cluster", String.valueOf(database)));
            return;
        }
        checks.add(ok("redis.database", REDIS, target.getName(),
                "precheck.redis.database.ok", String.valueOf(database)));
    }

    private void checkCluster(RedisTarget target, RedisProbe probe,
                              List<PrecheckReport.Check> checks) {
        if (!target.isClusterMode()) {
            checks.add(skip("redis.cluster", REDIS, target.getName(),
                    "precheck.redis.cluster.disabled", null));
            return;
        }
        try {
            Map<String, String> info = parseInfo(probe.clusterInfo());
            String state = info.get("cluster_state");
            int assigned = parseInt(info.get("cluster_slots_assigned"), -1);
            if (!"ok".equalsIgnoreCase(state)) {
                checks.add(error("redis.cluster", REDIS, target.getName(),
                        "precheck.redis.cluster.incomplete",
                        "cluster_state=" + state));
            } else if (assigned < CLUSTER_SLOTS) {
                checks.add(error("redis.cluster", REDIS, target.getName(),
                        "precheck.redis.cluster.incomplete",
                        "cluster_slots_assigned=" + assigned + "/" + CLUSTER_SLOTS));
            } else {
                checks.add(ok("redis.cluster", REDIS, target.getName(),
                        "precheck.redis.cluster.ok", String.valueOf(assigned)));
            }
        } catch (RuntimeException e) {
            checks.add(error("redis.cluster", REDIS, target.getName(),
                    "precheck.redis.cluster.incomplete", messageOf(e)));
        }
    }

    private void checkMemoryPolicy(RedisTarget target, RedisProbe probe,
                                   List<PrecheckReport.Check> checks) {
        String policyValue;
        try {
            policyValue = probe.maxmemoryPolicy();
        } catch (RuntimeException e) {
            checks.add(new PrecheckReport.Check("redis.memoryPolicy",
                    PrecheckReport.Severity.WARN, REDIS, target.getName(),
                    "precheck.redis.memoryPolicy.unknown", messageOf(e)));
            return;
        }
        if (policyValue == null || policyValue.isBlank()) {
            checks.add(new PrecheckReport.Check("redis.memoryPolicy",
                    PrecheckReport.Severity.WARN, REDIS, target.getName(),
                    "precheck.redis.memoryPolicy.unknown", null));
        } else if ("noeviction".equalsIgnoreCase(policyValue.trim())) {
            checks.add(new PrecheckReport.Check("redis.memoryPolicy",
                    PrecheckReport.Severity.WARN, REDIS, target.getName(),
                    "precheck.redis.memoryPolicy.noeviction", policyValue));
        } else {
            checks.add(ok("redis.memoryPolicy", REDIS, target.getName(),
                    "precheck.redis.memoryPolicy.ok", policyValue));
        }
    }

    // —— projection contract ------------------------------------------------------------

    private void checkPrefix(Project project, RedisProjectionConfig projection,
                             List<PrecheckReport.Check> checks) {
        String prefix = projection.getKeyPrefix();
        if (prefix == null || prefix.isBlank()) {
            checks.add(error("redis.prefix", PROJECT, project.getName(),
                    "precheck.redis.prefix.blank", null));
            return;
        }
        boolean malformed = !prefix.endsWith(":") || prefix.chars()
                .anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c));
        if (malformed) {
            checks.add(error("redis.prefix", PROJECT, project.getName(),
                    "precheck.redis.prefix.format", prefix));
            return;
        }
        Project conflict = policy.conflictingProject(project, prefix);
        if (conflict != null) {
            checks.add(error("redis.prefix", PROJECT, project.getName(),
                    "precheck.redis.prefix.conflict", conflict.getName() + " · " + prefix));
            return;
        }
        checks.add(ok("redis.prefix", PROJECT, project.getName(),
                "precheck.redis.prefix.ok", prefix));
    }

    private void checkValueFormat(RedisProjectionConfig projection,
                                  List<PrecheckReport.Check> checks) {
        if (projection.getValueFormat() == null) {
            checks.add(error("redis.valueFormat", PROJECT, "valueFormat",
                    "precheck.redis.valueFormat.invalid", null));
            return;
        }
        checks.add(ok("redis.valueFormat", PROJECT, "valueFormat",
                "precheck.redis.valueFormat.ok", projection.getValueFormat().name()));
    }

    /**
     * A cache with no expiry accumulates keys for rows deleted from the source while the
     * consumer was not running: no delete event exists for a change the stream never saw, so
     * only a rebuild with the sweep option clears those keys. Zero TTL therefore stays a
     * warning even though that rebuild exists.
     */
    private void checkTtl(RedisProjectionConfig projection,
                          List<PrecheckReport.Check> checks) {
        if (projection.getTtlSeconds() <= 0) {
            checks.add(new PrecheckReport.Check("redis.ttl", PrecheckReport.Severity.WARN,
                    PROJECT, "ttlSeconds", "precheck.redis.ttl.never", null));
        } else {
            checks.add(ok("redis.ttl", PROJECT, "ttlSeconds", "precheck.redis.ttl.ok",
                    String.valueOf(projection.getTtlSeconds())));
        }
    }

    // —— source -------------------------------------------------------------------------

    private SyncContext sourceContext(Project project, SyncConfig config,
                                      List<PrecheckReport.Check> checks) {
        try {
            return contextFactory.buildSourceOnly(project, config);
        } catch (RuntimeException e) {
            checks.add(error("source.config", PROJECT, project.getName(),
                    "precheck.connection.config", messageOf(e)));
            return null;
        }
    }

    private DatabaseMeta readSource(SyncContext ctx, List<PrecheckReport.Check> checks) {
        Connection conn = null;
        try {
            conn = dataSourceManager.getConnection(ctx.getSourceConfig());
            return ctx.getSourceReader().readAllForData(conn, ctx.getSourceSchema(),
                    selection(ctx.getConfig().getTables(), ctx.getConfig().isObjectSelectionExplicit()),
                    null, null, false, false);
        } catch (SQLException | RuntimeException e) {
            checks.add(error("source.metadata", CONNECTION,
                    ctx.getSourceConfig().getName(), "precheck.source.metadataFailed",
                    messageOf(e)));
            return null;
        } finally {
            closeQuietly(conn);
        }
    }

    private void checkExcludedColumns(RedisProjectionConfig projection, List<TableMeta> tables,
                                      List<PrecheckReport.Check> checks) {
        Set<String> excluded = projection.getExcludedColumns();
        if (excluded == null || excluded.isEmpty()) {
            checks.add(ok("redis.excludedColumns", PROJECT, "excludedColumns",
                    "precheck.redis.excludedColumns.none", null));
            return;
        }
        Set<String> known = new LinkedHashSet<>();
        for (TableMeta table : tables) {
            for (ColumnMeta column : table.getColumns()) {
                known.add(column.getName().toUpperCase(Locale.ROOT));
            }
        }
        List<String> unknown = excluded.stream()
                .filter(name -> name != null
                        && !known.contains(name.toUpperCase(Locale.ROOT)))
                .toList();
        if (unknown.isEmpty()) {
            checks.add(ok("redis.excludedColumns", PROJECT, "excludedColumns",
                    "precheck.redis.excludedColumns.ok", null));
        } else {
            checks.add(new PrecheckReport.Check("redis.excludedColumns",
                    PrecheckReport.Severity.WARN, PROJECT, "excludedColumns",
                    "precheck.redis.excludedColumns.unknown", String.join(", ", unknown)));
        }
    }

    private void checkLobColumns(RedisProjectionConfig projection, List<TableMeta> tables,
                                 List<PrecheckReport.Check> checks) {
        if (projection.getExcludedColumns() == null) {
            return;
        }
        Set<String> excluded = upperCase(projection.getExcludedColumns());
        List<String> large = new ArrayList<>();
        for (TableMeta table : tables) {
            for (ColumnMeta column : table.getColumns()) {
                if (isLargeValueColumn(column)
                        && !excluded.contains(column.getName().toUpperCase(Locale.ROOT))) {
                    large.add(table.getName() + "." + column.getName());
                }
            }
        }
        if (large.isEmpty()) {
            checks.add(ok("redis.lobColumns", PROJECT, "lobColumns",
                    "precheck.redis.lobColumns.ok", null));
        } else {
            checks.add(new PrecheckReport.Check("redis.lobColumns",
                    PrecheckReport.Severity.WARN, PROJECT, "lobColumns",
                    "precheck.redis.lobColumns.present", String.join(", ", large)));
        }
    }

    private void checkPrimaryKeys(List<TableMeta> tables, List<PrecheckReport.Check> checks) {
        if (tables.isEmpty()) {
            checks.add(skip("tables.primaryKey", TABLE, null, "precheck.redis.noTables", null));
            return;
        }
        List<String> keyless = tables.stream()
                .filter(table -> !table.hasPrimaryKey())
                .map(TableMeta::getName)
                .toList();
        if (keyless.isEmpty()) {
            checks.add(ok("tables.primaryKey", TABLE, null, "precheck.redis.primaryKey.ok", null));
        } else {
            checks.add(error("tables.primaryKey", TABLE, null,
                    "precheck.redis.primaryKey.missing", String.join(", ", keyless)));
        }
    }

    // —— CDC ----------------------------------------------------------------------------

    private void checkCdc(Project project, List<PrecheckReport.Check> checks) {
        CdcProbeResult probe;
        try {
            probe = cdcOperations.probeProject(project.getId());
        } catch (IllegalArgumentException e) {
            if ("cdc.probe.unsupported".equals(e.getMessage())) {
                checks.add(skip("source.cdc", CDC, project.getName(),
                        "precheck.cdc.unsupported", null));
            } else {
                checks.add(error("source.cdc", CDC, project.getName(),
                        "precheck.cdc.probeFailed", messageOf(e)));
            }
            return;
        } catch (RuntimeException e) {
            checks.add(error("source.cdc", CDC, project.getName(),
                    "precheck.cdc.probeFailed", messageOf(e)));
            return;
        }
        if (probe == null) {
            checks.add(error("source.cdc", CDC, project.getName(),
                    "precheck.cdc.probeFailed", null));
            return;
        }
        List<CdcProbeResult.Check> failures = probe.failures();
        for (CdcProbeResult.Check failure : failures) {
            checks.add(new PrecheckReport.Check("source.cdc." + failure.getName(),
                    PrecheckReport.Severity.ERROR, CDC, failure.getName(),
                    "cdc.check." + failure.getName(), failure.getDetail()));
        }
        if (failures.isEmpty()) {
            checks.add(ok("source.cdc", CDC, project.getName(), "precheck.redis.cdcOk", null));
        }
    }

    private void checkBaselineAndLayout(Project project, SyncContext ctx, DatabaseMeta sourceMeta,
                                        SyncConfig config, RedisProjectionConfig projection,
                                        List<PrecheckReport.Check> checks) {
        Optional<CdcPosition> position;
        try {
            position = cdcPositions.find(project.getId());
        } catch (RuntimeException e) {
            checks.add(error("baseline.done", CDC, project.getName(),
                    "precheck.cdc.probeFailed", messageOf(e)));
            return;
        }
        position.ifPresent(stored -> checkStoredContract(project, projection, stored, checks));
        if (!position.isPresent() || !Boolean.TRUE.equals(position.get().isBaselineDone())) {
            checks.add(new PrecheckReport.Check("baseline.done", PrecheckReport.Severity.WARN,
                    CDC, project.getName(), "precheck.redis.baselinePending", null));
            checks.add(skip("position.layout", CDC, project.getName(),
                    "precheck.redis.layout.pendingBaseline", null));
            return;
        }
        checks.add(ok("baseline.done", CDC, project.getName(),
                "precheck.redis.baselineDone", null));

        if (ctx == null || sourceMeta == null) {
            checks.add(skip("position.layout", CDC, project.getName(),
                    "precheck.redis.layout.noSource", null));
            return;
        }
        List<TableMeta> tables = selectedTables(sourceMeta, config);
        if (tables.isEmpty()) {
            checks.add(skip("position.layout", CDC, project.getName(),
                    "precheck.redis.noTables", null));
            return;
        }

        Map<String, String> persisted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try {
            persisted.putAll(cdcPositions.layoutSignatures(project.getId()));
        } catch (RuntimeException e) {
            checks.add(error("position.layout", CDC, project.getName(),
                    "precheck.cdc.probeFailed", messageOf(e)));
            return;
        }
        List<String> drifted = new ArrayList<>();
        int missing = 0;
        for (TableMeta table : tables) {
            String current = CdcTableBinding.layoutSignature(table);
            if (current == null) {
                continue;
            }
            String key = ctx.getSourceSchema() + "." + table.getName();
            String recorded = persisted.get(key);
            if (recorded == null) {
                missing++;
            } else if (!recorded.equalsIgnoreCase(current)) {
                drifted.add(key);
            }
        }
        if (!drifted.isEmpty()) {
            checks.add(new PrecheckReport.Check("position.layout", PrecheckReport.Severity.WARN,
                    CDC, project.getName(), "precheck.redis.layoutDrift",
                    String.join(", ", drifted)));
        } else if (missing > 0) {
            checks.add(new PrecheckReport.Check("position.layout", PrecheckReport.Severity.WARN,
                    CDC, project.getName(), "precheck.redis.layoutMissing",
                    String.valueOf(missing)));
        } else {
            checks.add(ok("position.layout", CDC, project.getName(),
                    "precheck.redis.layoutOk", null));
        }
    }

    /**
     * Start refuses a projection whose key-space contract differs from the one recorded when
     * the cache was built — the existing keys are of the other type and the next write would
     * answer {@code WRONGTYPE}. A precheck that stays green on that state is worse than no
     * precheck: the user fixes every row it names and the start still fails.
     *
     * <p>No signature on the row (written before the column existed) is "nothing known", not
     * a mismatch; inventing one would block a cache that is perfectly fine.
     */
    private void checkStoredContract(Project project, RedisProjectionConfig projection,
                                     CdcPosition stored, List<PrecheckReport.Check> checks) {
        String built = stored.getProjectionSignature();
        String current = RedisProjectionSignature.of(projection);
        if (built != null && current != null && !built.equals(current)) {
            checks.add(error("position.contract", CDC, project.getName(),
                    "precheck.redis.contractMismatch", built + " -> " + current));
        }
    }

    // —— helpers ------------------------------------------------------------------------

    private static List<TableMeta> selectedTables(DatabaseMeta meta, SyncConfig config) {
        if (meta == null || meta.getTables() == null || config == null) {
            return List.of();
        }
        return meta.getTables().stream().filter(table -> config.includesTable(table.getName()))
                .toList();
    }

    private static List<String> selection(Set<String> names, boolean explicit) {
        if (names == null || names.isEmpty()) {
            return explicit ? List.of() : null;
        }
        return new ArrayList<>(names);
    }

    private static Set<String> upperCase(Set<String> names) {
        Set<String> upper = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null) {
                upper.add(name.toUpperCase(Locale.ROOT));
            }
        }
        return upper;
    }

    /** A column whose whole value is a poor fit for a single cache entry. */
    private static boolean isLargeValueColumn(ColumnMeta column) {
        String typeName = column.getTypeName() == null ? ""
                : column.getTypeName().toUpperCase(Locale.ROOT);
        if (VendorTypeMap.isNonComparableName(typeName)
                || "JSON".equals(typeName) || "JSONB".equals(typeName)) {
            return true;
        }
        switch (column.getJdbcType()) {
            case Types.BLOB:
            case Types.CLOB:
            case Types.NCLOB:
            case Types.LONGVARBINARY:
            case Types.LONGVARCHAR:
            case Types.LONGNVARCHAR:
            case Types.SQLXML:
            case Types.BINARY:
            case Types.VARBINARY:
                return true;
            default:
                return false;
        }
    }

    /** {@code CLUSTER INFO} is {@code key:value} lines, one per line. */
    private static Map<String, String> parseInfo(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        if (body == null) {
            return values;
        }
        for (String line : body.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                values.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        return values;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean isTlsFailure(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof javax.net.ssl.SSLException) {
                return true;
            }
            String name = t.getClass().getName();
            if (name.contains("SSL") || name.contains("Certificate") || name.contains("PKIX")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static PrecheckReport.Check ok(String code, String objectType, String objectName,
                                           String messageKey, String detail) {
        return new PrecheckReport.Check(code, PrecheckReport.Severity.OK, objectType,
                objectName, messageKey, detail);
    }

    private static PrecheckReport.Check error(String code, String objectType, String objectName,
                                              String messageKey, String detail) {
        return new PrecheckReport.Check(code, PrecheckReport.Severity.ERROR, objectType,
                objectName, messageKey, detail);
    }

    private static PrecheckReport.Check skip(String code, String objectType, String objectName,
                                             String messageKey, String detail) {
        return new PrecheckReport.Check(code, PrecheckReport.Severity.SKIP, objectType,
                objectName, messageKey, detail);
    }

    private static void closeQuietly(RedisProbe probe) {
        try {
            probe.close();
        } catch (RuntimeException e) {
            log.debug("Could not close Redis precheck connection: {}", e.getMessage());
        }
    }

    private static void closeQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.close();
        } catch (SQLException e) {
            log.debug("Could not close precheck connection: {}", e.getMessage());
        }
    }
}
