package com.synctool.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.synctool.dto.SyncConfig;
import com.synctool.model.DatabaseConfig;
import com.synctool.model.DatabaseType;
import com.synctool.model.Project;
import com.synctool.repository.DatabaseConfigRepository;
import com.synctool.repository.ProjectRepository;
import com.synctool.service.ProjectService;
import com.synctool.service.connection.DataSourceManager;
import com.synctool.service.converter.SqlBodyConverter;
import com.synctool.service.converter.SqlDialectFactory;
import com.synctool.service.metadata.MetadataReader;
import com.synctool.service.metadata.MetadataReaderFactory;

/**
 * Override bookkeeping and orphan detection in {@link ConversionAssistService}.
 *
 * <p>Focused on the parts that change persisted state or decide what the reviewer is shown. The
 * override map is what the sync path actually reads, so a wrong key here means a reviewed and
 * approved statement silently never runs — the failure mode this whole feature exists to prevent.
 */
class ConversionAssistServiceTest {

    private ProjectRepository projectRepository;
    private DatabaseConfigRepository databaseConfigRepository;
    private ProjectService projectService;
    private DataSourceManager dataSourceManager;
    private MetadataReaderFactory readerFactory;
    private MetadataReader reader;
    private AiProviderService providerService;
    private CandidateValidator validator;
    private ConversionAssistService service;

    private Project project;
    private SyncConfig config;

