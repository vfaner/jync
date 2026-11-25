package com.qqmu.jync.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.qqmu.jync.service.ai.support.SupportDraftStore;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.config.I18nConfig;
import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.ColumnRule;
import com.qqmu.jync.dto.ColumnRuleType;
import com.qqmu.jync.dto.Pager;
import com.qqmu.jync.dto.RedisProjectionConfig;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.SyncConfigForm;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.service.CaptureStrategyParser;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.sync.ColumnCleanser;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.cdc.CdcProbeResult;
import com.qqmu.jync.service.cdc.CdcReconnectBackoff;
import com.qqmu.jync.service.cdc.CdcRuntimeStats;
import com.qqmu.jync.service.projection.redis.RedisTargetService;
import com.qqmu.jync.service.projection.redis.RedisValueFormat;
import com.qqmu.jync.service.task.FullLoadCapacity;

import lombok.extern.slf4j.Slf4j;

/** Server-rendered project management and configuration. */
@Controller
@RequestMapping("/projects")
@Slf4j
public class ProjectController {

    /** Project names cap at 128 in the service; a clone's suffix must not push past it. */
    private static final int PROJECT_NAME_MAX_LENGTH = 128;

    private final ProjectService projectService;
    private final DatabaseConfigService databaseConfigService;

    /** Optional so slice tests without the fan-out beans still load the controller. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.qqmu.jync.service.fanout.FanoutPositionStore fanoutPositionStore;
    /** Optional for the same reason: the status fragment also shows live worker state. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.qqmu.jync.service.fanout.FanoutRuntimeRegistry fanoutRuntimes;
    private final CdcOperationsService cdcOperationsService;
    private final CdcConsumerManager cdcConsumerManager;
    private final CdcPositionStore cdcPositionStore;
    private final RedisTargetService redisTargetService;
    private final I18nConfig.MessageSourceAccessorHolder messages;
    private final SyncProperties properties;
    /** Absent in MVC slices that do not import the support assistant. */
    private final ObjectProvider<SupportDraftStore> draftStore;

    public ProjectController(ProjectService projectService,
                            DatabaseConfigService databaseConfigService,
                            CdcOperationsService cdcOperationsService,
                            CdcConsumerManager cdcConsumerManager,
                            CdcPositionStore cdcPositionStore,
                            RedisTargetService redisTargetService,
                            I18nConfig.MessageSourceAccessorHolder messages,
                            SyncProperties properties,
                            ObjectProvider<SupportDraftStore> draftStore) {
        this.projectService = projectService;
        this.databaseConfigService = databaseConfigService;
        this.cdcOperationsService = cdcOperationsService;
        this.cdcConsumerManager = cdcConsumerManager;
        this.cdcPositionStore = cdcPositionStore;
        this.redisTargetService = redisTargetService;
        this.messages = messages;
        this.properties = properties;
        this.draftStore = draftStore;
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

        // Rows whose saved configuration can produce no write at all: the template disables
        // their start icon and shows the reason; the service refuses the action anyway.
        Map<Long, String> noSyncWorkMessages = new HashMap<>();
        for (Project project : projects) {
            String reason = noSyncWorkMessage(project, projectService.loadConfig(project));
            if (reason != null) {
                noSyncWorkMessages.put(project.getId(), reason);
            }
        }

        model.addAttribute("projects", projects);
        model.addAttribute("taskLookup", taskLookup);
        model.addAttribute("noSyncWorkMessages", noSyncWorkMessages);
        model.addAttribute("pager", pager);
        return "projects";
    }

    /**
     * New-project form, optionally prefilled from an existing project ({@code ?from=}).
     *
     * <p>A clone keeps the source and copies the sync settings on save, but the target is
     * left blank on purpose: writing the same tables to the same database is exactly what a
     * second project must not do, and the create form already requires a target.
     */
    @GetMapping("/new")
    public String createForm(@RequestParam(name = "from", required = false) Long from,
                             @RequestParam(name = "draft", required = false) String draft,
                             Model model) {
        Project project = new Project();
        boolean prefilled = false;
        SupportDraftStore store = draftStore == null ? null : draftStore.getIfAvailable();
        if (draft != null && store != null) {
            // The token is the AI draft's only handle; it is consumed here so a refresh
            // cannot silently re-apply a suggestion the user already decided about.
            var taken = store.take(draft, AccessGuard.current().username());
            if (taken.isPresent()) {
                SupportDraftStore.Draft d = taken.get();
                project.setName(d.project().getName());
                project.setDescription(d.project().getDescription());
                project.setSourceDbId(d.project().getSourceDbId());
                project.setTargetDbId(d.project().getTargetDbId());
                project.setTargetKind(d.project().getTargetKind());
                project.setTargetRedisId(d.project().getTargetRedisId());
                model.addAttribute("draftSuggestion", d.suggestion());
                prefilled = true;
            } else {
                model.addAttribute("draftExpired", true);
            }
        }
        Project source = !prefilled && from != null
                ? projectService.findById(from).orElse(null) : null;
        if (source != null) {
            project.setName(cloneName(source.getName()));
            project.setDescription(source.getDescription());
            project.setSourceDbId(source.getSourceDbId());
            model.addAttribute("copyFrom", source.getId());
        }
        model.addAttribute("project", project);
        model.addAttribute("fanoutSelected", java.util.List.of());
        addDatabaseOptions(model, project);
        addRedisOptions(model, project);
        return "project-form";
    }

    private void validateFanoutTargets(Project project, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        if (ids.size() > 8) {
            throw new IllegalArgumentException("error.project.fanout.tooMany");
        }
        Set<Long> seen = new java.util.HashSet<>();
        for (Long targetId : ids) {
            if (targetId == null || !seen.add(targetId)) {
                throw new IllegalArgumentException("error.project.fanout.invalid");
            }
            DatabaseConfig target = databaseConfigService.findById(targetId).orElse(null);
            if (target == null || target.getRole() != ConnectionRole.TARGET
                    || java.util.Objects.equals(targetId, project.getSourceDbId())
                    || java.util.Objects.equals(targetId, project.getTargetDbId())) {
                throw new IllegalArgumentException("error.project.fanout.invalid");
            }
        }
    }

    /**
     * Localized reason why start/sync-now would do nothing for this project, or null when
     * there is work. An explicit empty object selection blocks every strategy; turning all
     * sync options off only reduces a polling cycle to a no-op, because a CDC consumer
     * replays row events regardless of those switches.
     */
    private String noSyncWorkMessage(Project project, SyncConfig config) {
        // A cache target runs only on the log stream; a stored polling strategy on a row that
        // was switched to Redis keeps the start button enabled and makes it fail server-side.
        // Start refuses it with this key, so the page must say the same thing.
        if (project.getTargetKindOrDefault() == SyncTargetKind.REDIS
                && !config.getCaptureStrategyOrDefault().isLogCdc()) {
            return messages.get("error.project.redis.cdcOnly",
                    LocaleContextHolder.getLocale());
        }
        if (config.hasNoSyncObjects()) {
            return messages.get("error.project.no.objects", LocaleContextHolder.getLocale());
        }
        if (!config.getCaptureStrategyOrDefault().isLogCdc() && config.hasNoPollingWork()) {
            return messages.get("error.project.no.options", LocaleContextHolder.getLocale());
        }
        return null;
    }

