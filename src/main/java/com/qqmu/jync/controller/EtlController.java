package com.qqmu.jync.controller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlBatch;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.service.etl.EtlBatchService;
import com.qqmu.jync.service.etl.EtlPrecheckResult;
import com.qqmu.jync.service.etl.EtlPrecheckService;
import com.qqmu.jync.service.etl.EtlTaskService;

/**
 * Server-rendered ETL task management.
 *
 * <p>Same authorization split as the real-time project pages: the listing is readable by every
 * signed-in user, while the forms and every POST (save, schedule, run, delete) are admin-only
 * through the blanket {@code POST /**} rule and the {@code ADMIN_ONLY_GET} entries for the
 * forms. A viewer therefore never sees a button whose submit would be refused.
 */
@Controller
@RequestMapping("/etl")
public class EtlController {

    private final EtlTaskService etlTaskService;
    private final EtlPrecheckService precheckService;
    private final EtlBatchService etlBatchService;

    public EtlController(EtlTaskService etlTaskService, EtlPrecheckService precheckService,
                         EtlBatchService etlBatchService) {
        this.etlTaskService = etlTaskService;
        this.precheckService = precheckService;
        this.etlBatchService = etlBatchService;
    }

    @GetMapping
    public String list(Model model) {
        List<EtlTask> tasks = etlTaskService.findAll();
        Map<Long, Boolean> running = new HashMap<>();
        for (EtlTask task : tasks) {
            running.put(task.getId(), etlTaskService.isRunning(task.getId()));
        }

        // Batches own their members' schedule and connections; the page shows the group and the
        // tables it runs, while the table below keeps only the tasks that stand alone.
        List<EtlBatch> batches = etlBatchService.findAll();
        Map<Long, List<EtlTask>> batchMembers = new HashMap<>();
        Map<Long, Boolean> batchRunning = new HashMap<>();
        List<EtlTask> standaloneTasks = new ArrayList<>();
        for (EtlBatch batch : batches) {
            // Start every card with an empty list; the members are grouped below from the task
            // list already loaded for this page. Querying members per batch made the page issue
            // one select per batch on every render.
            batchMembers.put(batch.getId(), new ArrayList<>());
            batchRunning.put(batch.getId(), etlBatchService.isRunning(batch.getId()));
        }
        for (EtlTask task : tasks) {
            if (task.getBatchId() == null) {
                standaloneTasks.add(task);
            } else {
                batchMembers.computeIfAbsent(task.getBatchId(), id -> new ArrayList<>())
                        .add(task);
            }
        }
        // findAll orders by id descending; member lists were always ordered oldest first.
        for (List<EtlTask> members : batchMembers.values()) {
            members.sort(Comparator.comparing(EtlTask::getId));
        }

        model.addAttribute("standaloneTasks", standaloneTasks);
        model.addAttribute("batches", batches);
        model.addAttribute("batchMembers", batchMembers);
        model.addAttribute("batchRunning", batchRunning);
        model.addAttribute("running", running);
        model.addAttribute("connectionNames", connectionNames());
        return "etl-list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        EtlTask task = new EtlTask();
        task.setWriteMode(EtlWriteMode.OVERWRITE);
        model.addAttribute("task", task);
        addFormOptions(model, task);
        return "etl-form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes flash) {
        EtlTask task = etlTaskService.findById(id).orElse(null);
        if (task == null) {
            flash.addFlashAttribute("error", "error.etl.not.found");
            return "redirect:/etl";
        }
        AccessGuard.current().requireEditable(task.getOwnerUsername());
        model.addAttribute("task", task);
        addFormOptions(model, task);
        return "etl-form";
    }

    @PostMapping("/save")
    public String save(@ModelAttribute("task") EtlTask task, Model model,
                       RedirectAttributes flash) {
        try {
            etlTaskService.save(task, AccessGuard.current());
            flash.addFlashAttribute("message", "msg.etl.saved");
            return "redirect:/etl";
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
            addFormOptions(model, task);
            return "etl-form";
        }
    }

