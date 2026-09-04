package com.synctool.service.ai;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.synctool.dto.SyncConfig;
import com.synctool.dto.meta.ProcedureMeta;
import com.synctool.dto.meta.ViewMeta;
import com.synctool.model.DatabaseConfig;
import com.synctool.model.DatabaseType;
import com.synctool.model.Project;
import com.synctool.repository.DatabaseConfigRepository;
import com.synctool.repository.ProjectRepository;
import com.synctool.service.ProjectService;
import com.synctool.service.connection.DataSourceManager;
import com.synctool.service.converter.SqlBodyConverter;
import com.synctool.service.converter.SqlDialect;
import com.synctool.service.converter.SqlDialectFactory;
import com.synctool.service.metadata.MetadataReader;
import com.synctool.service.metadata.MetadataReaderFactory;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Drives the review workflow for objects whose conversion cannot be trusted to run unattended.
 *
 * <p>Views and routines are the only objects the mechanical converter can get wrong in a way
 * that still executes. Tables cannot: a type mapping either produces valid DDL or it does not.
 * So this service exists for exactly the two object kinds that have a {@code ddlOverrides} entry,
 * and its job is to put the source, the mechanical attempt and an optional AI draft in front of a
 * person before anything is written down.
 *
 * <p>The mechanical column is produced by the same calls {@code StructureSyncService} makes, not
 * by a simplified re-implementation. If the two ever diverge the page becomes a liar — showing a
 * reviewer SQL that sync would not actually run is worse than showing nothing.
 *
 * <p>Nothing here touches the target except {@link CandidateValidator}, which is only reached
 * through an explicit user action.
 */
@Service
@Slf4j
public class ConversionAssistService {

    /** Override key prefix for routines. Functions fold into PROCEDURE, as the sync path does. */
    public static final String KIND_PROCEDURE = "PROCEDURE";
    public static final String KIND_VIEW = "VIEW";

    private final ProjectRepository projectRepository;
    private final DatabaseConfigRepository databaseConfigRepository;
    private final ProjectService projectService;
    private final DataSourceManager dataSourceManager;
    private final MetadataReaderFactory readerFactory;
    private final SqlDialectFactory dialectFactory;
    private final SqlBodyConverter bodyConverter;
    private final AiSqlAssistant assistant;
    private final CandidateValidator validator;
    private final AiProviderService providerService;

    public ConversionAssistService(ProjectRepository projectRepository,
                                   DatabaseConfigRepository databaseConfigRepository,
                                   ProjectService projectService,
                                   DataSourceManager dataSourceManager,
                                   MetadataReaderFactory readerFactory,
                                   SqlDialectFactory dialectFactory,
                                   SqlBodyConverter bodyConverter,
                                   AiSqlAssistant assistant,
                                   CandidateValidator validator,
                                   AiProviderService providerService) {
        this.projectRepository = projectRepository;
        this.databaseConfigRepository = databaseConfigRepository;
        this.projectService = projectService;
        this.dataSourceManager = dataSourceManager;
        this.readerFactory = readerFactory;
        this.dialectFactory = dialectFactory;
        this.bodyConverter = bodyConverter;
        this.assistant = assistant;
        this.validator = validator;
        this.providerService = providerService;
    }

