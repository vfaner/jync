package com.qqmu.jync.service.diagnostics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.AuditEvent;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.AuditEventRepository;
import com.qqmu.jync.repository.CdcPositionRepository;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.log.SystemLogService;
import com.qqmu.jync.service.task.SyncContextFactory;

/**
 * Assembles the support bundle behind {@code POST /api/diagnostics/bundle}.
 *
 * <p>Membership is a whitelist, not a blacklist: every entry is projected field by field, so
 * a column added to {@link DatabaseConfig} or {@link SyncProperties} later cannot end up in a
 * bundle by accident. Passwords, encrypted payloads, connection URLs with embedded
 * credentials, extra parameters, custom jar paths, cursor values and CDC layout signatures
 * are never copied. {@link DiagnosticRedactor} then rewrites the free text that does pass
 * (change-log details, audit details, log lines).
 *
 * <p>Everything is built in memory under a hard size ceiling; a bundle that would exceed it
 * fails with {@link DiagnosticBundleTooLargeException} instead of exhausting the heap.
 */
@Service
public class DiagnosticBundleService {

    public static final long MAX_TOTAL_BYTES = 10L * 1024 * 1024;

    static final long MAX_JSON_BYTES = 1024L * 1024;
    static final long MAX_LOG_BYTES = 2L * 1024 * 1024;
    static final int LOG_LINES = 2000;
    static final int RECENT_ROWS = 200;
    static final int DETAIL_CHARS = 500;

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final String README = """
            Jync diagnostics bundle
            =======================

            What this archive is for
              It gives support the application version, the shape of the configuration,
              the current sync progress, the recent change and admin-audit history, and
              the tail of the application log - without asking you to collect files by
              hand.

            What it deliberately does not contain
              Database passwords, encrypted password payloads, API keys, webhook URLs,
              connection strings with embedded credentials, custom driver paths, cursor
              values, CDC layout signatures, the encryption key file, the application.yml
              as written, or the embedded database files.

            Free text is best-effort redacted
              Log lines, change-log details and audit details pass through a redactor that
              masks assignment-like values, bearer tokens, URL user info, sensitive query
              parameters, encrypted payloads and private-key blocks. The redactor is a
              second line of defence, not a guarantee: treat this archive as sensitive.
              Review it before sharing outside your organisation.

            Size ceilings
              JSON entries are capped at 1 MB, the log tail at 2 MB / 2000 lines, and the
              whole bundle at 10 MB before compression.

            manifest.json
              Lists every other entry with its size and SHA-256 so the archive can be
              checked for accidental modification in transit.
            """;

    private final ProjectRepository projects;
    private final DatabaseConfigRepository connections;
    private final SyncProgressRepository progress;
    private final CdcPositionRepository cdcPositions;
    private final ChangeLogRepository changeLogs;
    private final AuditEventRepository audits;
    private final SystemLogService systemLog;
    private final SyncContextFactory contextFactory;
    private final ObjectMapper mapper;
    private final DiagnosticRedactor redactor;
    private final SyncProperties properties;
    private final String version;
    private final int port;
    private final long maxTotalBytes;

    @Autowired
    public DiagnosticBundleService(ProjectRepository projects,
                                  DatabaseConfigRepository connections,
                                  SyncProgressRepository progress,
                                  CdcPositionRepository cdcPositions,
                                  ChangeLogRepository changeLogs,
                                  AuditEventRepository audits,
                                  SystemLogService systemLog,
                                  SyncContextFactory contextFactory,
                                  ObjectMapper mapper,
                                  DiagnosticRedactor redactor,
                                  SyncProperties properties,
                                  @Value("${app.version:unknown}") String version,
                                  @Value("${server.port:8080}") int port) {
        this(projects, connections, progress, cdcPositions, changeLogs, audits, systemLog,
                contextFactory, mapper, redactor, properties, version, port, MAX_TOTAL_BYTES);
    }