    @PostMapping("/precheck")
    public String precheck(@ModelAttribute("task") EtlTask task, Model model) {
        try {
            if (task.getId() != null) {
                // The form names an existing task: rehearsal must be the task owner's too.
                etlTaskService.requireEditable(task.getId(), AccessGuard.current());
            }
            EtlPrecheckResult result = precheckService.check(task, AccessGuard.current());
            model.addAttribute("precheck", result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("precheckError", e.getMessage());
        }
        addFormOptions(model, task);
        // Pass the task back to the form (it may have binding errors)
        model.addAttribute("task", task);
        return "etl-form";
    }

    @PostMapping("/{id}/start")
    public String start(@PathVariable Long id, RedirectAttributes flash) {
        return changeEnabled(id, true, flash);
    }

    @PostMapping("/{id}/stop")
    public String stop(@PathVariable Long id, RedirectAttributes flash) {
        return changeEnabled(id, false, flash);
    }

    @PostMapping("/{id}/run")
    public String runNow(@PathVariable Long id, RedirectAttributes flash) {
        try {
            // submit() has no actor of its own: the caller must qualify before it runs.
            etlTaskService.requireEditable(id, AccessGuard.current());
            if (etlTaskService.submit(id)) {
                flash.addFlashAttribute("message", "msg.etl.started");
            } else {
                flash.addFlashAttribute("error", "error.etl.running");
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/etl";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        try {
            etlTaskService.delete(id, AccessGuard.current());
            flash.addFlashAttribute("message", "msg.etl.deleted");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/etl";
    }

    private String changeEnabled(Long id, boolean enabled, RedirectAttributes flash) {
        try {
            etlTaskService.setEnabled(id, enabled, AccessGuard.current());
            flash.addFlashAttribute("message",
                    enabled ? "msg.etl.enabled" : "msg.etl.disabled");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/etl";
    }

    private void addFormOptions(Model model, EtlTask task) {
        EtlTask stored = task == null || task.getId() == null
                ? null : etlTaskService.findById(task.getId()).orElse(null);
        if (stored != null) {
            // The form posts only the id: membership and the stored schedule are not fields.
            // Without this, a re-render after a rejected save or a precheck would show a member
            // as a standalone task — editable connections and a cron box the save then refuses.
            if (task.getBatchId() == null) {
                task.setBatchId(stored.getBatchId());
            }
            model.addAttribute("running", etlTaskService.isRunning(task.getId()));
        }
        if (task != null && task.getBatchId() != null) {
            // The banner and the locked connection pair come from the owning batch.
            etlBatchService.findById(task.getBatchId())
                    .ifPresent(batch -> model.addAttribute("batch", batch));
        }
        List<DatabaseConfig> connections = etlTaskService.connections();
        // A new task belongs to its creator; an edit keeps the stored owner's scope, so even an
        // administrator sees the connections the task was built from.
        String scope = EtlConnectionOptions.optionScope(
                stored == null ? null : stored.getOwnerUsername(), AccessGuard.current());
        List<DatabaseConfig> sources = EtlConnectionOptions.sources(connections,
                task == null ? null : task.getSourceDbId(), scope);
        Map<Long, String> connectionNames = new HashMap<>();
        for (DatabaseConfig config : connections) {
            connectionNames.put(config.getId(), config.getName());
        }
        model.addAttribute("connectionNames", connectionNames);
        model.addAttribute("connections", sources);
        model.addAttribute("targetConnections", EtlConnectionOptions.targets(connections,
                task == null ? null : task.getTargetDbId(), scope));
        model.addAttribute("writeModes", EtlWriteMode.values());
        // The form shows the SQL box or the Mongo fields, never both. Render the half the
        // task actually uses on the first paint rather than letting app.js correct it, which
        // would flash an empty extraction box at a Mongo operator.
        model.addAttribute("sourceMongo",
                isNativeMongo(sources, task == null ? null : task.getSourceDbId()));
        // The same first-paint rule for the three write-mode fields: a continuation only
        // applies to append and range overwrite, the range condition only bounds a range
        // overwrite, and the upsert key only names an upsert's match. A null mode is the
        // form's overwrite default. The fields stay in the form when hidden, so switching
        // modes back and forth keeps what was typed.
        EtlWriteMode mode = task == null ? null : task.getWriteMode();
        model.addAttribute("rangeApplicable", mode == EtlWriteMode.RANGE_OVERWRITE);
        // Same rule the runner and the save use, so a mode cannot show a field it never reads.
        model.addAttribute("resumeApplicable", mode != null && mode.supportsResume());
        model.addAttribute("upsertApplicable", mode == EtlWriteMode.UPSERT);
    }

    /** True when the chosen source is a native MongoDB connection (collection + filter). */
    private static boolean isNativeMongo(List<DatabaseConfig> sources, Long sourceDbId) {
        if (sourceDbId == null) {
            return false;
        }
        return sources.stream().anyMatch(source -> sourceDbId.equals(source.getId())
                && source.effectiveType() == DatabaseType.MONGODB_NATIVE);
    }

    private Map<Long, String> connectionNames() {
        Map<Long, String> names = new HashMap<>();
        for (DatabaseConfig config : etlTaskService.connections()) {
            names.put(config.getId(), config.getName());
        }
        return names;
    }
}
