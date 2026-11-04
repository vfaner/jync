package com.qqmu.jync.service.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.AuditEvent;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.ObjectType;
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
 * The bundle is a whitelist, not a blacklist: entries are built field by field, so a new
 * column on an entity can never leak into it by accident.
 */
class DiagnosticBundleServiceTest {

    private static final long PROJECT_ID = 7L;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    private ProjectRepository projects;
    private DatabaseConfigRepository connections;
    private SyncProgressRepository progress;
    private CdcPositionRepository cdcPositions;
    private ChangeLogRepository changeLogs;
    private AuditEventRepository audits;
    private SystemLogService systemLog;
    private SyncContextFactory contextFactory;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectRepository.class);
        connections = mock(DatabaseConfigRepository.class);
        progress = mock(SyncProgressRepository.class);
        cdcPositions = mock(CdcPositionRepository.class);
        changeLogs = mock(ChangeLogRepository.class);
        audits = mock(AuditEventRepository.class);
        systemLog = mock(SystemLogService.class);
        contextFactory = mock(SyncContextFactory.class);

        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("orders-sync");
        project.setDescription("prod to reporting");
        project.setSourceDbId(1L);
        project.setTargetDbId(2L);
        project.setEnabled(true);
        project.setCreatedAt(Instant.parse("2026-10-01T00:00:00Z"));
        when(projects.findAll()).thenReturn(List.of(project));

        DatabaseConfig source = new DatabaseConfig();
        source.setId(1L);
        source.setName("prod-mysql");
        source.setType(DatabaseType.MYSQL);
        source.setRole(ConnectionRole.SOURCE);
        source.setHost("db.internal");
        source.setPort(3306);
        source.setDatabaseName("app");
        source.setUsername("sync-user");
        source.setPassword("pw-SECRET");
        source.setMinerPassword("miner-SECRET");
        source.setCustomUrl("jdbc:mysql://u:pw-SECRET@db.internal/app");
        source.setExtraParams("useSSL=false&password=pw-SECRET");
        source.setCustomJarPath("/opt/private/driver-SECRET.jar");
        DatabaseConfig target = new DatabaseConfig();
        target.setId(2L);
        target.setName("reporting-pg");
        target.setType(DatabaseType.POSTGRESQL);
        target.setRole(ConnectionRole.TARGET);
        target.setHost("report.internal");
        target.setPort(5432);
        when(connections.findAllByOrderByNameAsc()).thenReturn(List.of(source, target));

        SyncProgress row = new SyncProgress();
        row.setProjectId(PROJECT_ID);
        row.setObjectType(ObjectType.TABLE);
        row.setObjectName("orders");
        row.setCursorColumn("updated_at");
        row.setCursorStrategy("TIMESTAMP");
        row.setLastSyncValue("CURSOR-SECRET");
        row.setInitialLoadDone(true);
        row.setRowsSyncedTotal(120L);
        when(progress.findByProjectId(PROJECT_ID)).thenReturn(List.of(row));

        CdcPosition position = new CdcPosition();
        position.setProjectId(PROJECT_ID);
        position.setBinlogFile("mysql-bin.000012");
        position.setBinlogPos(3456L);
        position.setBaselineDone(true);
        position.setSchemaSignatures("SIGNATURE-SECRET");
        when(cdcPositions.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(position));

        ChangeLog change = new ChangeLog();
        change.setId(1L);
        change.setProjectId(PROJECT_ID);
        change.setObjectType(ObjectType.TABLE);
        change.setObjectName("orders");
        change.setChangeType(ChangeType.ALTER);
        change.setDetails("api_key=log-SECRET");
        change.setOccurredAt(Instant.parse("2026-11-03T00:00:00Z"));
        when(changeLogs.findAllByOrderByOccurredAtDesc(any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(change)));

        AuditEvent audit = new AuditEvent();
        audit.setId(1L);
        audit.setOccurredAt(Instant.parse("2026-11-03T00:00:00Z"));
        audit.setActor("admin");
        audit.setAction(AuditAction.LOGIN);
        audit.setDetail("token=audit-SECRET");
        when(audits.findAll(any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(audit)));

        when(systemLog.rawTail(2000, 2L * 1024 * 1024))
                .thenReturn(List.of("INFO line <b>& with password=logpw-SECRET"));
        when(contextFactory.parseConfig(any())).thenReturn(new SyncConfig());
    }

    private DiagnosticBundleService service() {
        return service(10L * 1024 * 1024);
    }

    private DiagnosticBundleService service(long maxTotalBytes) {
        return new DiagnosticBundleService(projects, connections, progress, cdcPositions,
                changeLogs, audits, systemLog, contextFactory, mapper,
                new DiagnosticRedactor(), new SyncProperties(), "1.12.0", 8080,
                maxTotalBytes);
    }

    private Map<String, String> entries(DiagnosticBundleService.DiagnosticBundle bundle)
            throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(bundle.zip()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(),
                        new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void containsExactlyTheWhitelistedEntries() throws Exception {
        Map<String, String> entries = entries(service().build());

        assertThat(entries.keySet()).containsExactlyInAnyOrder(
                "README.txt",
                "manifest.json",
                "summary.json",
                "config/sync-settings.json",
                "config/app-settings.json",
                "connections.json",
                "projects.json",
                "projects/" + PROJECT_ID + "/progress.json",
                "projects/" + PROJECT_ID + "/cdc-position.json",
                "changelog/recent.json",
                "audit/recent.json",
                "logs/jync.log.tail.txt",
                "jvm.json");
        assertThat(entries.keySet()).noneMatch(name ->
                name.startsWith("data/") || name.endsWith(".key")
                        || name.endsWith(".mv.db") || name.equals("application.yml"));
    }

    @Test
    void syncSettingsIncludeTheContentAuditKnobs() throws Exception {
        Map<String, String> entries = entries(service().build());
        JsonNode settings = mapper.readTree(entries.get("config/sync-settings.json"));

        // The bundle is the support engineer's snapshot of runtime behaviour; a v1.12.0
        // feature that is missing here cannot be reasoned about from the bundle alone.
        assertThat(settings.get("contentAuditIntervalMs").asLong()).isEqualTo(900_000L);
        assertThat(settings.get("contentAuditSampleSize").asInt()).isEqualTo(50);
        assertThat(settings.get("contentAuditMaxRows").asLong()).isEqualTo(2_000_000L);
        assertThat(settings.get("contentAuditMaxTablesPerCycle").asInt()).isEqualTo(1);
        assertThat(settings.get("contentAuditTimeoutSeconds").asInt()).isEqualTo(60);
    }

    @Test
    void noSeededSecretReachesAnyEntry() throws Exception {
        DiagnosticBundleService.DiagnosticBundle bundle = service().build();
        Map<String, String> entries = entries(bundle);
        String all = String.join("\n", entries.values());

        assertThat(all).doesNotContain("pw-SECRET", "miner-SECRET", "CURSOR-SECRET",
                "SIGNATURE-SECRET", "log-SECRET", "audit-SECRET", "logpw-SECRET",
                "enc:", "private/driver");
        // The log line still arrives, redacted rather than dropped.
        assertThat(entries.get("logs/jync.log.tail.txt")).contains("<redacted>");
    }

    @Test
    void connectionsExposeNoCredentialsOrPaths() throws Exception {
        Map<String, String> entries = entries(service().build());
        JsonNode listed = mapper.readTree(entries.get("connections.json"));
        JsonNode first = listed.get(0);

        assertThat(first.has("password")).isFalse();
        assertThat(first.has("minerPassword")).isFalse();
        assertThat(first.has("customUrl")).isFalse();
        assertThat(first.has("extraParams")).isFalse();
        assertThat(first.has("customJarPath")).isFalse();
        assertThat(first.get("host").asText()).isEqualTo("db.internal");
    }

    @Test
    void manifestHashesMatchEveryOtherEntry() throws Exception {
        DiagnosticBundleService.DiagnosticBundle bundle = service().build();
        Map<String, String> entries = entries(bundle);
        JsonNode manifest = mapper.readTree(entries.get("manifest.json"));

        assertThat(manifest.get("entries").size()).isEqualTo(entries.size() - 1);
        for (JsonNode listed : manifest.get("entries")) {
            String name = listed.get("name").asText();
            if ("manifest.json".equals(name)) {
                continue;
            }
            assertThat(entries).containsKey(name);
            assertThat(listed.get("size").asLong())
                    .isEqualTo(entries.get(name).getBytes(StandardCharsets.UTF_8).length);
            assertThat(listed.get("sha256").asText())
                    .isEqualTo(sha256(entries.get(name)
                            .getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void readsTheLogTailWithTheDocumentedCaps() throws Exception {
        service().build();
        verify(systemLog).rawTail(2000, 2L * 1024 * 1024);
    }

    @Test
    void refusesToBuildAnOversizedBundle() {
        assertThatThrownBy(() -> service(100L).build())
                .isInstanceOf(DiagnosticBundleService.DiagnosticBundleTooLargeException.class)
                .hasMessage("error.diagnostics.tooLarge");
    }

    @Test
    void namesTheArchiveAndCountsItsEntries() {
        DiagnosticBundleService.DiagnosticBundle bundle = service().build();
        assertThat(bundle.filename()).matches("jync-diagnostics-\\d{8}-\\d{6}\\.zip");
        assertThat(bundle.entryCount()).isEqualTo(13);
    }
}
