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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
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

    public EtlController(EtlTaskService etlTaskService, EtlPrecheckService precheckService) {
        this.etlTaskService = etlTaskService;
        this.precheckService = precheckService;
    }

    @GetMapping
    public String list(Model model) {
        List<EtlTask> tasks = etlTaskService.findAll();
        Map<Long, Boolean> running = new HashMap<>();
        for (EtlTask task : tasks) {
            running.put(task.getId(), etlTaskService.isRunning(task.getId()));
        }
        model.addAttribute("tasks", tasks);
        model.addAttribute("running", running);
        model.addAttribute("connectionNames", connectionNames());
        return "etl-list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        EtlTask task = new EtlTask();
        task.setWriteMode(EtlWriteMode.OVERWRITE);
        model.addAttribute("task", task);
        addFormOptions(model);
        return "etl-form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes flash) {
        EtlTask task = etlTaskService.findById(id).orElse(null);
        if (task == null) {
            flash.addFlashAttribute("error", "error.etl.not.found");
            return "redirect:/etl";
        }
        model.addAttribute("task", task);
        model.addAttribute("running", etlTaskService.isRunning(id));
        addFormOptions(model);
        return "etl-form";
    }

    @PostMapping("/save")
    public String save(@ModelAttribute("task") EtlTask task, Model model,
                       RedirectAttributes flash) {
        try {
            etlTaskService.save(task);
            flash.addFlashAttribute("message", "msg.etl.saved");
            return "redirect:/etl";
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
            addFormOptions(model);
            return "etl-form";
        }
    }

    @PostMapping("/precheck")
    public String precheck(@ModelAttribute("task") EtlTask task, Model model) {
        try {
            EtlPrecheckResult result = precheckService.check(task);
            model.addAttribute("precheck", result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("precheckError", e.getMessage());
        }
        addFormOptions(model);
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
            etlTaskService.delete(id);
            flash.addFlashAttribute("message", "msg.etl.deleted");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/etl";
    }

    private String changeEnabled(Long id, boolean enabled, RedirectAttributes flash) {
        try {
            etlTaskService.setEnabled(id, enabled);
            flash.addFlashAttribute("message",
                    enabled ? "msg.etl.enabled" : "msg.etl.disabled");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/etl";
    }

    private void addFormOptions(Model model) {
        model.addAttribute("connections", etlTaskService.connections());
        model.addAttribute("writeModes", EtlWriteMode.values());
    }

    private Map<Long, String> connectionNames() {
        Map<Long, String> names = new HashMap<>();
        for (DatabaseConfig config : etlTaskService.connections()) {
            names.put(config.getId(), config.getName());
        }
        return names;
    }
}
