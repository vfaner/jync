package com.qqmu.jync.controller;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.EtlBatch;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.service.etl.EtlBatchService;
import com.qqmu.jync.service.etl.EtlBatchTableException;
import com.qqmu.jync.service.etl.EtlTaskService;

/**
 * Server-rendered ETL batch (task group) management.
 *
 * <p>The batch form is one page for creating a group and for editing its settings afterwards. A
 * batch owns its source/target pair once created, so the edit form renders the pair as read-only
 * hidden values. Every route is admin-only: the GETs through {@code ADMIN_ONLY_GET}, every POST
 * through the blanket rule.
 */
@Controller
@RequestMapping("/etl/batches")
public class EtlBatchController {

    private final EtlBatchService etlBatchService;
    private final EtlTaskService etlTaskService;

    public EtlBatchController(EtlBatchService etlBatchService, EtlTaskService etlTaskService) {
        this.etlBatchService = etlBatchService;
        this.etlTaskService = etlTaskService;
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        EtlBatch batch = new EtlBatch();
        model.addAttribute("batch", batch);
        model.addAttribute("selectedTables", List.of());
        model.addAttribute("existingTables", List.of());
        addFormOptions(model, batch);
        return "etl-batch-form";
    }

    @PostMapping("/create")
    public String create(@ModelAttribute("batch") EtlBatch batch,
                         @RequestParam(name = "tables", required = false) List<String> tables,
                         Model model, RedirectAttributes flash) {
        List<String> chosen = tables == null ? List.of() : tables;
        try {
            etlBatchService.createWithTables(batch, chosen);
            flash.addFlashAttribute("message", "msg.etl.batch.created");
            return "redirect:/etl";
        } catch (EtlBatchTableException e) {
            // The names that were refused travel back to the form so the operator can see
            // which tick boxes to drop instead of guessing from a generic message.
            model.addAttribute("error", e.getMessage());
            model.addAttribute("errorDetail", String.join(", ", e.names()));
            model.addAttribute("selectedTables", chosen);
            model.addAttribute("existingTables", List.of());
            addFormOptions(model, batch);
            return "etl-batch-form";
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("selectedTables", chosen);
            model.addAttribute("existingTables", List.of());
            addFormOptions(model, batch);
            return "etl-batch-form";
        }
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes flash) {
        EtlBatch batch = etlBatchService.findById(id).orElse(null);
        if (batch == null) {
            flash.addFlashAttribute("error", "error.etl.batch.not.found");
            return "redirect:/etl";
        }
        List<EtlTask> members = etlBatchService.members(id);
        Map<Long, Boolean> memberRunning = new HashMap<>();
        for (EtlTask member : members) {
            memberRunning.put(member.getId(), etlTaskService.isRunning(member.getId()));
        }
        model.addAttribute("batch", batch);
        model.addAttribute("members", members);
        model.addAttribute("memberRunning", memberRunning);
        model.addAttribute("running", etlBatchService.isRunning(id));
        // The picker on the edit page offers the source's remaining tables; every target
        // table already in the batch is refused (two overwrite members on one table).
        List<String> takenTargets = members.stream()
                .map(EtlTask::getTargetTable)
                .toList();
        model.addAttribute("existingTables", takenTargets);
        model.addAttribute("selectedTables", List.of());
        addFormOptions(model, batch);
        return "etl-batch-form";
    }

    @PostMapping("/save")
    public String save(@ModelAttribute("batch") EtlBatch batch, RedirectAttributes flash) {
        try {
            EtlBatch saved = etlBatchService.saveSettings(batch);
            flash.addFlashAttribute("message", "msg.etl.batch.saved");
            return "redirect:/etl/batches/" + saved.getId() + "/edit";
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return batch.getId() == null
                    ? "redirect:/etl" : "redirect:/etl/batches/" + batch.getId() + "/edit";
        }
    }

    @PostMapping("/{id}/add-tables")
    public String addTables(@PathVariable Long id,
                            @RequestParam(name = "tables", required = false) List<String> tables,
                            RedirectAttributes flash) {
        try {
            etlBatchService.addTables(id, tables == null ? List.of() : tables);
            flash.addFlashAttribute("message", "msg.etl.batch.tables.added");
        } catch (EtlBatchTableException e) {
            // Flash 的 key:附加上下文 约定（见 layout.html）把被拒绝的表名带回去。
            flash.addFlashAttribute("error",
                    e.getMessage() + ": " + String.join(", ", e.names()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/etl/batches/" + id + "/edit";
    }

    @PostMapping("/{id}/members/{taskId}/remove")
    public String removeMember(@PathVariable Long id, @PathVariable Long taskId,
                               RedirectAttributes flash) {
        try {
            etlBatchService.removeMember(id, taskId);
            flash.addFlashAttribute("message", etlBatchService.members(id).isEmpty()
                    ? "msg.etl.batch.member.emptied" : "msg.etl.batch.member.removed");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/etl/batches/" + id + "/edit";
    }

    @PostMapping("/{id}/run")
    public String runNow(@PathVariable Long id, RedirectAttributes flash) {
        try {
            if (etlBatchService.submit(id)) {
                flash.addFlashAttribute("message", "msg.etl.batch.started");
            } else {
                flash.addFlashAttribute("error", "error.etl.batch.running");
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/etl";
    }

    @PostMapping("/{id}/start")
    public String start(@PathVariable Long id, RedirectAttributes flash) {
        return changeEnabled(id, true, flash);
    }

    @PostMapping("/{id}/stop")
    public String stop(@PathVariable Long id, RedirectAttributes flash) {
        return changeEnabled(id, false, flash);
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        try {
            etlBatchService.delete(id);
            flash.addFlashAttribute("message", "msg.etl.batch.deleted");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/etl";
    }

    private String changeEnabled(Long id, boolean enabled, RedirectAttributes flash) {
        try {
            etlBatchService.setEnabled(id, enabled);
            flash.addFlashAttribute("message",
                    enabled ? "msg.etl.batch.enabled" : "msg.etl.batch.disabled");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/etl";
    }

    private void addFormOptions(Model model, EtlBatch batch) {
        List<DatabaseConfig> connections = etlTaskService.connections();
        model.addAttribute("connections", EtlConnectionOptions.sources(connections,
                batch == null ? null : batch.getSourceDbId()));
        model.addAttribute("targetConnections", EtlConnectionOptions.targets(connections,
                batch == null ? null : batch.getTargetDbId()));
        // The edit form shows the locked pair by name; the selects are gone there.
        Map<Long, String> names = new HashMap<>();
        for (DatabaseConfig config : connections) {
            names.put(config.getId(), config.getName());
        }
        model.addAttribute("connectionNames", names);
    }
}