    @BeforeEach
    void setUp() throws Exception {
        projectRepository = mock(ProjectRepository.class);
        databaseConfigRepository = mock(DatabaseConfigRepository.class);
        projectService = mock(ProjectService.class);
        dataSourceManager = mock(DataSourceManager.class);
        readerFactory = mock(MetadataReaderFactory.class);
        reader = mock(MetadataReader.class);
        providerService = mock(AiProviderService.class);
        validator = mock(CandidateValidator.class);

        service = new ConversionAssistService(projectRepository, databaseConfigRepository,
                projectService, dataSourceManager, readerFactory,
                mock(SqlDialectFactory.class), mock(SqlBodyConverter.class),
                mock(AiSqlAssistant.class), validator, providerService);

        project = new Project();
        project.setId(1L);
        project.setName("proj");
        project.setSourceDbId(10L);
        project.setTargetDbId(20L);

        config = new SyncConfig();

        DatabaseConfig source = db(10L, "src", DatabaseType.ORACLE, "APP");
        DatabaseConfig target = db(20L, "dst", DatabaseType.MYSQL, null);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectService.loadConfig(project)).thenReturn(config);
        when(databaseConfigRepository.findById(10L)).thenReturn(Optional.of(source));
        when(databaseConfigRepository.findById(20L)).thenReturn(Optional.of(target));
        when(readerFactory.forType(any())).thenReturn(reader);
        when(dataSourceManager.getConnection(any())).thenReturn(mock(Connection.class));
        when(reader.listProcedureNames(any(), any())).thenReturn(List.of());
        when(reader.listViewNames(any(), any())).thenReturn(List.of());
        when(providerService.isAssistAvailable()).thenReturn(true);
    }

    private DatabaseConfig db(Long id, String name, DatabaseType type, String schema) {
        DatabaseConfig c = new DatabaseConfig();
        c.setId(id);
        c.setName(name);
        c.setType(type);
        c.setSchemaName(schema);
        return c;
    }

    private SyncConfig savedConfig() {
        ArgumentCaptor<SyncConfig> captor = ArgumentCaptor.forClass(SyncConfig.class);
        verify(projectService).saveConfig(anyLong(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a saved override lands under the key the sync path reads")
    void anOverrideIsKeyedTheWayTheSyncPathLooksItUp() {
        service.saveOverride(1L, "PROCEDURE", "GET_TOTAL", "CREATE PROCEDURE x() BEGIN END");

        // StructureSyncService calls config.ddlOverride("PROCEDURE", name). Any other key here
        // would store the statement where nothing ever reads it.
        assertThat(savedConfig().ddlOverride("PROCEDURE", "GET_TOTAL"))
                .isEqualTo("CREATE PROCEDURE x() BEGIN END");
    }

    @Test
    @DisplayName("a function's override folds onto the PROCEDURE key")
    void aFunctionFoldsOntoTheProcedureKey() {
        service.saveOverride(1L, "FUNCTION", "CALC_TAX", "CREATE FUNCTION x() RETURNS INT RETURN 1");

        // The sync path does not distinguish the two; it looks up PROCEDURE for both. Storing
        // FUNCTION:CALC_TAX would be invisible to it.
        assertThat(savedConfig().getDdlOverrides()).containsOnlyKeys("PROCEDURE:CALC_TAX");
    }

    @Test
    @DisplayName("a view's override keeps its own key")
    void aViewKeepsItsOwnKey() {
        service.saveOverride(1L, "VIEW", "V_SALES", "CREATE VIEW V_SALES AS SELECT 1");

        assertThat(savedConfig().ddlOverride("VIEW", "V_SALES"))
                .isEqualTo("CREATE VIEW V_SALES AS SELECT 1");
    }

    @Test
    @DisplayName("an unknown kind is treated as a routine rather than creating a third namespace")
    void anUnknownKindFallsBackToProcedure() {
        service.saveOverride(1L, "TRIGGER", "T1", "CREATE TRIGGER whatever");

        assertThat(savedConfig().getDdlOverrides()).containsOnlyKeys("PROCEDURE:T1");
    }

    @Test
    @DisplayName("surrounding whitespace is stripped before saving")
    void whitespaceIsStripped() {
        service.saveOverride(1L, "VIEW", "V", "\n\n  CREATE VIEW V AS SELECT 1  \n\n");

        assertThat(savedConfig().ddlOverride("VIEW", "V")).isEqualTo("CREATE VIEW V AS SELECT 1");
    }

    @Test
    @DisplayName("a blank override is refused instead of disabling conversion silently")
    void aBlankOverrideIsRefused() {
        // An empty-string override is worse than none: ddlOverride() returns it, the sync path
        // treats it as the statement to run, and the object quietly stops being created.
        assertThatThrownBy(() -> service.saveOverride(1L, "VIEW", "V", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.override.empty");

        verify(projectService, never()).saveConfig(anyLong(), any());
    }

    @Test
    @DisplayName("a null override is refused")
    void aNullOverrideIsRefused() {
        assertThatThrownBy(() -> service.saveOverride(1L, "VIEW", "V", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.override.empty");
    }

    @Test
    @DisplayName("deleting an override removes exactly that key")
    void deletingRemovesThatKeyOnly() {
        config.getDdlOverrides().put("PROCEDURE:A", "one");
        config.getDdlOverrides().put("PROCEDURE:B", "two");

        service.deleteOverride(1L, "PROCEDURE:A");

        assertThat(savedConfig().getDdlOverrides()).containsOnlyKeys("PROCEDURE:B");
    }

    @Test
    @DisplayName("deleting a key that is not there does not rewrite the config")
    void deletingAMissingKeyIsANoOp() {
        service.deleteOverride(1L, "PROCEDURE:NOT_THERE");

        // Writing the project row for a no-op would bump updatedAt and make the audit trail lie.
        verify(projectService, never()).saveConfig(anyLong(), any());
    }

    @Test
    @DisplayName("an override for an object still in the source is not called orphaned")
    void aLiveOverrideIsNotOrphaned() throws Exception {
        when(reader.listProcedureNames(any(), any())).thenReturn(List.of("GET_TOTAL"));
        config.getDdlOverrides().put("PROCEDURE:GET_TOTAL", "CREATE PROCEDURE x() BEGIN END");

        ConversionAssistService.Overview overview = service.overview(1L);

        assertThat(overview.getOrphanedOverrides()).isEmpty();
        assertThat(overview.getRoutines()).hasSize(1);
        assertThat(overview.getRoutines().get(0).isHasOverride()).isTrue();
    }

    @Test
    @DisplayName("an override whose object is gone from the source is flagged as orphaned")
    void anOverrideForADeletedObjectIsOrphaned() throws Exception {
        when(reader.listProcedureNames(any(), any())).thenReturn(List.of("STILL_HERE"));
        config.getDdlOverrides().put("PROCEDURE:DELETED", "CREATE PROCEDURE x() BEGIN END");

        ConversionAssistService.Overview overview = service.overview(1L);

        // Otherwise this entry is unreachable: there is no object page to open, so no way to
        // remove it short of editing the database by hand.
        assertThat(overview.getOrphanedOverrides()).containsExactly("PROCEDURE:DELETED");
    }

    @Test
    @DisplayName("an override for a deselected object is flagged as orphaned")
    void anOverrideForADeselectedObjectIsOrphaned() throws Exception {
        when(reader.listProcedureNames(any(), any())).thenReturn(List.of("A", "B"));
        config.getProcedures().add("A");
        config.getDdlOverrides().put("PROCEDURE:B", "CREATE PROCEDURE b() BEGIN END");

        ConversionAssistService.Overview overview = service.overview(1L);

        // B exists in the source but the project no longer syncs it, so its override will never
        // run. Same dead weight, different cause.
        assertThat(overview.getRoutines()).hasSize(1);
        assertThat(overview.getOrphanedOverrides()).containsExactly("PROCEDURE:B");
    }

    @Test
    @DisplayName("a blank override value is not reported as an orphan to chase")
    void aBlankOverrideValueIsNotReportedAsOrphaned() throws Exception {
        config.getDdlOverrides().put("PROCEDURE:EMPTY", "  ");

        ConversionAssistService.Overview overview = service.overview(1L);

        assertThat(overview.getOrphanedOverrides()).isEmpty();
    }

    @Test
    @DisplayName("an empty selection means everything is listed, matching the sync path")
    void anEmptySelectionListsEverything() throws Exception {
        when(reader.listProcedureNames(any(), any())).thenReturn(List.of("A", "B"));
        when(reader.listViewNames(any(), any())).thenReturn(List.of("V1"));

        ConversionAssistService.Overview overview = service.overview(1L);

        // SyncConfig.includesProcedure treats an empty set as "all", and the review page must
        // agree or it will hide objects that sync is going to convert.
        assertThat(overview.getRoutines()).hasSize(2);
        assertThat(overview.getViews()).hasSize(1);
    }

    @Test
    @DisplayName("the configured schema wins over the connection's default")
    void theConfiguredSchemaIsUsed() throws Exception {
        service.overview(1L);

        // Asking the connection for its default schema when the project names one explicitly
        // would list a different schema's objects than sync will read.
        verify(reader, never()).resolveDefaultSchema(any());
        verify(reader).listProcedureNames(any(), org.mockito.ArgumentMatchers.eq("APP"));
    }

    @Test
    @DisplayName("with no configured schema the connection's default is resolved")
    void theDefaultSchemaIsResolvedWhenUnset() throws Exception {
        when(databaseConfigRepository.findById(10L))
                .thenReturn(Optional.of(db(10L, "src", DatabaseType.ORACLE, null)));
        when(reader.resolveDefaultSchema(any())).thenReturn("RESOLVED");

        service.overview(1L);

        verify(reader).listProcedureNames(any(), org.mockito.ArgumentMatchers.eq("RESOLVED"));
    }

    @Test
    @DisplayName("assistance availability is reported, not assumed")
    void assistAvailabilityIsReported() {
        when(providerService.isAssistAvailable()).thenReturn(false);

        assertThat(service.overview(1L).isAssistAvailable()).isFalse();
    }

    @Test
    @DisplayName("a project without a target refuses rather than half-rendering")
    void aProjectWithoutATargetIsRejected() {
        project.setTargetDbId(null);

        assertThatThrownBy(() -> service.overview(1L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a missing project is rejected before any connection is opened")
    void aMissingProjectIsRejected() throws Exception {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.overview(99L))
                .isInstanceOf(IllegalArgumentException.class);
        verify(dataSourceManager, never()).getConnection(any());
    }

    @Test
    @DisplayName("validation is aimed at the target, never the source")
    void validationRunsAgainstTheTarget() {
        when(validator.validate(any(), any(), any()))
                .thenReturn(mock(CandidateValidator.ValidationResult.class));

        service.validate(1L, "GET_TOTAL", "CREATE PROCEDURE GET_TOTAL() BEGIN END");

        ArgumentCaptor<DatabaseConfig> captor = ArgumentCaptor.forClass(DatabaseConfig.class);
        verify(validator).validate(captor.capture(), any(), any());
        // Creating a check object on the source would write to the system of record.
        assertThat(captor.getValue().getId()).isEqualTo(20L);
    }
}