    /** Test seam for the size ceiling. */
    DiagnosticBundleService(ProjectRepository projects,
                            DatabaseConfigRepository connections,
                            SyncProgressRepository progress,
                            CdcPositionRepository cdcPositions,
                            ChangeLogRepository changeLogs,
                            AuditEventRepository audits,
                            SystemLogService systemLog,
                            SyncContextFactory contextFactory,
                            ObjectMapper mapper,
                            DiagnosticRedactor redactor,
                            SyncProperties properties,
                            String version,
                            int port,
                            long maxTotalBytes) {
        this.projects = projects;
        this.connections = connections;
        this.progress = progress;
        this.cdcPositions = cdcPositions;
        this.changeLogs = changeLogs;
        this.audits = audits;
        this.systemLog = systemLog;
        this.contextFactory = contextFactory;
        this.mapper = mapper;
        this.redactor = redactor;
        this.properties = properties;
        this.version = version;
        this.port = port;
        this.maxTotalBytes = maxTotalBytes;
    }

    public DiagnosticBundle build() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        List<Project> projectList = projects.findAll();
        List<DatabaseConfig> connectionList = connections.findAllByOrderByNameAsc();

        add(entries, "README.txt", README.getBytes(StandardCharsets.UTF_8), MAX_JSON_BYTES);
        add(entries, "summary.json", json(summary(projectList, connectionList)), MAX_JSON_BYTES);
        add(entries, "config/sync-settings.json", json(syncSettings()), MAX_JSON_BYTES);
        add(entries, "config/app-settings.json", json(appSettings()), MAX_JSON_BYTES);
        add(entries, "connections.json", json(connections(connectionList)), MAX_JSON_BYTES);
        add(entries, "projects.json", json(projects(projectList)), MAX_JSON_BYTES);

        for (Project project : projectList) {
            Long id = project.getId();
            add(entries, "projects/" + id + "/progress.json",
                    json(progressRows(id)), MAX_JSON_BYTES);
            cdcPosition(id).ifPresent(position ->
                    add(entries, "projects/" + id + "/cdc-position.json",
                            json(position), MAX_JSON_BYTES));
        }

        add(entries, "changelog/recent.json", json(recentChanges()), MAX_JSON_BYTES);
        add(entries, "audit/recent.json", json(recentAudits()), MAX_JSON_BYTES);
        add(entries, "logs/jync.log.tail.txt", logTail(), MAX_LOG_BYTES);
        add(entries, "jvm.json", json(jvm()), MAX_JSON_BYTES);

        add(entries, "manifest.json", json(manifest(entries)), MAX_JSON_BYTES);