    /** The source name plus the localized "(copy)" suffix, capped at the column length. */
    private String cloneName(String sourceName) {
        String base = sourceName == null ? "" : sourceName;
        String suffix = messages.get("project.clone.nameSuffix",
                LocaleContextHolder.getLocale());
        if (base.length() + suffix.length() > PROJECT_NAME_MAX_LENGTH) {
            base = base.substring(0, PROJECT_NAME_MAX_LENGTH - suffix.length());
        }
        return base + suffix;
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes flash) {
        return projectService.findById(id).map(project -> {
            AccessGuard.current().requireEditable(project.getOwnerUsername());
            model.addAttribute("project", project);
            // The service refuses an endpoint switch while running; the form shows the same
            // verdict up front so the user does not fill in a whole edit that cannot save.
            model.addAttribute("topologyLocked", projectService.isRunning(project));
            SyncConfig editConfig = projectService.loadConfig(project);
            model.addAttribute("fanoutSelected",
                    editConfig == null || editConfig.getFanoutTargetDbIds() == null
                            ? java.util.List.of()
                            : editConfig.getFanoutTargetDbIds());
            addDatabaseOptions(model, project);
            addRedisOptions(model, project);
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
        String scope = optionScope(project.getOwnerUsername(), AccessGuard.current());
        model.addAttribute("sourceDatabases",
                optionsForRole(ConnectionRole.SOURCE, project.getSourceDbId(), scope));
        model.addAttribute("targetDatabases",
                optionsForRole(ConnectionRole.TARGET, project.getTargetDbId(), scope));
    }

    /**
     * Redis endpoints are not {@link DatabaseConfig}s and carry no role: they are always a
     * sync target, never a source, so the form gets them as one flat list.
     */
    private void addRedisOptions(Model model, Project project) {
        String scope = optionScope(project.getOwnerUsername(), AccessGuard.current());
        model.addAttribute("redisTargets", scoped(redisTargetService.findAll(), scope,
                RedisTarget::getOwnerUsername));
    }

    private List<DatabaseConfig> optionsForRole(ConnectionRole role, Long selectedId,
                                               String scope) {
        // The two selectors ask different questions: a polling-only engine can read a source
        // but must never appear as a target.
        java.util.function.Predicate<DatabaseConfig> eligible =
                role == ConnectionRole.TARGET
                        ? DatabaseConfig::supportsSyncTarget
                        : DatabaseConfig::supportsSyncSource;
        List<DatabaseConfig> options = scoped(databaseConfigService.findByRole(role), scope,
                DatabaseConfig::getOwnerUsername).stream()
                .filter(eligible)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        // The current selection is appended even when it is no longer eligible: an existing
        // project keeps rendering the connection it was saved with instead of silently
        // losing the value and saving the change back to a different one.
        if (selectedId != null && options.stream().noneMatch(c -> selectedId.equals(c.getId()))) {
            databaseConfigService.findById(selectedId).ifPresent(options::add);
        }
        return options;
    }

    /**
     * The connection scope for a form: the owner of the resource being edited, otherwise the
     * caller. An administrator gets everything only when neither applies.
     */
    private static String optionScope(String resourceOwner, AccessGuard access) {
        if (resourceOwner != null) {
            return resourceOwner;
        }
        return access.isAdmin() ? null : access.username();
    }

    private static <T> List<T> scoped(List<T> all, String scope,
                                      java.util.function.Function<T, String> owner) {
        return scope == null ? all
                : all.stream().filter(item -> scope.equals(owner.apply(item))).toList();
    }

    @PostMapping("/save")
    public String save(@ModelAttribute Project project,
                       @RequestParam(name = "copyFrom", required = false) Long copyFrom,
                       @RequestParam(name = "fanoutTargetIds", required = false)
                       List<Long> fanoutTargetIds,
                       RedirectAttributes flash) {
        try {
            validateFanoutTargets(project, fanoutTargetIds);
            // The clone is created and its settings copied in one transaction; a failure
            // leaves neither an empty project nor a half-initialized one behind.
            Project saved = copyFrom != null
                    ? projectService.saveAsClone(project, copyFrom, AccessGuard.current())
                    : projectService.save(project, AccessGuard.current());
            projectService.configureFanout(saved.getId(), fanoutTargetIds);
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
        // The live inspections below dial the production source. The equivalent APIs
        // (/api/projects/*/preview|columns|cursor-candidates|cdc-probe|row-count|precheck)
        // are writer-only AND ownership-checked for exactly that reason; a viewer or a
        // non-owner opening the detail page must not get the same source probe (or pay
        // ~15 s of source queries per tab load). Stored state still renders for everyone.
        boolean liveSourceAllowed = AccessGuard.current().canEdit(project.getOwnerUsername());

        model.addAttribute("project", project);
        model.addAttribute("config", config);
        // A Redis target is a cache projection, not a database: the page renders the
        // projection card and the table picker, and skips every JDBC-only card. The target
        // id is resolved here rather than in the template so a stale id cannot 500 the page.
        boolean isRedisTarget = project.getTargetKindOrDefault() == SyncTargetKind.REDIS;
        model.addAttribute("isRedisTarget", isRedisTarget);
        RedisTarget redisTarget = isRedisTarget && project.getTargetRedisId() != null
                ? redisTargetService.find(project.getTargetRedisId()).orElse(null)
                : null;
        model.addAttribute("redisTarget", redisTarget);
        // Drives the disabled state of start/sync-now plus the reason shown on the page.
        model.addAttribute("noSyncWorkMessage", noSyncWorkMessage(project, config));
        SyncTask task = projectService.findTask(id).orElse(null);
        model.addAttribute("task", task);
        // The polling cycle executing on this node, if any: the next-run block uses it to
        // tell a slow round from a wedged one, the lock block to tell a live lease from the
        // row a finished round left behind.
        ProjectService.CycleView activeCycle = projectService.activeCycle(id);
        model.addAttribute("activeCycle", activeCycle);
        boolean cycleRunning = activeCycle != null && activeCycle.running();
        // Lock visibility: a held lease explains why start/sync-now/delete may refuse, and an
        // expired one tells the admin the holder is gone but the row has not been reclaimed.
        boolean lockHeld = task != null && task.getLockOwner() != null;
        boolean lockExpired = lockHeld && task.getLockExpiresAt() != null
                && task.getLockExpiresAt().isBefore(java.time.Instant.now());
        model.addAttribute("lockHeld", lockHeld);
        model.addAttribute("lockExpired", lockExpired);
        // An expired lease with nothing running here is just leftover state: the next
        // acquire accepts an expired lease and takes it over, so it blocks nothing and
        // force-unlock — which only clears that row — has nothing to offer.
        model.addAttribute("lockStale", lockExpired && !cycleRunning);
        var progressList = projectService.findProgress(id);
        // 列清洗卡按表名查该表的游标策略与累计同步行数（键大小写无关）；表名清单供筛选下拉。
        Map<String, SyncProgress> progressByTable = new HashMap<>();
        for (SyncProgress p : progressList) {
            if (p.getObjectName() != null) {
                progressByTable.put(p.getObjectName().toUpperCase(Locale.ROOT), p);
            }
        }
        model.addAttribute("progressByTable", progressByTable);
        Map<String, ProjectService.FullLoadProgress> fullLoad = filterFullLoadToSelection(
                projectService.findFullLoadProgress(id), config);
        model.addAttribute("statusFragmentData", false);
        addTableStatuses(id, config, fullLoad, model);
        Set<String> columnRuleTables = new TreeSet<>();
        for (ColumnRule r : config.getColumnRules()) {
            if (r.getTable() != null && !r.getTable().isBlank()) {
                columnRuleTables.add(r.getTable());
            }
        }
        model.addAttribute("columnRuleTables", columnRuleTables);
        List<Map<String, Object>> fanoutStatuses = buildFanoutStatuses(id, project, config);
        if (!fanoutStatuses.isEmpty()) {
            model.addAttribute("fanoutStatuses", fanoutStatuses);
        }
        // In-flight chunked full loads (checkpoints exist only while a load is unfinished):
        // lets the page show chunk progress and the resume promise per table.
        addFullLoadProgress(fullLoad, model);
        model.addAttribute("scheduled", projectService.isScheduled(id));
        // "When will it actually run?" has to be visible before the time comes: a stopped
        // project and a project whose job failed to register look identical otherwise, and
        // the fire time silently passes. Null when nothing is scheduled.
        java.time.LocalDateTime nextRunAt = projectService.nextFireTime(id);
        model.addAttribute("nextRunAt", nextRunAt);
        // A fire that is still executing keeps its trigger out of Quartz's time wheel
        // (@DisallowConcurrentExecution), so the stored next-fire time stays frozen in the
        // past while every later fire is skipped. The page has to say that, or the number
        // reads as "the scheduler is late" and the operator just waits. The grace covers a
        // normal cycle that simply has not finished by the time the page renders.
        model.addAttribute("nextRunOverdue", nextRunAt != null
                && nextRunAt.isBefore(java.time.LocalDateTime.now().minusSeconds(60)));
        model.addAttribute("serverZone", java.time.ZoneId.systemDefault().getId());

        // --- capture-strategy cards (C1) ---------------------------------------------------
        // A CDC card renders only when the backend would accept that strategy for this
        // source. That test is asked of CaptureStrategyParser.supportedFor — the same call
        // the save path and the bulk-create page make — instead of re-deriving families
        // here. The second copy drifted exactly once already: YashanDB declares the ORACLE
        // family and passes the LogMiner gate, yet has no DBMS_LOGMNR (measured, see
        // docs/dm-logminer-spike.md §7), so the bulk page hides the option while this page
        // still offered a card whose probe could only fail.
        DatabaseConfig source = project.getSourceDbId() == null ? null
                : databaseConfigService.findById(project.getSourceDbId()).orElse(null);
        List<CaptureStrategy> supportedStrategies = source == null
                ? List.of()
                : CaptureStrategyParser.supportedFor(source.getType(), source.resolvedFamily());
        boolean isMysqlSource = supportedStrategies.contains(CaptureStrategy.BINLOG_CDC);
        boolean isOracleSource = supportedStrategies.contains(CaptureStrategy.LOGMINER_CDC);
        boolean isPostgresSource = supportedStrategies.contains(CaptureStrategy.PG_LOGICAL_CDC);
        boolean isSqlServerSource = supportedStrategies.contains(CaptureStrategy.SQLSERVER_CDC);
        boolean isYashanSource = supportedStrategies.contains(CaptureStrategy.YSTREAM_CDC);
        model.addAttribute("isMysqlSource", isMysqlSource);
        model.addAttribute("isOracleSource", isOracleSource);
        model.addAttribute("isPostgresSource", isPostgresSource);
        model.addAttribute("isSqlServerSource", isSqlServerSource);
        model.addAttribute("isYashanSource", isYashanSource);

        // The worker count is clamped by the connection pools (FullLoadCapacity), so the card
        // shows what the saved value will actually run as: "受连接池上限约束" alone leaves a
        // saved 8 looking effective when the pool seats 4.
        DatabaseConfig target = project.getTargetDbId() == null ? null
                : databaseConfigService.findById(project.getTargetDbId()).orElse(null);
        model.addAttribute("fullLoadPoolSize", Math.max(2, properties.getMaxPoolSize()));
        model.addAttribute("fullLoadParallelismCap",
                FullLoadCapacity.effectiveCap(properties, source, target));

        CaptureStrategy current = config.getCaptureStrategyOrDefault();
        CdcProbeResult cdcProbe = null;
        boolean cdcRecommended = false;
        CaptureStrategy recommendedStrategy = null;
        // Recommendation only pre-selects CDC for a project the user has NEVER explicitly
        // configured (raw field absent). Gating on current==POLLING is wrong: getCaptureStrategyOrDefault()
        // maps both "field absent" and "user saved POLLING" to POLLING, so a saved polling project would
        // be re-overridden back to CDC on every render (it could never stay polling).
        if ((isYashanSource || isMysqlSource || isOracleSource || isPostgresSource
                || isSqlServerSource)
                && config.getCaptureStrategy() == null && progressList.isEmpty()) {
            // A brand-new MySQL/Oracle/PG/SQLServer project that never synced: probe live
            // and preselect the CDC card when it passes. A failing probe must never block
            // rendering — same rule as B2 (a failed probe does not block saving).
            try {
                cdcProbe = liveSourceAllowed ? cdcOperationsService.probeProject(id) : null;
                cdcRecommended = cdcProbe != null && cdcProbe.passed();
                if (cdcRecommended) {
                    if (isYashanSource) {
                        // The only log engine a YashanDB instance has; isOracleSource is false
                        // here because supportedFor hides LogMiner for this product.
                        recommendedStrategy = CaptureStrategy.YSTREAM_CDC;
                    } else if (isMysqlSource) {
                        recommendedStrategy = CaptureStrategy.BINLOG_CDC;
                    } else if (isOracleSource) {
                        recommendedStrategy = CaptureStrategy.LOGMINER_CDC;
                    } else if (isPostgresSource) {
                        recommendedStrategy = CaptureStrategy.PG_LOGICAL_CDC;
                    } else if (isSqlServerSource) {
                        recommendedStrategy = CaptureStrategy.SQLSERVER_CDC;
                    }
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
        CaptureStrategy selected = recommendedStrategy != null ? recommendedStrategy : current;
        model.addAttribute("selectedStrategy", selected.name());
        // The object/option switches below are polling-only — except the index switch, which
        // the baseline honours exactly as saved and therefore stays visible. Keyed off the
        // SELECTION (including an unsaved recommendation) so the hidden ones disappear the
        // moment a CDC card is picked; app.js keeps it in sync after.
        model.addAttribute("selectedCdc", selected.isLogCdc());
        // The persisted value, deliberately distinct from selectedStrategy: a CDC card
        // pre-selected by the probe recommendation is NOT saved, and the form's baseline
        // (dirty detection + the CDC-switch confirmation) must compare against what the
        // Start button will actually run, or Start silently launches polling while the
        // CDC card looks checked.
        model.addAttribute("storedStrategy", current.name());

        // An explicit FULL_COMPARE choice skips the resolver's row cap, and even a table
        // within the cap can be heavy for a short poll interval. Say so before the first
        // cycle instead of letting the source load explain it. A snapshot read failure must
        // not take the page down — the banner is advisory.
        ProjectService.FullCompareLoadReport fullCompareLoad = null;
        if (!isRedisTarget && liveSourceAllowed) {
            try {
                fullCompareLoad = projectService.inspectFullCompareLoad(id, config);
            } catch (RuntimeException e) {
                log.debug("Could not estimate FULL_COMPARE load for project {}: {}",
                        id, e.toString());
            }
        }
        model.addAttribute("fullCompareLoad", fullCompareLoad);

        // Oracle-style '||' concatenation is logical OR on MySQL-family targets: the view or
        // routine is created and only returns the wrong rows afterwards. Name the objects
        // here, where an override can still be supplied before the first run. Advisory — a
        // source that cannot answer loses the warning, not the page.
        if (!isRedisTarget && liveSourceAllowed) {
            try {
                model.addAttribute("stringConcatWarnings",
                        projectService.findStringConcatenationWarnings(id));
            } catch (RuntimeException e) {
                log.debug("Could not inspect '||' usage for project {}: {}", id, e.toString());
            }
        }

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
        // Cursor priority is meaningful only for an incremental polling cycle. Full compare
        // re-reads every row and never consults a cursor column, so hiding the editor avoids
        // a setting that would silently do nothing.
        model.addAttribute("cursorPriorityApplicable",
                !cdcActive && current != CaptureStrategy.FULL_COMPARE);

        // The polling path reads by cursor column only: without an index leading with that
        // column every incremental read scans the whole source table, which is the read that
        // reaches the statement timeout on a large table. The sync log says so once; this is
        // where the operator who can add the index actually sees it. Advisory — a source
        // that cannot answer loses the hint, not the page.
        if (!cdcActive && liveSourceAllowed) {
            try {
                model.addAttribute("unindexedCursorTables",
                        projectService.findUnindexedCursorTables(id, progressList));
            } catch (RuntimeException e) {
                log.debug("Could not inspect cursor-column indexes for project {}: {}",
                        id, e.toString());
            }
        }
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
        model.addAttribute("cdcBaselinePending", cdcActive
                && (cdcPosition == null || !Boolean.TRUE.equals(cdcPosition.isBaselineDone())));

        if (!liveSourceAllowed) {
            // Not a fault: this account may not edit the project, so the page never dials
            // the live source. Say that, not "check the connection" — the template's
            // sourceObjects consumers are all null-safe.
            model.addAttribute("sourceWarning", "msg.source.objects.restricted");
            return "project-detail";
        }
        try {
            ProjectService.SourceObjects objects = projectService.listSourceObjects(id);
            model.addAttribute("sourceObjects", objects);
        } catch (RuntimeException e) {
            // Keep the raw detail (SQL/connection internals) server-side; the page only ever sees
            // this bundle key, which the template already knows how to resolve.
            log.warn("Could not list source objects for project {}: {}", id, e.getMessage(), e);
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
     * {@code project-detail/cdc-status :: cdc-card} reads: the persisted position plus this node's
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
    @GetMapping("/{id}/full-load-card")
    public String fullLoadCardFragment(@PathVariable Long id, Model model) {
        Project project = projectService.findById(id).orElse(null);
        if (project == null) {
            // The browser treats a missing #full-load-card as "reload"; rendering the normal
            // fragment here would dereference project.id and turn a deleted project into a
            // 500 that the poller keeps retrying.
            return "project-detail/full-load-progress :: missing";
        }
        SyncConfig config = projectService.loadConfig(project);
        Map<String, ProjectService.FullLoadProgress> fullLoad = filterFullLoadToSelection(
                projectService.findFullLoadProgress(id), config);
        model.addAttribute("project", project);
        model.addAttribute("statusFragmentData", true);
        addTableStatuses(id, config, fullLoad, model);
        addFullLoadProgress(fullLoad, model);
        return "project-detail/full-load-progress :: data";
    }

    /**
     * Selected-table statuses (including tables that have not started yet) plus the four
     * counters the summary badge needs. Shared by the detail page and the 2-second
     * full-load fragment so both always describe the same table list.
     */
    private void addTableStatuses(Long id, SyncConfig config,
                                  Map<String, ProjectService.FullLoadProgress> fullLoad,
                                  Model model) {
        List<ProjectService.TableSyncStatus> tableStatuses =
                projectService.findTableSyncStatuses(id, config, fullLoad);
        if (tableStatuses == null) {
            return;
        }
        model.addAttribute("tableStatuses", tableStatuses);
        model.addAttribute("tableStatusCompleted", tableStatuses.stream()
                .filter(status -> status.state() == ProjectService.SyncState.COMPLETED).count());
        model.addAttribute("tableStatusInProgress", tableStatuses.stream()
                .filter(status -> status.state() == ProjectService.SyncState.IN_PROGRESS).count());
        model.addAttribute("tableStatusIncomplete", tableStatuses.stream()
                .filter(status -> status.state() == ProjectService.SyncState.INCOMPLETE).count());
        model.addAttribute("tableStatusNotStarted", tableStatuses.stream()
                .filter(status -> status.state() == ProjectService.SyncState.NOT_STARTED).count());
        model.addAttribute("tableStatusIncRunning", tableStatuses.stream()
                .filter(status -> status.incremental()
                        == ProjectService.IncrementalState.RUNNING).count());
        model.addAttribute("tableStatusIncPaused", tableStatuses.stream()
                .filter(status -> status.incremental()
                        == ProjectService.IncrementalState.PAUSED).count());
        model.addAttribute("tableStatusIncError", tableStatuses.stream()
                .filter(status -> status.incremental()
                        == ProjectService.IncrementalState.ERROR).count());
        model.addAttribute("tableStatusIncNotStarted", tableStatuses.stream()
                .filter(status -> status.incremental()
                        == ProjectService.IncrementalState.NOT_STARTED).count());
        model.addAttribute("tableStatusIncLimited", tableStatuses.stream()
                .filter(status -> status.incremental()
                        == ProjectService.IncrementalState.LIMITED).count());
        model.addAttribute("tableStatusIncWaiting", tableStatuses.stream()
                .filter(status -> status.incremental()
                        == ProjectService.IncrementalState.WAITING).count());
        // Before the first chunk checkpoint there is no full-load row to point at; the
        // progress row created when the table's work started is the best current-table hint.
        model.addAttribute("tableStatusInProgressName", tableStatuses.stream()
                .filter(status -> status.state() == ProjectService.SyncState.IN_PROGRESS)
                .map(ProjectService.TableSyncStatus::name)
                .findFirst().orElse(null));
    }

    /**
     * Checkpoint-derived detail for the progress summary: rows copied, last update, stale
     * flag and the current table. Table counts come from {@link #addTableStatuses}, which
     * still knows about completed tables after the last checkpoint disappears.
     */
    private void addFullLoadProgress(Map<String, ProjectService.FullLoadProgress> fullLoadProgress,
                                     Model model) {
        // The panel says "当前表 <name> 已写入行数 <rows>": both values must describe the SAME
        // table. Summing every unfinished load here made a project with a second table's
        // leftover checkpoints show a total next to one table's name, disagreeing with that
        // table's own row in the list.
        Map.Entry<String, ProjectService.FullLoadProgress> current =
                fullLoadProgress.entrySet().stream()
                        .filter(entry -> entry.getValue().getLastUpdatedAt() != null)
                        .max(java.util.Comparator.comparing(
                                entry -> entry.getValue().getLastUpdatedAt()))
                        .orElse(null);
        java.time.Instant fullLoadUpdatedAt =
                current == null ? null : current.getValue().getLastUpdatedAt();
        model.addAttribute("fullLoadTableCount", fullLoadProgress.size());
        model.addAttribute("fullLoadRows",
                current == null ? 0L : current.getValue().getRowsCopied());
        model.addAttribute("fullLoadUpdatedAt", fullLoadUpdatedAt);
        model.addAttribute("fullLoadStale", fullLoadUpdatedAt != null
                && fullLoadUpdatedAt.isBefore(java.time.Instant.now().minusSeconds(600)));
        model.addAttribute("fullLoadCurrentTable", current == null ? null : current.getKey());
    }

    /**
     * Checkpoints of tables the project no longer selects are historical leftovers: they must
     * not inflate the full-load summary or keep the 「全量进度」 column visible. A legacy
     * project without an explicit selection ("all") has no list to filter by and keeps every
     * entry; an explicitly empty selection keeps none.
     */
    private static Map<String, ProjectService.FullLoadProgress> filterFullLoadToSelection(
            Map<String, ProjectService.FullLoadProgress> fullLoad, SyncConfig config) {
        if (config == null || config.getTables() == null || config.getTables().isEmpty()) {
            return config != null && config.isObjectSelectionExplicit()
                    ? Map.of() : fullLoad;
        }
        Set<String> selected = new LinkedHashSet<>();
        for (String table : config.getTables()) {
            if (table != null) {
                selected.add(table.toUpperCase(Locale.ROOT));
            }
        }
        Map<String, ProjectService.FullLoadProgress> filtered = new LinkedHashMap<>();
        fullLoad.forEach((table, progress) -> {
            if (table != null && selected.contains(table.toUpperCase(Locale.ROOT))) {
                filtered.put(table, progress);
            }
        });
        return filtered;
    }

    /** Polled by the detail page so per-target positions and spool backlog stay live. */
    @GetMapping("/{id}/fanout-status")
    public String fanoutStatusFragment(@PathVariable Long id, Model model) {
        Project project = projectService.findById(id).orElse(null);
        if (project == null) {
            return "project-detail/fanout-status :: missing";
        }
        SyncConfig config = projectService.loadConfig(project);
        model.addAttribute("fanoutStatuses", buildFanoutStatuses(id, project, config));
        return "project-detail/fanout-status :: list";
    }

    private List<Map<String, Object>> buildFanoutStatuses(Long projectId, Project project,
                                                          SyncConfig config) {
        if (fanoutPositionStore == null || config == null
                || config.getFanoutTargetDbIds() == null
                || config.getFanoutTargetDbIds().isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        if (project.getTargetDbId() != null) {
            ids.add(project.getTargetDbId());
        }
        ids.addAll(config.getFanoutTargetDbIds());
        List<Map<String, Object>> statuses = new ArrayList<>();
        for (Long targetId : ids) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("targetId", targetId);
            row.put("name", databaseConfigService.findById(targetId)
                    .map(DatabaseConfig::getName).orElse("#" + targetId));
            row.put("ageSeconds", null);
            row.put("lsn", null);
            row.put("scn", null);
            fanoutPositionStore.find(projectId, targetId).ifPresentOrElse(position -> {
                row.put("file", position.getBinlogFile());
                row.put("pos", position.getBinlogPos());
                // Non-binlog engines resume by LSN/SCN; show the token they actually use
                // instead of pretending every fan-out source is MySQL.
                row.put("lsn", position.getLsn());
                row.put("scn", position.getScn());
                if (position.getUpdatedAt() != null) {
                    row.put("ageSeconds", Math.max(0L,
                            java.time.Duration.between(position.getUpdatedAt(),
                                    java.time.Instant.now()).getSeconds()));
                }
            }, () -> row.put("file", null));
            java.nio.file.Path spool = java.nio.file.Path.of("data", "fanout",
                    String.valueOf(projectId), targetId + ".log");
            try {
                row.put("spoolBytes", java.nio.file.Files.exists(spool)
                        ? java.nio.file.Files.size(spool) : 0L);
            } catch (java.io.IOException e) {
                row.put("spoolBytes", 0L);
            }
            statuses.add(row);
        }
        attachWorkerStates(projectId, statuses);
        return statuses;
    }

    /**
     * Adds the live worker state of this node (alive / last error / applied transactions).
     * On another node the registry has no runtime and the DB position is all the page can
     * show — the same split the single-target CDC card already has.
     */
    private void attachWorkerStates(Long projectId, List<Map<String, Object>> statuses) {
        // The keys must exist even when this node runs no runtime: Thymeleaf's map lookup
        // throws on a missing key, which would 500 the whole status fragment.
        for (Map<String, Object> row : statuses) {
            row.put("workerAlive", null);
            row.put("workerError", null);
            row.put("appliedTransactions", null);
        }
        com.qqmu.jync.service.fanout.FanoutRuntime runtime =
                fanoutRuntimes == null ? null : fanoutRuntimes.get(projectId);
        if (runtime == null) {
            return;
        }
        Map<Long, com.qqmu.jync.service.fanout.FanoutRuntime.TargetState> live =
                new java.util.HashMap<>();
        try {
            for (com.qqmu.jync.service.fanout.FanoutRuntime.TargetState state
                    : runtime.workerStates()) {
                live.put(state.targetDbId(), state);
            }
        } catch (RuntimeException e) {
            // A runtime closing between the registry lookup and this read must not turn the
            // status fragment into a 500; the DB-backed columns still render.
            return;
        }
        for (Map<String, Object> row : statuses) {
            com.qqmu.jync.service.fanout.FanoutRuntime.TargetState state =
                    live.get((Long) row.get("targetId"));
            if (state != null) {
                row.put("workerAlive", state.alive());
                row.put("workerError", state.lastError());
                row.put("appliedTransactions", state.appliedTransactions());
            }
        }
    }

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
        model.addAttribute("cdcBaselinePending", cdcActive
                && (cdcPosition == null || !Boolean.TRUE.equals(cdcPosition.isBaselineDone())));
        return "project-detail/cdc-status :: cdc-card";
    }

    /** Header value that routes a save to the JSON answer instead of the PRG redirect. */
    static final String AJAX_SAVE_HEADER = "X-Jync-Ajax=1";

    /**
     * Saves the object selection and sync options (plain form POST).
     *
     * <p>The detail page has a background twin, {@link #saveConfigAjax}; both run
     * {@link #applyConfig}, so the two entry points cannot drift apart field by field.
     * This variant keeps the PRG redirect and flash banner for clients without JavaScript
     * and for direct posts.
     */
    @PostMapping("/{id}/config")
    public String saveConfig(@PathVariable Long id, @ModelAttribute SyncConfigForm form,
                            RedirectAttributes flash) {
        ConfigSaveOutcome outcome = applyConfig(id, form);
        if (outcome.error() != null) {
            flash.addFlashAttribute("error", outcome.error());
        }
        if (outcome.message() != null) {
            flash.addFlashAttribute("message", outcome.message());
        }
        return "redirect:/projects/" + id;
    }

    /**
     * Background save for the detail page: JSON instead of a redirect.
     *
     * <p>A window reload after saving re-runs {@code listSourceObjects} against the live
     * source (thousands of tables on a large schema), discards the scroll position, and
     * re-renders a page the user did not leave. The answer carries what the page cannot
     * infer locally: the localized result, whether start/sync-now have work
     * ({@code noSyncWork}), and whether the page must re-render anyway because the capture
     * strategy moved the CDC panel on or off it ({@code reload}).
     */
    @PostMapping(value = "/{id}/config", headers = AJAX_SAVE_HEADER)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveConfigAjax(
            @PathVariable Long id, @ModelAttribute SyncConfigForm form) {
        ConfigSaveOutcome outcome;
        try {
            outcome = applyConfig(id, form);
        } catch (org.springframework.security.access.AccessDeniedException e) {
            // The AJAX caller cannot read the HTML redirect the security filter would send;
            // answer in the shape it parses so the page shows "permission denied" instead
            // of a generic network error.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "ok", false, "message", localize("error.forbidden"), "reload", false));
        } catch (RuntimeException e) {
            log.warn("Background config save for project {} failed: {}", id, e.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "ok", false, "message", localize("error.unexpected"), "reload", false));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", outcome.error() == null);
        String key = outcome.error() != null ? outcome.error() : outcome.message();
        if (key != null) {
            body.put("message", localize(key));
        }
        body.put("reload", outcome.reload());
        if (outcome.noSyncWork() != null) {
            body.put("noSyncWork", outcome.noSyncWork());
        }
        return ResponseEntity
                .status(outcome.error() == null ? HttpStatus.OK : HttpStatus.BAD_REQUEST)
                .body(body);
    }

    /** What one save produced, before it becomes a redirect or a JSON body. */
    private record ConfigSaveOutcome(String error, String message, boolean reload,
                                     String noSyncWork) {
        static ConfigSaveOutcome error(String key) {
            return new ConfigSaveOutcome(key, null, false, null);
        }
    }

    /**
     * Applies a submitted configuration. Shared by the form and AJAX mappings, it returns
     * i18n keys rather than writing flash attributes so neither caller has to guess.
     */
    private ConfigSaveOutcome applyConfig(Long id, SyncConfigForm form) {
        try {
            var project = projectService.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
            AccessGuard.current().requireEditable(project.getOwnerUsername());
            // 运行中的项目不允许改配置:改完要下次启动才生效,运行中改完看不到反馈。
            // 前端 fieldset 已经 disabled,这层是防直接 POST 的兜底。
            if (Boolean.TRUE.equals(project.getEnabled())) {
                return ConfigSaveOutcome.error("error.project.stop.before.config");
            }
            SyncConfig config = projectService.loadConfig(project);

            // The strategy radio is normalized up front: an unknown value is flagged and
            // dropped (the stored strategy stays), but must not fail the rest of the form.
            String captureStrategy = form.getCaptureStrategy();
            String strategyParam = null;
            String strategyError = null;
            if (captureStrategy != null && !captureStrategy.isBlank()) {
                try {
                    strategyParam = CaptureStrategy.valueOf(captureStrategy.trim()).name();
                } catch (IllegalArgumentException e) {
                    strategyError = "error.project.strategy.invalid";
                }
            }

            List<String> tables = form.getTables();
            List<String> views = form.getViews();
            List<String> procedures = form.getProcedures();
            // Object selection. An empty submission is ambiguous, so the form tells us which
            // reading it is:
            //  - flag present: the pickers were rendered and this is the user's full choice.
            //    An empty list means "sync none of this kind" — a legitimate no-op, not an error.
            //  - flag absent: the source listing failed, or an old/direct client posted. An
            //    empty payload then carries NO selection information, so the stored sets are
            //    kept instead of being silently wiped to "sync nothing".
            if (form.isObjectSelectionExplicit()) {
                config.setTables(tables == null ? new LinkedHashSet<>() : new LinkedHashSet<>(tables));
                config.setViews(views == null ? new LinkedHashSet<>() : new LinkedHashSet<>(views));
                config.setProcedures(procedures == null ? new LinkedHashSet<>()
                        : new LinkedHashSet<>(procedures));
                config.setObjectSelectionExplicit(true);
            } else if ((tables != null && !tables.isEmpty())
                    || (views != null && !views.isEmpty())
                    || (procedures != null && !procedures.isEmpty())) {
                // Legacy payload: names present, flag missing. Store it with the old reading.
                config.setTables(tables == null ? new LinkedHashSet<>() : new LinkedHashSet<>(tables));
                config.setViews(views == null ? new LinkedHashSet<>() : new LinkedHashSet<>(views));
                config.setProcedures(procedures == null ? new LinkedHashSet<>()
                        : new LinkedHashSet<>(procedures));
            }
            // 表名映射:只有面板真的渲染了这些行才按提交内容整体替换。字段缺席有两种
            // 来源——源库列举失败/旧客户端提交,此时不能把已存映射清空;面板渲染了但
            // 所有行都没勾选,此时 mappingSource 同样缺席(未勾选行不提交映射),而
            // 提交的映射仍是完整事实,已存映射必须一起清掉。
            List<String> mappingSource = form.getMappingSource();
            if (mappingSource != null || form.isObjectSelectionExplicit()) {
                config.setTableNameMapping(readTableNameMapping(
                        mappingSource == null ? List.of() : mappingSource,
                        form.getMappingTarget() == null ? List.of() : form.getMappingTarget(),
                        config));
            }
            // CDC 空闲轮询间隔:留空=引擎默认;给值就不得低于安全下限,
            // 否则等于对源库做紧密挖掘会话循环(读侧还有一层兜底 clamp)。
            Long cdcIdlePollMs = form.getCdcIdlePollMs();
            if (cdcIdlePollMs != null && cdcIdlePollMs < CdcReconnectBackoff.MIN_IDLE_POLL_MS) {
                return ConfigSaveOutcome.error("error.project.cdc.idle.poll.too.small");
            }

            // The option, schedule and performance cards are not rendered for a cache target
            // (see project-detail.html), so their fields are absent from the submission. The
            // form defaults would overwrite what a converted project saved while it still had
            // a database target — the baseline load of the projection still uses batch size
            // and worker count. Write only what the Redis page actually renders.
            if (project.getTargetKindOrDefault() != SyncTargetKind.REDIS) {
                config.setSyncStructure(form.isSyncStructure());
                config.setSyncData(form.isSyncData());
                config.setSyncIndexes(form.isSyncIndexes());
                config.setSyncViews(form.isSyncViews());
                config.setSyncProcedures(form.isSyncProcedures());
                config.setAllowDrop(form.isAllowDrop());
                config.setSyncDeletes(form.isSyncDeletes());
                config.setDisableTargetConstraints(form.isDisableTargetConstraints());
                config.setTruncateBeforeInitialLoad(form.isTruncateBeforeInitialLoad());
                // Enabling takes a fresh snapshot on the next cycle; without clearing it, a
                // table created while the switch was off would be treated as "new" the
                // moment it is turned back on.
                boolean autoSync = form.isAutoSyncNewTables();
                if (autoSync && !config.isAutoSyncNewTables()) {
                    config.setAutoSyncKnownTables(null);
                }
                config.setAutoSyncNewTables(autoSync);
                List<Long> fanout = form.getFanoutTargetIds();
                if (fanout != null) {
                    List<Long> clean = new ArrayList<>();
                    for (Long targetId : fanout) {
                        if (targetId != null && !clean.contains(targetId)) {
                            clean.add(targetId);
                        }
                    }
                    if (clean.size() > 8) {
                        return ConfigSaveOutcome.error("error.project.fanout.tooMany");
                    }
                    if (!clean.isEmpty()) {
                        CaptureStrategy effective = strategyParam != null
                                ? CaptureStrategy.valueOf(strategyParam)
                                : config.getCaptureStrategyOrDefault();
                        if (!effective.isLogCdc()
                                || project.getTargetKindOrDefault()
                                        != SyncTargetKind.JDBC) {
                            return ConfigSaveOutcome.error(
                                    "error.project.fanout.requires.binlog");
                        }
                        for (Long targetId : clean) {
                            DatabaseConfig target = databaseConfigService.findById(targetId)
                                    .orElse(null);
                            if (target == null || target.getRole() != ConnectionRole.TARGET
                                    || java.util.Objects.equals(targetId, project.getSourceDbId())
                                    || java.util.Objects.equals(targetId, project.getTargetDbId())) {
                                return ConfigSaveOutcome.error("error.project.fanout.invalid");
                            }
                        }
                    }
                    config.setFanoutTargetDbIds(clean);
                }
                // Per-project transport choice. A change moves where UNDELIVERED events
                // live: the capture anchor has already advanced past events sitting in the
                // old place, so the safe move is the same as a target-list change — refuse
                // while running, and drop the anchor so the next start re-baselines every
                // target instead of silently skipping that window.
                String requestedTransport = form.getFanoutTransport();
                if (requestedTransport != null) {
                    String cleanTransport = requestedTransport.trim();
                    if (cleanTransport.isEmpty()) {
                        cleanTransport = null;
                    } else if (!"SPOOL".equalsIgnoreCase(cleanTransport)
                            && !"REDIS".equalsIgnoreCase(cleanTransport)) {
                        return ConfigSaveOutcome.error(
                                "error.project.fanout.transport.invalid");
                    } else {
                        cleanTransport = cleanTransport.toUpperCase(java.util.Locale.ROOT);
                    }
                    String requestedUri = form.getFanoutRedisUri() == null
                            ? null : form.getFanoutRedisUri().trim();
                    if (requestedUri != null && requestedUri.isEmpty()) {
                        requestedUri = null;
                    }
                    boolean transportChanged = !java.util.Objects.equals(
                            config.getFanoutTransport(), cleanTransport)
                            || !java.util.Objects.equals(
                                    config.getFanoutRedisUri(), requestedUri);
                    if (transportChanged && config.getFanoutTargetDbIds() != null
                            && !config.getFanoutTargetDbIds().isEmpty()) {
                        if (projectService.isRunning(project)) {
                            return ConfigSaveOutcome.error(
                                    "error.project.stop.before.config");
                        }
                        cdcPositionStore.delete(project.getId());
                    }
                    config.setFanoutTransport(cleanTransport);
                    config.setFanoutRedisUri(requestedUri);
                }
                // A strategy switch away from binlog leaves no fan-out to run; stale extra
                // targets would otherwise sit in the config and surprise a later switch back.
                CaptureStrategy effectiveForFanout = strategyParam != null
                        ? CaptureStrategy.valueOf(strategyParam)
                        : config.getCaptureStrategyOrDefault();
                if (!effectiveForFanout.isLogCdc()
                        && config.getFanoutTargetDbIds() != null) {
                    config.setFanoutTargetDbIds(new ArrayList<>());
                }
                // 自定义调度开关:关闭时清空调度的两个字段,让引擎回退到全局默认值。
                // 开启时透传表单值(空字符串 → null,保持"留空=默认"语义)。
                if (form.isCustomSchedule()) {
                    config.setPollIntervalMs(form.getPollIntervalMs());
                    String cron = form.getCronExpression();
                    config.setCronExpression(cron == null || cron.isBlank() ? null : cron.trim());
                } else {
                    config.setPollIntervalMs(null);
                    config.setCronExpression(null);
                }
                // 加载性能与调度无关(CDC 项目的基线全量同样吃这两个值),因此不受上面开关影响。
                // 留空 → null = 用全局默认;越界在服务端收敛到边界并记账,绝不静默接受。
                config.setBatchSize(clampFullLoadBatchSize(form.getBatchSize()));
                config.setFullLoadParallelism(
                        clampFullLoadParallelism(form.getFullLoadParallelism()));
            }
            // 与自定义调度无关:三个轮询式日志 CDC 引擎(LogMiner/PG/SQLServer)
            // 的空闲休眠;留空回退引擎默认。binlog 引擎不轮询,忽略此值。
            config.setCdcIdlePollMs(cdcIdlePollMs);

            // The projection card is rendered only for a Redis target; the fields it submits
            // are meaningless (and must not be stored) for a database project.
            if (project.getTargetKindOrDefault() == SyncTargetKind.REDIS) {
                String invalidProjection = applyRedisProjection(form, config, project.getId());
                if (invalidProjection != null) {
                    return ConfigSaveOutcome.error(invalidProjection);
                }
            }

            CaptureStrategy before = config.getCaptureStrategyOrDefault();
            projectService.saveConfig(id, config, strategyParam, AccessGuard.current());
            // A real switch gets its own confirmation copy: the C2 side effects (reload,
            // consumer start/stop) make it more than a settings save. The page must also
            // re-render: the CDC panel and the full-compare banner follow the saved
            // strategy, not the radio the user just clicked.
            boolean switched = strategyParam != null && !strategyParam.equals(before.name());
            String message = strategyError != null ? null
                    : (switched ? "msg.project.strategy.switched" : "msg.config.saved");
            return new ConfigSaveOutcome(strategyError, message, switched,
                    noSyncWorkMessage(project, config));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ConfigSaveOutcome.error(e.getMessage());
        }
    }

    /**
     * Copies the Redis projection inputs onto the config.
     *
     * <p>Bounds (prefix shape, TTL, value size, command count) are enforced once in
     * {@code RedisProjectionPolicy}; this method only maps the flat form fields. A value the
     * enum cannot parse is refused here, because the policy has no vocabulary for it.
     *
     * @return the i18n key of the first invalid value, or null when the projection is usable
     */
    private static String applyRedisProjection(SyncConfigForm form, SyncConfig config,
                                               Long projectId) {
        RedisProjectionConfig projection = config.getRedisProjection();
        if (projection == null) {
            projection = RedisProjectionConfig.defaultFor(projectId);
            config.setRedisProjection(projection);
        }
        if (form.getKeyPrefix() != null) {
            projection.setKeyPrefix(form.getKeyPrefix());
        }
        if (form.getValueFormat() != null && !form.getValueFormat().isBlank()) {
            try {
                projection.setValueFormat(RedisValueFormat.valueOf(form.getValueFormat().trim()));
            } catch (IllegalArgumentException e) {
                return "error.project.redis.valueFormat.invalid";
            }
        }
        if (form.getTtlSeconds() != null) {
            projection.setTtlSeconds(form.getTtlSeconds());
        }
        if (form.getMaxValueBytes() != null) {
            projection.setMaxValueBytes(form.getMaxValueBytes());
        }
        if (form.getMaxCommandsPerTransaction() != null) {
            projection.setMaxCommandsPerTransaction(form.getMaxCommandsPerTransaction());
        }
        if (form.getExcludedColumns() != null) {
            projection.setExcludedColumns(splitColumns(form.getExcludedColumns()));
        }
        return null;
    }

    /** Comma-separated column list from one text input; blank entries are ignored. */
    private static java.util.Set<String> splitColumns(String raw) {
        java.util.Set<String> columns = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String column = part.trim();
            if (!column.isEmpty()) {
                columns.add(column);
            }
        }
        return columns;
    }

    /**
     * Resolves a save's message key for the JSON answer the same way the layout resolves a
     * flash banner: a plain key becomes its translation, and {@code key:context} keeps the
     * context readable after the localized prefix. An unknown key is shown as-is.
     */
    private String localize(String key) {
        Locale locale = LocaleContextHolder.getLocale();
        int colon = key.indexOf(':');
        if (colon < 0) {
            return messages.get(key, locale);
        }
        String headKey = key.substring(0, colon);
        String head = messages.get(headKey, locale);
        if (head.equals(headKey)) {
            return key;
        }
        String context = key.substring(colon + 1).trim();
        return context.isEmpty() ? head : head + " " + context;
    }

    /**
     * Pairs the form's per-table mapping rows: the hidden {@code mappingSource} and the
     * {@code mappingTarget} input are submitted in document order, one pair per source
     * table. Only checked tables keep a mapping — an unchecked table is not synced, so it
     * has no target table to rename even if its input still shows an old value.
     *
     * <p>Values are stored as typed; whitespace stripping and blank/self-mapping removal
     * happen once in {@link ProjectService#saveConfig}, which also guards direct callers.
     */
    private static Map<String, String> readTableNameMapping(List<String> sources,
                                                             List<String> targets,
                                                             SyncConfig config) {
        Map<String, String> mapping = new LinkedHashMap<>();
        if (targets == null) {
            return mapping;
        }
        int rows = Math.min(sources.size(), targets.size());
        for (int i = 0; i < rows; i++) {
            String source = sources.get(i);
            String target = targets.get(i);
            if (source == null || source.isBlank() || target == null
                    || !config.includesTable(source)) {
                continue;
            }
            mapping.put(source, target);
        }
        return mapping;
    }

    /** Server-side bounds for the full-load knobs; the form's min/max is only a hint. */
    private static final int MAX_FULL_LOAD_BATCH = 20_000;

    /**
     * Null stays null ("use the global default"); out-of-range values are pulled to the
     * nearest bound rather than rejected, so an old form or a direct POST cannot make the
     * project unsaveable.
     */
    private Integer clampFullLoadBatchSize(Integer value) {
        if (value == null) {
            return null;
        }
        int clamped = Math.max(1, Math.min(value, MAX_FULL_LOAD_BATCH));
        if (clamped != value) {
            log.info("Full-load batch size {} out of range; storing {}", value, clamped);
        }
        return clamped;
    }

    /** Same contract as {@link #clampFullLoadBatchSize}: null = global default. */
    private Integer clampFullLoadParallelism(Integer value) {
        if (value == null) {
            return null;
        }
        int clamped = Math.max(1, Math.min(value, FullLoadCapacity.MAX_PARALLELISM));
        if (clamped != value) {
            log.info("Full-load parallelism {} out of range; storing {}", value, clamped);
        }
        return clamped;
    }

    /**
     * Appends one column-cleansing rule. The closed rule set is validated here rather than
     * in the sync engine: a bad rule must be refused when it is saved, not discovered
     * mid-load. A running project is refused for the same reason as any other config change.
     */
    @PostMapping("/{id}/column-rules")
    public String addColumnRule(@PathVariable Long id,
                                @RequestParam(required = false) String tableName,
                                @RequestParam(name = "columnName", required = false)
                                        String columnName,
                                @RequestParam(required = false) String ruleType,
                                @RequestParam(required = false) String ruleArg,
                                @RequestParam(required = false) String ruleReplacement,
                                RedirectAttributes flash) {
        try {
            var project = projectService.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
            AccessGuard.current().requireEditable(project.getOwnerUsername());
            if (Boolean.TRUE.equals(project.getEnabled())) {
                flash.addFlashAttribute("error", "error.project.stop.before.config");
                return "redirect:/projects/" + id;
            }
            String table = trimToNull(tableName);
            String column = trimToNull(columnName);
            if (table == null || column == null) {
                flash.addFlashAttribute("error", "error.columnrule.required");
                return "redirect:/projects/" + id;
            }
            ColumnRuleType type = parseRuleType(ruleType);
            String arg = trimToNull(ruleArg);
            String replacement = trimToNull(ruleReplacement);
            String invalid = validateRuleArgs(type, arg, replacement);
            if (invalid != null) {
                flash.addFlashAttribute("error", invalid);
                return "redirect:/projects/" + id;
            }
            SyncConfig config = projectService.loadConfig(project);
            ColumnRule rule = new ColumnRule();
            rule.setTable(table);
            rule.setColumn(column);
            rule.setType(type);
            rule.setArg(arg);
            rule.setReplacement(replacement);
            // The cleanser applies rules in stored order, so an identical rule saved twice
            // runs twice (two prefixes stripped, two regex passes) and the panel lists the
            // same row twice. Table/column are matched case-insensitively at sync time, so a
            // case difference is the same rule; the argument and replacement are not.
            if (hasDuplicate(config.getColumnRules(), rule)) {
                flash.addFlashAttribute("error", "error.columnrule.duplicate");
                return "redirect:/projects/" + id;
            }
            config.getColumnRules().add(rule);
            projectService.saveConfig(id, config, AccessGuard.current());
            flash.addFlashAttribute("message", "msg.columnrule.saved");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/projects/" + id;
    }

    /** Removes one rule by its position in the stored list. */
    @PostMapping("/{id}/column-rules/delete")
    public String deleteColumnRule(@PathVariable Long id,
                                   @RequestParam(defaultValue = "-1") int index,
                                   RedirectAttributes flash) {
        try {
            var project = projectService.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
            AccessGuard.current().requireEditable(project.getOwnerUsername());
            if (Boolean.TRUE.equals(project.getEnabled())) {
                flash.addFlashAttribute("error", "error.project.stop.before.config");
                return "redirect:/projects/" + id;
            }
            SyncConfig config = projectService.loadConfig(project);
            if (index < 0 || index >= config.getColumnRules().size()) {
                flash.addFlashAttribute("error", "error.columnrule.notfound");
                return "redirect:/projects/" + id;
            }
            config.getColumnRules().remove(index);
            projectService.saveConfig(id, config, AccessGuard.current());
            flash.addFlashAttribute("message", "msg.columnrule.saved");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/projects/" + id;
    }

    /** Unknown rule names are a user error, not a 500. */
    private static ColumnRuleType parseRuleType(String ruleType) {
        if (ruleType == null || ruleType.isBlank()) {
            throw new IllegalArgumentException("error.columnrule.type.invalid");
        }
        try {
            return ColumnRuleType.valueOf(ruleType.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("error.columnrule.type.invalid");
        }
    }

    /** Returns the message key for an invalid argument, or null when the rule is usable. */
    private static String validateRuleArgs(ColumnRuleType type, String arg, String replacement) {
        boolean requiresArg = type == ColumnRuleType.STRIP_PREFIX
                || type == ColumnRuleType.STRIP_SUFFIX
                || type == ColumnRuleType.REGEX_REPLACE;
        if (requiresArg && arg == null) {
            return "error.columnrule.arg.required";
        }
        if (type == ColumnRuleType.REGEX_REPLACE) {
            java.util.regex.Pattern pattern;
            try {
                pattern = java.util.regex.Pattern.compile(arg);
            } catch (java.util.regex.PatternSyntaxException e) {
                return "error.columnrule.regex.invalid";
            }
            // Matcher parses the replacement lazily, at the first match; an invalid one would
            // therefore save cleanly and then fail every sync cycle. Validate the syntax here.
            if (!ColumnCleanser.isValidReplacement(pattern, replacement)) {
                return "error.columnrule.replacement.invalid";
            }
        }
        return null;
    }

    /**
     * A rule is identified by table, column, type, argument and replacement. Table and column
     * are database identifiers, matched case-insensitively by {@code ColumnCleanser}, so a
     * case difference names the same rule; the argument and replacement are compared as-is
     * (a prefix or regex is case-sensitive at sync time).
     */
    private static boolean hasDuplicate(List<ColumnRule> rules, ColumnRule candidate) {
        if (rules == null) {
            return false;
        }
        for (ColumnRule existing : rules) {
            if (existing == null || existing.getType() != candidate.getType()) {
                continue;
            }
            if (existing.getTable() == null || existing.getColumn() == null) {
                continue;
            }
            if (!existing.getTable().equalsIgnoreCase(candidate.getTable())
                    || !existing.getColumn().equalsIgnoreCase(candidate.getColumn())) {
                continue;
            }
            if (java.util.Objects.equals(existing.getArg(), candidate.getArg())
                    && java.util.Objects.equals(existing.getReplacement(),
                            candidate.getReplacement())) {
                return true;
            }
        }
        return false;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Sets an explicit cursor column for one table, overriding auto-detection.
     *
     * <p>The service owns the rules that make the override safe: a running project refuses
     * it, and a changed column re-baselines the table (the stored watermark belongs to the
     * column that produced it).
     */
    @PostMapping("/{id}/cursor")
    public String saveCursor(@PathVariable Long id,
                            @RequestParam String tableName,
                            @RequestParam(required = false) String cursorColumn,
                            RedirectAttributes flash) {
        try {
            projectService.saveCursorColumn(id, tableName, cursorColumn, AccessGuard.current());
            flash.addFlashAttribute("message", "msg.cursor.saved");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/projects/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        try {
            projectService.delete(id, AccessGuard.current());
            flash.addFlashAttribute("message", "msg.project.deleted");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/projects";
    }
}