    /**
     * Lists the project's selected views and routines with their override state.
     *
     * <p>Reads names only. Loading every body to build a list page would mean one metadata query
     * per object against a production source, for information the list does not show.
     */
    public Overview overview(Long projectId) {
        Project project = require(projectId);
        SyncConfig config = projectService.loadConfig(project);
        DatabaseConfig source = requireSource(project);

        MetadataReader reader = readerFactory.forType(source.getType());
        Overview overview = new Overview();
        overview.sourceProduct = source.getType();
        overview.targetProduct = requireTarget(project).getType();
        overview.assistAvailable = providerService.isAssistAvailable();

        try (Connection conn = dataSourceManager.getConnection(source)) {
            String schema = schemaOf(source, reader, conn);
            overview.schema = schema;

            for (String name : reader.listProcedureNames(conn, schema)) {
                if (config.includesProcedure(name)) {
                    overview.routines.add(summarize(KIND_PROCEDURE, name, config));
                }
            }
            for (String name : reader.listViewNames(conn, schema)) {
                if (config.includesView(name)) {
                    overview.views.add(summarize(KIND_VIEW, name, config));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read source objects: " + e.getMessage(), e);
        }

        // An override for an object no longer in the source, or no longer selected, is dead
        // weight the user cannot otherwise see or remove. Surface it rather than hiding it.
        Set<String> live = new LinkedHashSet<>();
        overview.routines.forEach(s -> live.add(s.overrideKey));
        overview.views.forEach(s -> live.add(s.overrideKey));
        config.getDdlOverrides().forEach((key, value) -> {
            if (!live.contains(key) && value != null && !value.isBlank()) {
                overview.orphanedOverrides.add(key);
            }
        });
        return overview;
    }

    private Summary summarize(String kind, String name, SyncConfig config) {
        Summary summary = new Summary();
        summary.kind = kind;
        summary.name = name;
        summary.overrideKey = kind + ":" + name;
        String override = config.ddlOverride(kind, name);
        summary.hasOverride = override != null && !override.isBlank();
        summary.overrideChars = summary.hasOverride ? override.length() : 0;
        return summary;
    }

    /**
     * Loads one object: its source body, the mechanical conversion, and any saved override.
     *
     * @param kind {@link #KIND_PROCEDURE} or {@link #KIND_VIEW}
     */
    public Detail load(Long projectId, String kind, String name) {
        Project project = require(projectId);
        SyncConfig config = projectService.loadConfig(project);
        DatabaseConfig source = requireSource(project);
        DatabaseConfig target = requireTarget(project);

        MetadataReader reader = readerFactory.forType(source.getType());
        SqlDialect targetDialect = dialectFactory.forType(target.getType());

        Detail detail = new Detail();
        detail.kind = normalizeKind(kind);
        detail.name = name;
        detail.overrideKey = detail.kind + ":" + name;
        detail.sourceProduct = source.getType();
        detail.targetProduct = target.getType();
        detail.assistAvailable = providerService.isAssistAvailable();
        detail.sameFamily = source.getType() != null && target.getType() != null
                && source.getType().getFamily() == target.getType().getFamily();

        String override = config.ddlOverride(detail.kind, name);
        detail.override = override == null ? "" : override;

        try (Connection conn = dataSourceManager.getConnection(source)) {
            String sourceSchema = schemaOf(source, reader, conn);
            String targetSchema = target.getSchemaName() != null
                    && !target.getSchemaName().isBlank() ? target.getSchemaName() : sourceSchema;

            if (KIND_VIEW.equals(detail.kind)) {
                ViewMeta view = reader.readView(conn, sourceSchema, name);
                detail.routineType = KIND_VIEW;
                detail.sourceSql = nullToEmpty(view.getDefinition());
                detail.mechanicalSql = mechanicalView(view, targetSchema, source.getType(),
                        target.getType(), targetDialect);
            } else {
                ProcedureMeta proc = reader.readProcedure(conn, sourceSchema, name);
                detail.routineType = proc.getRoutineType();
                detail.sourceSql = nullToEmpty(proc.getDefinition());
                detail.mechanicalSql = mechanicalProcedure(proc, targetSchema, source.getType(),
                        target.getType(), targetDialect);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read " + name + ": " + e.getMessage(), e);
        }
        return detail;
    }

    /**
     * The statement structure sync would run for a view, absent an override.
     *
     * <p>Mirrors {@code StructureSyncService.applyView}. Only the final statement is returned:
     * the dialect may prepend a DROP, but an override replaces the whole sequence with one
     * statement, so the CREATE is what the reviewer is actually editing.
     */
    private String mechanicalView(ViewMeta view, String targetSchema, DatabaseType sourceType,
                                  DatabaseType targetType, SqlDialect dialect) {
        ViewMeta translated = new ViewMeta();
        translated.setName(view.getName());
        translated.setSchema(targetSchema);
        String body = bodyConverter.extractViewBody(view.getDefinition());
        translated.setDefinition(bodyConverter.convert(body, sourceType, targetType));
        return lastStatement(dialect.getCreateViewSql(translated, targetSchema, sourceType));
    }

    /** As {@link #mechanicalView}, mirroring {@code StructureSyncService.applyProcedure}. */
    private String mechanicalProcedure(ProcedureMeta proc, String targetSchema,
                                       DatabaseType sourceType, DatabaseType targetType,
                                       SqlDialect dialect) {
        ProcedureMeta translated = new ProcedureMeta();
        translated.setName(proc.getName());
        translated.setSchema(targetSchema);
        translated.setRoutineType(proc.getRoutineType());
        translated.setReturnType(proc.getReturnType());
        translated.setParameters(proc.getParameters());
        translated.setDefinition(bodyConverter.convert(proc.getDefinition(), sourceType, targetType));
        return lastStatement(dialect.getCreateProcedureSql(translated, targetSchema, sourceType));
    }

    private String lastStatement(List<String> statements) {
        if (statements == null || statements.isEmpty()) {
            return "";
        }
        return nullToEmpty(statements.get(statements.size() - 1));
    }

    /**
     * Asks the model for a candidate. Reads from the source only; writes nothing anywhere.
     *
     * @throws IllegalStateException when no provider is enabled
     */
    public AiSqlAssistant.Candidate draft(Long projectId, String kind, String name) {
        Detail detail = load(projectId, kind, name);
        return assistant.draft(detail.routineType, name, detail.sourceSql, detail.mechanicalSql,
                detail.sourceProduct, detail.targetProduct);
    }

    /**
     * Syntax-checks a candidate against the real target.
     *
     * <p>Takes the SQL as an argument rather than re-deriving it, because the point is to check
     * what the user has in the editor — including their own hand edits, which are the whole
     * reason this workflow exists.
     */
    public CandidateValidator.ValidationResult validate(Long projectId, String name,
                                                       String candidateSql) {
        Project project = require(projectId);
        return validator.validate(requireTarget(project), candidateSql, name);
    }

    /** Saves a reviewed candidate as the DDL override the sync path will use. */
    @Transactional
    public void saveOverride(Long projectId, String kind, String name, String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("error.override.empty");
        }
        Project project = require(projectId);
        SyncConfig config = projectService.loadConfig(project);
        String key = normalizeKind(kind) + ":" + name;
        config.getDdlOverrides().put(key, sql.strip());
        projectService.saveConfig(projectId, config);
        log.info("Saved a DDL override for {} on project '{}' ({} chars)",
                key, project.getName(), sql.strip().length());
    }

    /** Removes an override, restoring automatic conversion for that object. */
    @Transactional
    public void deleteOverride(Long projectId, String overrideKey) {
        Project project = require(projectId);
        SyncConfig config = projectService.loadConfig(project);
        if (config.getDdlOverrides().remove(overrideKey) != null) {
            projectService.saveConfig(projectId, config);
            log.info("Removed the DDL override for {} on project '{}'",
                    overrideKey, project.getName());
        }
    }

    /** Folds FUNCTION into PROCEDURE, matching the key the sync path looks up. */
    private String normalizeKind(String kind) {
        return KIND_VIEW.equalsIgnoreCase(kind) ? KIND_VIEW : KIND_PROCEDURE;
    }

    private String schemaOf(DatabaseConfig config, MetadataReader reader, Connection conn)
            throws SQLException {
        return config.getSchemaName() != null && !config.getSchemaName().isBlank()
                ? config.getSchemaName() : reader.resolveDefaultSchema(conn);
    }

    private Project require(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
    }

    private DatabaseConfig requireSource(Project project) {
        if (project.getSourceDbId() == null) {
            throw new IllegalStateException("error.project.source.required");
        }
        return databaseConfigRepository.findById(project.getSourceDbId())
                .orElseThrow(() -> new IllegalStateException("error.connection.missing"));
    }

    private DatabaseConfig requireTarget(Project project) {
        if (project.getTargetDbId() == null) {
            throw new IllegalStateException("error.project.endpoints.required");
        }
        return databaseConfigRepository.findById(project.getTargetDbId())
                .orElseThrow(() -> new IllegalStateException("error.connection.missing"));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** One row on the list page. */
    @Getter
    public static class Summary {
        private String kind;
        private String name;
        private String overrideKey;
        private boolean hasOverride;
        private int overrideChars;
    }

    /** The list page's model. */
    @Getter
    public static class Overview {
        private String schema;
        private DatabaseType sourceProduct;
        private DatabaseType targetProduct;
        private boolean assistAvailable;
        private final List<Summary> routines = new ArrayList<>();
        private final List<Summary> views = new ArrayList<>();
        /** Overrides whose object is gone or deselected; shown so they can be cleaned up. */
        private final List<String> orphanedOverrides = new ArrayList<>();
    }

    /** The review page's model. */
    @Getter
    public static class Detail {
        private String kind;
        private String name;
        private String overrideKey;
        /** PROCEDURE, FUNCTION or VIEW -- the real kind, not the folded override key. */
        private String routineType;
        private String sourceSql;
        private String mechanicalSql;
        private String override;
        private DatabaseType sourceProduct;
        private DatabaseType targetProduct;
        private boolean assistAvailable;
        /** Same dialect family: the mechanical pass is a passthrough and needs no review. */
        private boolean sameFamily;

        /** What the editor should open with: the override if saved, else the mechanical attempt. */
        public String editorSeed() {
            return override != null && !override.isBlank() ? override : mechanicalSql;
        }
    }
}