        byte[] zip = zip(entries);
        return new DiagnosticBundle(zip,
                "jync-diagnostics-" + LocalDateTime.now().format(FILE_STAMP) + ".zip",
                entries.size());
    }

    private Map<String, Object> summary(List<Project> projectList,
                                        List<DatabaseConfig> connectionList) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("generatedAt", Instant.now());
        summary.put("version", version);
        summary.put("projects", projectList.size());
        summary.put("connections", connectionList.size());
        summary.put("logLines", LOG_LINES);
        return summary;
    }

    /**
     * Explicit projection: {@link SyncProperties} also carries {@code cryptoPassword} and
     * {@code cryptoSalt}, and a future field must be added here deliberately to be shared.
     */
    private Map<String, Object> syncSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("pollInterval", properties.getPollInterval());
        settings.put("maxRetries", properties.getMaxRetries());
        settings.put("batchSize", properties.getBatchSize());
        settings.put("fetchSize", properties.getFetchSize());
        settings.put("fullLoadBatchSize", properties.getFullLoadBatchSize());
        settings.put("fullLoadFetchSize", properties.getFullLoadFetchSize());
        settings.put("initialLoadInsertFirst", properties.isInitialLoadInsertFirst());
        settings.put("chunkSize", properties.getChunkSize());
        settings.put("fullLoadParallelism", properties.getFullLoadParallelism());
        settings.put("deferSecondaryIndexes", properties.isDeferSecondaryIndexes());
        settings.put("safetyLagMs", properties.getSafetyLagMs());
        settings.put("fullCompareMaxRows", properties.getFullCompareMaxRows());
        settings.put("deleteCompareMaxRows", properties.getDeleteCompareMaxRows());
        settings.put("maxPoolSize", properties.getMaxPoolSize());
        settings.put("poolWaitMs", properties.getPoolWaitMs());
        settings.put("lockTtlMs", properties.getLockTtlMs());
        settings.put("stuckThresholdMs", properties.getStuckThresholdMs());
        settings.put("rowCountAuditIntervalMs", properties.getRowCountAuditIntervalMs());
        settings.put("maxUnindexedScanRows", properties.getMaxUnindexedScanRows());
        settings.put("rowCountRepairMaxRows", properties.getRowCountRepairMaxRows());
        settings.put("queryTimeoutSeconds", properties.getQueryTimeoutSeconds());
        settings.put("copyTimeoutSeconds", properties.getCopyTimeoutSeconds());
        settings.put("metadataQueryTimeoutSeconds", properties.getMetadataQueryTimeoutSeconds());
        settings.put("shutdownWaitMs", properties.getShutdownWaitMs());
        settings.put("changeLogRetentionDays", properties.getChangeLogRetentionDays());
        return settings;
    }

    private Map<String, Object> appSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("version", version);
        settings.put("port", port);
        settings.put("timezone", java.util.TimeZone.getDefault().getID());
        settings.put("locale", java.util.Locale.getDefault().toLanguageTag());
        settings.put("profile", System.getProperty("spring.profiles.active", "default"));
        return settings;
    }

    /** Hosts and names only: no password, URL, extra parameters or custom jar path. */
    private List<Map<String, Object>> connections(List<DatabaseConfig> list) {
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (DatabaseConfig c : list) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", c.getId());
            map.put("name", c.getName());
            map.put("type", name(c.getType()));
            map.put("dialectFamily", name(c.getDialectFamily()));
            map.put("role", name(c.getRole()));
            map.put("host", c.getHost());
            map.put("port", c.getPort());
            map.put("databaseName", c.getDatabaseName());
            map.put("schemaName", c.getSchemaName());
            map.put("username", c.getUsername());
            map.put("createdAt", c.getCreatedAt());
            map.put("updatedAt", c.getUpdatedAt());
            out.add(map);
        }
        return out;
    }

    private List<Map<String, Object>> projects(List<Project> list) {
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Project p : list) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", p.getId());
            map.put("name", p.getName());
            map.put("description", p.getDescription());
            map.put("sourceDbId", p.getSourceDbId());
            map.put("targetDbId", p.getTargetDbId());
            map.put("enabled", p.getEnabled());
            map.put("createdAt", p.getCreatedAt());
            map.put("updatedAt", p.getUpdatedAt());
            map.put("config", configSummary(p));
            out.add(map);
        }
        return out;
    }

    private Map<String, Object> configSummary(Project project) {
        Map<String, Object> summary = new LinkedHashMap<>();
        try {
            SyncConfig config = contextFactory.parseConfig(project);
            summary.put("captureStrategy", name(config.getCaptureStrategyOrDefault()));
            summary.put("syncStructure", config.isSyncStructure());
            summary.put("syncData", config.isSyncData());
            summary.put("syncDeletes", config.isSyncDeletes());
            summary.put("tablesSelected", config.getTables().size());
            summary.put("viewsSelected", config.getViews().size());
            summary.put("proceduresSelected", config.getProcedures().size());
            summary.put("tableNameMappings", config.getTableNameMapping().size());
            summary.put("cursorOverrides", config.getCursorColumns().size());
            summary.put("columnRules", config.getColumnRules().size());
        } catch (RuntimeException ex) {
            summary.put("error", ex.getClass().getSimpleName());
        }
        return summary;
    }

    /** Cursor values are high-watermarks, not for sharing: they are omitted, not redacted. */
    private List<Map<String, Object>> progressRows(Long projectId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SyncProgress row : progress.findByProjectId(projectId)) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("objectType", name(row.getObjectType()));
            map.put("objectName", row.getObjectName());
            map.put("cursorColumn", row.getCursorColumn());
            map.put("cursorStrategy", row.getCursorStrategy());
            map.put("resumableLoad", row.getResumableLoad());
            map.put("initialLoadDone", row.getInitialLoadDone());
            map.put("rowsSyncedTotal", row.getRowsSyncedTotal());
            map.put("lastSyncTime", row.getLastSyncTime());
            out.add(map);
        }
        return out;
    }

    private Optional<Map<String, Object>> cdcPosition(Long projectId) {
        return cdcPositions.findByProjectId(projectId).map(position -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("binlogFile", position.getBinlogFile());
            map.put("binlogPos", position.getBinlogPos());
            map.put("scn", position.getScn());
            map.put("lsn", position.getLsn());
            map.put("serverId", position.getServerId());
            map.put("baselineDone", position.isBaselineDone());
            map.put("updatedAt", position.getUpdatedAt());
            return map;
        });
    }

    private List<Map<String, Object>> recentChanges() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChangeLog change : changeLogs.findAllByOrderByOccurredAtDesc(
                PageRequest.of(0, RECENT_ROWS, Sort.by(Sort.Direction.DESC, "occurredAt")))
                .getContent()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", change.getId());
            map.put("projectId", change.getProjectId());
            map.put("objectType", name(change.getObjectType()));
            map.put("objectName", change.getObjectName());
            map.put("changeType", name(change.getChangeType()));
            map.put("affectedRows", change.getAffectedRows());
            map.put("success", change.getSuccess());
            map.put("durationMs", change.getDurationMs());
            map.put("occurredAt", change.getOccurredAt());
            map.put("details", detail(change.getDetails()));
            out.add(map);
        }
        return out;
    }

    private List<Map<String, Object>> recentAudits() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AuditEvent event : audits.findAll(
                PageRequest.of(0, RECENT_ROWS, Sort.by(Sort.Direction.DESC, "occurredAt")))
                .getContent()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", event.getId());
            map.put("occurredAt", event.getOccurredAt());
            map.put("actor", event.getActor());
            map.put("action", name(event.getAction()));
            map.put("detail", detail(event.getDetail()));
            out.add(map);
        }
        return out;
    }

    private byte[] logTail() {
        StringBuilder text = new StringBuilder();
        for (String line : systemLog.rawTail(LOG_LINES, MAX_LOG_BYTES)) {
            text.append(redactor.redact(line)).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Map<String, Object> jvm() {
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("javaVersion", System.getProperty("java.version"));
        map.put("javaVendor", System.getProperty("java.vendor"));
        map.put("vmName", System.getProperty("java.vm.name"));
        map.put("osName", System.getProperty("os.name"));
        map.put("osVersion", System.getProperty("os.version"));
        map.put("osArch", System.getProperty("os.arch"));
        map.put("availableProcessors", runtime.availableProcessors());
        map.put("maxMemoryBytes", runtime.maxMemory());
        map.put("totalMemoryBytes", runtime.totalMemory());
        map.put("freeMemoryBytes", runtime.freeMemory());
        return map;
    }

    private Map<String, Object> manifest(Map<String, byte[]> entries) {
        List<Map<String, Object>> listed = new ArrayList<>(entries.size());
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", entry.getKey());
            row.put("size", entry.getValue().length);
            row.put("sha256", sha256(entry.getValue()));
            listed.add(row);
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("generatedAt", Instant.now());
        manifest.put("version", version);
        manifest.put("entries", listed);
        return manifest;
    }

    private String detail(String value) {
        if (value == null) {
            return null;
        }
        String redacted = redactor.redact(value);
        return redacted.length() <= DETAIL_CHARS
                ? redacted
                : redacted.substring(0, DETAIL_CHARS);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private byte[] json(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (IOException ex) {
            throw new IllegalStateException("error.diagnostics.serialize", ex);
        }
    }

    private void add(Map<String, byte[]> entries, String name, byte[] bytes, long perEntryLimit) {
        long total = bytes.length;
        for (byte[] existing : entries.values()) {
            total += existing.length;
        }
        if (bytes.length > perEntryLimit || total > maxTotalBytes) {
            throw new DiagnosticBundleTooLargeException();
        }
        entries.put(name, bytes);
    }

    private static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("error.diagnostics.serialize", ex);
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Thrown when the bundle would exceed the in-memory ceiling; the controller maps it to 413. */
    public static class DiagnosticBundleTooLargeException extends RuntimeException {
        public DiagnosticBundleTooLargeException() {
            super("error.diagnostics.tooLarge");
        }
    }

    public record DiagnosticBundle(byte[] zip, String filename, int entryCount) {
    }
}
