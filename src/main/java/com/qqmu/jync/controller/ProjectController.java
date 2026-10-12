package com.qqmu.jync.controller;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.qqmu.jync.dto.Pager;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.cdc.CdcProbeResult;
import com.qqmu.jync.service.cdc.CdcRuntimeStats;

import lombok.extern.slf4j.Slf4j;

/** Server-rendered project management and configuration. */
@Controller
@RequestMapping("/projects")
@Slf4j
public class ProjectController {

    private final ProjectService projectService;
    private final DatabaseConfigService databaseConfigService;
    private final CdcOperationsService cdcOperationsService;
    private final CdcConsumerManager cdcConsumerManager;
    private final CdcPositionStore cdcPositionStore;

    public ProjectController(ProjectService projectService,
                            DatabaseConfigService databaseConfigService,
                            CdcOperationsService cdcOperationsService,
                            CdcConsumerManager cdcConsumerManager,
                            CdcPositionStore cdcPositionStore) {
        this.projectService = projectService;
        this.databaseConfigService = databaseConfigService;
        this.cdcOperationsService = cdcOperationsService;
        this.cdcConsumerManager = cdcConsumerManager;
        this.cdcPositionStore = cdcPositionStore;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "1") Integer page,
                       @RequestParam(required = false) Integer size,
                       @RequestParam(required = false) Integer spanL,
                       @RequestParam(required = false) Integer spanR,
                       Model model) {
        Pager pager = Pager.of(page, size, projectService.count(), spanL, spanR);
        List<Project> projects = projectService.findPage(pager.firstIndex(), pager.getSize());

        // One IN query for the whole page; a project has no task row until its first save
        // completes, so its key is simply absent and the template renders that as empty.
        Map<Long, SyncTask> taskLookup = projectService.findTasks(
                projects.stream().map(Project::getId).toList());

        model.addAttribute("projects", projects);
        model.addAttribute("taskLookup", taskLookup);
        model.addAttribute("pager", pager);
        return "projects";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        Project project = new Project();
        model.addAttribute("project", project);
        addDatabaseOptions(model, project);
        return "project-form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes flash) {
        return projectService.findById(id).map(project -> {
            model.addAttribute("project", project);
            addDatabaseOptions(model, project);
            return "project-form";
        }).orElseGet(() -> {
            flash.addFlashAttribute("error", "error.project.not.found");
            return "redirect:/projects";
        });
    }

    /**
     * Source/target selectors only list connections classified for that role. A project's
     * currently selected connection is still appended when its role no longer matches (legacy
     * data edge case), so the selector renders the real value instead of silently losing it.
     */
    private void addDatabaseOptions(Model model, Project project) {
        model.addAttribute("sourceDatabases",
                optionsForRole(ConnectionRole.SOURCE, project.getSourceDbId()));
        model.addAttribute("targetDatabases",
                optionsForRole(ConnectionRole.TARGET, project.getTargetDbId()));
    }

    private List<DatabaseConfig> optionsForRole(ConnectionRole role, Long selectedId) {
        List<DatabaseConfig> options = new ArrayList<>(databaseConfigService.findByRole(role));
        if (selectedId != null && options.stream().noneMatch(c -> selectedId.equals(c.getId()))) {
            databaseConfigService.findById(selectedId).ifPresent(options::add);
        }
        return options;
    }

    @PostMapping("/save")
    public String save(@ModelAttribute Project project, RedirectAttributes flash) {
        try {
            Project saved = projectService.save(project);
            flash.addFlashAttribute("message", "msg.project.saved");
            return "redirect:/projects/" + saved.getId();
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return project.getId() != null
                    ? "redirect:/projects/" + project.getId() + "/edit"
                    : "redirect:/projects/new";
        }
    }

    /**
     * Project detail: configuration, object selection, progress and recent activity.
     *
     * <p>Source object discovery needs a live connection, so a failure there is surfaced as a
     * warning on the page rather than an error page — the rest of the detail view is still
     * useful when the source is temporarily unreachable.
     */
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model, RedirectAttributes flash) {
        var maybeProject = projectService.findById(id);
        if (maybeProject.isEmpty()) {
            flash.addFlashAttribute("error", "error.project.not.found");
            return "redirect:/projects";
        }
        Project project = maybeProject.get();
        SyncConfig config = projectService.loadConfig(project);

        model.addAttribute("project", project);
        model.addAttribute("config", config);
        model.addAttribute("databases", databaseConfigService.findAll());
        SyncTask task = projectService.findTask(id).orElse(null);
        model.addAttribute("task", task);
        // Lock visibility: a held lease explains why start/sync-now/delete may refuse, and an
        // expired one tells the admin the holder is gone but the row has not been reclaimed.
        boolean lockHeld = task != null && task.getLockOwner() != null;
        model.addAttribute("lockHeld", lockHeld);
        model.addAttribute("lockExpired", lockHeld && task.getLockExpiresAt() != null
                && task.getLockExpiresAt().isBefore(java.time.Instant.now()));
        var progressList = projectService.findProgress(id);
        model.addAttribute("progressList", progressList);
        // In-flight chunked full loads (checkpoints exist only while a load is unfinished):
        // lets the page show chunk progress and the resume promise per table.
        model.addAttribute("fullLoadProgress", projectService.findFullLoadProgress(id));
        model.addAttribute("scheduled", projectService.isScheduled(id));

        // --- capture-strategy cards (C1) ---------------------------------------------------
        // The CDC card exists only for a strictly-MySQL or Oracle source; every other type
        // does not even render it (acceptance C①).
        DatabaseConfig source = project.getSourceDbId() == null ? null
                : databaseConfigService.findById(project.getSourceDbId()).orElse(null);
        boolean isMysqlSource = source != null && source.getType() == DatabaseType.MYSQL;
        boolean isOracleSource = source != null && source.getType() == DatabaseType.ORACLE;
        model.addAttribute("isMysqlSource", isMysqlSource);
        model.addAttribute("isOracleSource", isOracleSource);

        CaptureStrategy current = config.getCaptureStrategyOrDefault();
        CdcProbeResult cdcProbe = null;
        boolean cdcRecommended = false;
        CaptureStrategy recommendedStrategy = null;
        if ((isMysqlSource || isOracleSource)
                && current == CaptureStrategy.POLLING && progressList.isEmpty()) {
            // A brand-new MySQL/Oracle project that never synced: probe live and preselect
            // the CDC card when it passes. A failing probe must never block rendering —
            // same rule as B2 (a failed probe does not block saving).
            try {
                cdcProbe = cdcOperationsService.probeProject(id);
                cdcRecommended = cdcProbe != null && cdcProbe.passed();
                if (cdcRecommended) {
                    recommendedStrategy = isMysqlSource
                            ? CaptureStrategy.BINLOG_CDC : CaptureStrategy.LOGMINER_CDC;
                }
            } catch (RuntimeException e) {
                log.debug("CDC probe failed for project {}; rendering without recommendation: {}",
                        id, e.toString());
            }
        }
        model.addAttribute("cdcProbe", cdcProbe);
        model.addAttribute("cdcProbePassed", cdcProbe != null && cdcProbe.passed());
        model.addAttribute("cdcMissingChecks",
                cdcProbe == null ? List.of() : cdcProbe.failures());
        model.addAttribute("cdcRecommended", cdcRecommended);
        // Single source of truth for which card is checked — and for the form's
        // data-original-strategy, which the B8 confirm dialog compares against. The
        // recommendation wins over the stored value, so two cards can never both check.
        model.addAttribute("selectedStrategy",
                recommendedStrategy != null ? recommendedStrategy.name() : current.name());

        // --- CDC status panel (C3) ------------------------------------------------------
        // Keyed off the STORED strategy, not selectedStrategy: a merely-recommended CDC
        // card (probe passed on a pristine POLLING project) has no position and no
        // consumer yet, so the panel would only confuse. statsFor() == null means this
        // node does not hold the consumer (other node / no lease) — the template renders
        // the not-holder state plus the persisted position, which is node-independent.
        // Liveness is NEVER read from sync_task.status: a clean stop leaves it RUNNING
        // by design (T13), so holder-ship comes from statsFor and the snapshot alone.
        boolean cdcActive = current.isLogCdc();
        model.addAttribute("cdcActive", cdcActive);
        CdcPosition cdcPosition = null;
        Map<String, Object> cdcStats = null;
        if (cdcActive) {
            cdcPosition = cdcPositionStore.find(id).orElse(null);
            CdcRuntimeStats stats = cdcConsumerManager.statsFor(id);
            // snapshot() is the display contract of CdcRuntimeStats: every counter and
            // status the panel shows, already in display order (skip counters have no
            // individual getters by design).
            cdcStats = stats == null ? null : stats.snapshot();
        }
        model.addAttribute("cdcPosition", cdcPosition);
        model.addAttribute("cdcStats", cdcStats);

        try {
            ProjectService.SourceObjects objects = projectService.listSourceObjects(id);
            model.addAttribute("sourceObjects", objects);
        } catch (RuntimeException e) {
            // Keep the raw detail (SQL/connection internals) server-side; the page only ever sees
            // this bundle key, which the template already knows how to resolve.
            log.debug("Could not list source objects for project {}: {}", id, e.toString());
            model.addAttribute("sourceWarning", "msg.source.objects.unavailable");
        }
        return "project-detail";
    }

    /**
     * Lightweight CDC-panel fragment for the detail page's 5s auto-refresh (app.js
     * bindCdcRefresh). Deliberately NOT {@link #detail}: the full page model re-runs
     * {@code listSourceObjects} against the LIVE source database (and, for a pristine
     * MySQL project, the readiness probe) on every request — up to ~15s per cycle on a
     * slow source, once per open tab every 5 seconds. This handler builds only what
     * {@code project-detail :: cdc-card} reads: the persisted position plus this node's
     * runtime stats snapshot (both cheap, in-memory/metadata-store reads; the card's
     * unsupported-table badges come from the stats snapshot, never from a live listing).
     *
     * <p>Authorization is identical to the detail page: a GET under /projects/**,
     * authenticated for both roles, no state mutated. A session expiry therefore answers
     * with the login redirect exactly like the detail URL would.
     *
     * <p>When the project is gone or has been switched away from BINLOG_CDC, cdcActive
     * stays false and the fragment renders EMPTY; the browser-side refresh treats a
     * missing #cdc-card as "reload the page" and lets the full detail route (or the login
     * redirect) show the real state.
     */
    @GetMapping("/{id}/cdc-card")
    public String cdcCardFragment(@PathVariable Long id, Model model) {
        boolean cdcActive = false;
        CdcPosition cdcPosition = null;
        Map<String, Object> cdcStats = null;
        var maybeProject = projectService.findById(id);
        if (maybeProject.isPresent()) {
            Project project = maybeProject.get();
            // The card's data-refresh-url is built from the project id; keep the fragment
            // self-contained so the browser can keep polling after adopting fresh markup.
            model.addAttribute("project", project);
            cdcActive = projectService.loadConfig(project)
                    .getCaptureStrategyOrDefault().isLogCdc();
            if (cdcActive) {
                cdcPosition = cdcPositionStore.find(id).orElse(null);
                CdcRuntimeStats stats = cdcConsumerManager.statsFor(id);
                cdcStats = stats == null ? null : stats.snapshot();
            }
        }
        model.addAttribute("cdcActive", cdcActive);
        model.addAttribute("cdcPosition", cdcPosition);
        model.addAttribute("cdcStats", cdcStats);
        return "project-detail :: cdc-card";
    }

    /** Saves the object selection and sync options. */
    @PostMapping("/{id}/config")
    public String saveConfig(@PathVariable Long id,
                            @RequestParam(required = false) List<String> tables,
                            @RequestParam(required = false) List<String> views,
                            @RequestParam(required = false) List<String> procedures,
                            @RequestParam(defaultValue = "false") boolean syncStructure,
                            @RequestParam(defaultValue = "false") boolean syncData,
                            @RequestParam(defaultValue = "false") boolean syncIndexes,
                            @RequestParam(defaultValue = "false") boolean syncViews,
                            @RequestParam(defaultValue = "false") boolean syncProcedures,
                            @RequestParam(defaultValue = "false") boolean allowDrop,
                            @RequestParam(defaultValue = "false") boolean syncDeletes,
                            @RequestParam(defaultValue = "false") boolean disableTargetConstraints,
                            @RequestParam(defaultValue = "false") boolean truncateBeforeInitialLoad,
                            @RequestParam(required = false) Long pollIntervalMs,
                            @RequestParam(required = false) String cronExpression,
                            @RequestParam(required = false) Integer batchSize,
                            @RequestParam(defaultValue = "false") boolean customSchedule,
                            @RequestParam(name = "captureStrategy", required = false)
                                    String captureStrategy,
                            RedirectAttributes flash) {
        try {
            var project = projectService.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
            // 运行中的项目不允许改配置:改完要下次启动才生效,运行中改完看不到反馈。
            // 前端 fieldset 已经 disabled,这层是防直接 POST 的兜底。
            if (Boolean.TRUE.equals(project.getEnabled())) {
                flash.addFlashAttribute("error", "error.project.stop.before.config");
                return "redirect:/projects/" + id;
            }
            SyncConfig config = projectService.loadConfig(project);

            // The strategy radio is normalized up front: an unknown value is flagged and
            // dropped (the stored strategy stays), but must not fail the rest of the form.
            String strategyParam = null;
            boolean strategyRejected = false;
            if (captureStrategy != null && !captureStrategy.isBlank()) {
                try {
                    strategyParam = CaptureStrategy.valueOf(captureStrategy.trim()).name();
                } catch (IllegalArgumentException e) {
                    strategyRejected = true;
                    flash.addFlashAttribute("error", "error.project.strategy.invalid");
                }
            }

            // At least one table must be selected, per the requirement. An empty submission
            // would otherwise silently mean "all tables", which is not what unchecking implies.
            if (tables == null || tables.isEmpty()) {
                flash.addFlashAttribute("error", "error.select.at.least.one.table");
                return "redirect:/projects/" + id;
            }
            // Only when the feature is on: an empty selection must not be stored, because an
            // empty set means "include all" elsewhere (readSourceMetadata), so unchecking every
            // view/routine would silently sync all of them — the opposite of the click.
            if (syncViews && (views == null || views.isEmpty())) {
                flash.addFlashAttribute("error", "error.select.at.least.one.view");
                return "redirect:/projects/" + id;
            }
            if (syncProcedures && (procedures == null || procedures.isEmpty())) {
                flash.addFlashAttribute("error", "error.select.at.least.one.procedure");
                return "redirect:/projects/" + id;
            }

            config.setTables(new LinkedHashSet<>(tables));
            config.setViews(views == null ? new LinkedHashSet<>() : new LinkedHashSet<>(views));
            config.setProcedures(procedures == null ? new LinkedHashSet<>()
                    : new LinkedHashSet<>(procedures));
            config.setSyncStructure(syncStructure);
            config.setSyncData(syncData);
            config.setSyncIndexes(syncIndexes);
            config.setSyncViews(syncViews);
            config.setSyncProcedures(syncProcedures);
            config.setAllowDrop(allowDrop);
            config.setSyncDeletes(syncDeletes);
            config.setDisableTargetConstraints(disableTargetConstraints);
            config.setTruncateBeforeInitialLoad(truncateBeforeInitialLoad);
            // 自定义调度开关:关闭时清空三个字段,让引擎回退到全局默认值。
            // 开启时透传表单值(空字符串 → null,保持"留空=默认"语义)。
            if (customSchedule) {
                config.setPollIntervalMs(pollIntervalMs);
                config.setCronExpression(cronExpression == null || cronExpression.isBlank()
                        ? null : cronExpression.trim());
                config.setBatchSize(batchSize);
            } else {
                config.setPollIntervalMs(null);
                config.setCronExpression(null);
                config.setBatchSize(null);
            }

            CaptureStrategy before = config.getCaptureStrategyOrDefault();
            projectService.saveConfig(id, config, strategyParam);
            if (!strategyRejected) {
                // A real switch gets its own confirmation copy: the C2 side effects
                // (reload, consumer start/stop) make it more than a settings save.
                flash.addFlashAttribute("message",
                        strategyParam != null && !strategyParam.equals(before.name())
                                ? "msg.project.strategy.switched" : "msg.config.saved");
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/projects/" + id;
    }

    /** Sets an explicit cursor column for one table, overriding auto-detection. */
    @PostMapping("/{id}/cursor")
    public String saveCursor(@PathVariable Long id,
                            @RequestParam String tableName,
                            @RequestParam(required = false) String cursorColumn,
                            RedirectAttributes flash) {
        try {
            var project = projectService.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
            SyncConfig config = projectService.loadConfig(project);
            if (cursorColumn == null || cursorColumn.isBlank()) {
                config.getCursorColumns().remove(tableName);
            } else {
                config.getCursorColumns().put(tableName, cursorColumn.trim());
            }
            projectService.saveConfig(id, config);
            flash.addFlashAttribute("message", "msg.cursor.saved");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/projects/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        try {
            projectService.delete(id);
            flash.addFlashAttribute("message", "msg.project.deleted");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/projects";
    }
}
