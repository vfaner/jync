package com.qqmu.jync.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqmu.jync.dto.MigrationTaskForm;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationRun;
import com.qqmu.jync.model.MigrationRunTrigger;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.repository.MigrationRunRepository;
import com.qqmu.jync.service.migration.MigrationConnectionService;
import com.qqmu.jync.service.migration.MigrationPrecheckResult;
import com.qqmu.jync.service.migration.MigrationTaskService;
import com.qqmu.jync.service.migration.MigrationTypeHandler;
import com.qqmu.jync.service.migration.MigrationTypeRegistry;

/**
 * Server-rendered pages for dedicated migrations.
 *
 * <p>Listing and detail pages are readable by every signed-in user, like the ETL pages. The
 * forms are admin-only through {@code ADMIN_ONLY_GET}, and every action is a POST, which the
 * blanket rule already restricts to administrators. A viewer therefore never sees a control
 * whose submission would be refused.
 */
@Controller
@RequestMapping("/migrations")
public class MigrationController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MigrationTaskService taskService;
    private final MigrationConnectionService connectionService;
    private final MigrationTypeRegistry registry;
    private final MigrationRunRepository runRepository;

    public MigrationController(MigrationTaskService taskService,
                               MigrationConnectionService connectionService,
                               MigrationTypeRegistry registry,
                               MigrationRunRepository runRepository) {
        this.taskService = taskService;
        this.connectionService = connectionService;
        this.registry = registry;
        this.runRepository = runRepository;
    }

    @GetMapping
    public String home(Model model) {
        List<TypeCard> cards = new ArrayList<>();
        for (MigrationType type : MigrationType.values()) {
            boolean available = type.available() && registry.isRegistered(type);
            long taskCount = available ? taskService.findByType(type).size() : 0;
            cards.add(new TypeCard(type, available, taskCount));
        }
        model.addAttribute("cards", cards);
        return "migration-home";
    }

    @GetMapping("/{type}")
    public String list(@PathVariable String type, Model model, RedirectAttributes flash) {
        MigrationType resolved = resolve(type, flash);
        if (resolved == null) {
            return "redirect:/migrations";
        }
        if (!isAvailable(resolved)) {
            flash.addFlashAttribute("error", "error.migration.type.unknown");
            return "redirect:/migrations";
        }
        List<MigrationTask> tasks = taskService.findByType(resolved);
        Map<Long, Boolean> running = new HashMap<>();
        for (MigrationTask task : tasks) {
            running.put(task.getId(), taskService.isRunning(task.getId()));
        }
        model.addAttribute("type", resolved);
        model.addAttribute("tasks", tasks);
        model.addAttribute("running", running);
        model.addAttribute("connectionNames", connectionNames());
        return "migration-list";
    }

    @GetMapping("/{type}/new")
    public String createForm(@PathVariable String type, Model model, RedirectAttributes flash) {
        MigrationType resolved = resolve(type, flash);
        if (resolved == null) {
            return "redirect:/migrations";
        }
        MigrationTypeHandler handler = handlerOrRedirect(resolved, flash);
        if (handler == null) {
            return "redirect:/migrations";
        }
        MigrationTaskForm form = new MigrationTaskForm();
        form.setType(resolved);
        model.addAttribute("task", form);
        addFormOptions(model, resolved, handler, handler.defaultOptions());
        return "migration-form";
    }

    @GetMapping("/{type}/{id}/edit")
    public String editForm(@PathVariable String type, @PathVariable Long id, Model model,
                           RedirectAttributes flash) {
        MigrationType resolved = resolve(type, flash);
        if (resolved == null) {
            return "redirect:/migrations";
        }
        MigrationTypeHandler handler = handlerOrRedirect(resolved, flash);
        if (handler == null) {
            return "redirect:/migrations";
        }
        MigrationTask task = taskService.findById(id).orElse(null);
        if (task == null || task.getType() != resolved) {
            flash.addFlashAttribute("error", "error.migration.not.found");
            return "redirect:/migrations/" + type;
        }
        model.addAttribute("task", formFor(task));
        addFormOptions(model, resolved, handler, storedOptions(task, handler));
        return "migration-form";
    }

    /*
     * The path segment is name {@code typeKey}, not {@code type}: Spring MVC merges URI
     * template variables into @ModelAttribute binding, so a {type} segment would be bound to
     * MigrationTaskForm.type as the enum-key string and fail conversion with a 400.
     */
    @PostMapping("/{typeKey}/save")
    public String save(@PathVariable String typeKey,
                       @ModelAttribute("task") MigrationTaskForm form,
                       HttpServletRequest request,
                       Model model, RedirectAttributes flash) {
        MigrationType resolved = resolve(typeKey, flash);
        if (resolved == null) {
            return "redirect:/migrations";
        }
        form.setType(resolved);
        ObjectNode submitted = formOptions(request);
        try {
            MigrationTask saved = taskService.save(form, submitted);
            flash.addFlashAttribute("message", "msg.migration.saved");
            return "redirect:/migrations/" + typeKey + "/" + saved.getId();
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
            MigrationTypeHandler handler = registry.find(resolved).orElse(null);
            addFormOptions(model, resolved, handler, displayOptions(handler, submitted));
            return "migration-form";
        }
    }

    @PostMapping("/{typeKey}/precheck")
    public String precheck(@PathVariable String typeKey,
                           @ModelAttribute("task") MigrationTaskForm form,
                           HttpServletRequest request,
                           Model model, RedirectAttributes flash) {
        MigrationType resolved = resolve(typeKey, flash);
        if (resolved == null) {
            return "redirect:/migrations";
        }
        MigrationTypeHandler handler = handlerOrRedirect(resolved, flash);
        if (handler == null) {
            return "redirect:/migrations";
        }
        form.setType(resolved);
        ObjectNode submitted = formOptions(request);
        JsonNode options = displayOptions(handler, submitted);
        try {
            MigrationPrecheckResult result = handler.precheck(
                    transientTask(form, resolved), options,
                    connection(form.getSourceConnectionId(), "error.migration.source.missing"),
                    connection(form.getTargetConnectionId(), "error.migration.target.missing"));
            model.addAttribute("precheck", result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
        }
        addFormOptions(model, resolved, handler, options);
        return "migration-form";
    }

    @GetMapping("/{type}/{id}")
    public String detail(@PathVariable String type, @PathVariable Long id, Model model,
                         RedirectAttributes flash) {
        MigrationType resolved = resolve(type, flash);
        if (resolved == null) {
            return "redirect:/migrations";
        }
        if (!isAvailable(resolved)) {
            flash.addFlashAttribute("error", "error.migration.type.unknown");
            return "redirect:/migrations";
        }
        MigrationTask task = taskService.findById(id).orElse(null);
        if (task == null || task.getType() != resolved) {
            flash.addFlashAttribute("error", "error.migration.not.found");
            return "redirect:/migrations/" + type;
        }
        model.addAttribute("type", resolved);
        model.addAttribute("task", task);
        model.addAttribute("running", taskService.isRunning(id));
        model.addAttribute("connectionNames", connectionNames());
        model.addAttribute("runs", runRepository
                .findTop50ByMigrationTaskIdOrderByStartedAtDesc(id));
        return "migration-detail";
    }

    @PostMapping("/{type}/{id}/run")
    public String run(@PathVariable String type, @PathVariable Long id,
                      RedirectAttributes flash) {
        MigrationType resolved = resolve(type, flash);
        if (resolved == null) {
            return "redirect:/migrations";
        }
        try {
            if (taskService.submit(id, MigrationRunTrigger.MANUAL)) {
                flash.addFlashAttribute("message", "msg.migration.started");
            } else {
                flash.addFlashAttribute("error", "error.migration.running");
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/migrations/" + type + "/" + id;
    }

    @PostMapping("/{type}/{id}/cancel")
    public String cancel(@PathVariable String type, @PathVariable Long id,
                         RedirectAttributes flash) {
        MigrationType resolved = resolve(type, flash);
        if (resolved == null) {
            return "redirect:/migrations";
        }
        if (taskService.cancel(id)) {
            flash.addFlashAttribute("message", "msg.migration.cancelling");
        } else {
            flash.addFlashAttribute("error", "error.migration.not.running");
        }
        return "redirect:/migrations/" + type + "/" + id;
    }

    @PostMapping("/{type}/{id}/enable")
    public String enable(@PathVariable String type, @PathVariable Long id,
                         RedirectAttributes flash) {
        return changeEnabled(type, id, true, flash);
    }

    @PostMapping("/{type}/{id}/disable")
    public String disable(@PathVariable String type, @PathVariable Long id,
                          RedirectAttributes flash) {
        return changeEnabled(type, id, false, flash);
    }

    @PostMapping("/{type}/{id}/delete")
    public String delete(@PathVariable String type, @PathVariable Long id,
                         RedirectAttributes flash) {
        MigrationType resolved = resolve(type, flash);
        if (resolved == null) {
            return "redirect:/migrations";
        }
        try {
            taskService.delete(id);
            flash.addFlashAttribute("message", "msg.migration.deleted");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/migrations/" + type;
    }

    private String changeEnabled(String type, Long id, boolean enabled,
                                 RedirectAttributes flash) {
        MigrationType resolved = resolve(type, flash);
        if (resolved == null) {
            return "redirect:/migrations";
        }
        try {
            taskService.setEnabled(id, enabled);
            flash.addFlashAttribute("message",
                    enabled ? "msg.migration.enabled" : "msg.migration.disabled");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/migrations/" + type + "/" + id;
    }

    private void addFormOptions(Model model, MigrationType type, MigrationTypeHandler handler,
                                JsonNode options) {
        List<MigrationConnection> all = connectionService.findAll();
        List<MigrationConnection> sources = new ArrayList<>();
        List<MigrationConnection> targets = new ArrayList<>();
        for (MigrationConnection connection : all) {
            if (handler != null && handler.sourceKinds().contains(connection.getKind())) {
                sources.add(connection);
            }
            if (handler != null && handler.targetKinds().contains(connection.getKind())) {
                targets.add(connection);
            }
        }
        model.addAttribute("type", type);
        model.addAttribute("handler", handler);
        model.addAttribute("options", options == null ? JSON.createObjectNode() : options);
        model.addAttribute("sourceConnections", sources);
        model.addAttribute("targetConnections", targets);
    }

    private Map<Long, String> connectionNames() {
        Map<Long, String> names = new HashMap<>();
        for (MigrationConnection connection : connectionService.findAll()) {
            names.put(connection.getId(), connection.getName());
        }
        return names;
    }

    private MigrationConnection connection(Long id, String errorKey) {
        if (id == null) {
            throw new IllegalArgumentException(errorKey);
        }
        return connectionService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(errorKey));
    }

    private static MigrationTask transientTask(MigrationTaskForm form, MigrationType type) {
        MigrationTask task = new MigrationTask();
        task.setId(form.getId());
        task.setName(form.getName());
        task.setDescription(form.getDescription());
        task.setType(type);
        task.setSourceConnectionId(form.getSourceConnectionId());
        task.setTargetConnectionId(form.getTargetConnectionId());
        task.setCronExpression(form.getCronExpression());
        task.setEnabled(form.isEnabled());
        task.setTimeoutSeconds(form.getTimeoutSeconds());
        return task;
    }

    private static MigrationTaskForm formFor(MigrationTask task) {
        MigrationTaskForm form = new MigrationTaskForm();
        form.setId(task.getId());
        form.setName(task.getName());
        form.setDescription(task.getDescription());
        form.setType(task.getType());
        form.setSourceConnectionId(task.getSourceConnectionId());
        form.setTargetConnectionId(task.getTargetConnectionId());
        form.setCronExpression(task.getCronExpression());
        form.setEnabled(Boolean.TRUE.equals(task.getEnabled()));
        form.setTimeoutSeconds(task.getTimeoutSeconds());
        return form;
    }

    /**
     * Options a form re-renders. Starts from the handler's defaults so a partially stored
     * blob (or one written by an older release) still renders every field, then overlays the
     * values the user actually submitted. No validation happens here: a rejected save must
     * show exactly what was typed.
     */
    private static ObjectNode displayOptions(MigrationTypeHandler handler, ObjectNode submitted) {
        ObjectNode display = handler == null
                ? JSON.createObjectNode()
                : copyObject(handler.defaultOptions());
        if (submitted != null) {
            Iterator<Map.Entry<String, JsonNode>> fields = submitted.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                if (key.startsWith("opt.")) {
                    key = key.substring("opt.".length());
                }
                display.set(key, entry.getValue());
            }
        }
        return display;
    }

    private static JsonNode storedOptions(MigrationTask task, MigrationTypeHandler handler) {
        ObjectNode stored = copyObject(handler.defaultOptions());
        if (task.getOptionsJson() == null || task.getOptionsJson().isBlank()) {
            return stored;
        }
        try {
            JsonNode parsed = JSON.readTree(task.getOptionsJson());
            if (parsed != null && parsed.isObject()) {
                stored.setAll((ObjectNode) parsed);
            }
        } catch (IOException ignored) {
            // A corrupt blob still renders the form with defaults; saving overwrites it.
        }
        return stored;
    }

    private static ObjectNode copyObject(JsonNode node) {
        return node instanceof ObjectNode ? ((ObjectNode) node).deepCopy()
                : JSON.createObjectNode();
    }

    /**
     * Collects the type-specific {@code opt.*} parameters. A checkbox precedes its same-named
     * hidden input, so the first value wins: checked yields both "true" and "false", unchecked
     * yields only "false".
     */
    private static ObjectNode formOptions(HttpServletRequest request) {
        ObjectNode node = JSON.createObjectNode();
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("opt.")) {
                continue;
            }
            String[] values = entry.getValue();
            if (values == null || values.length == 0) {
                continue;
            }
            node.put(key, values[0]);
        }
        return node;
    }

    private boolean isAvailable(MigrationType type) {
        return type.available() && registry.isRegistered(type);
    }

    private MigrationTypeHandler handlerOrRedirect(MigrationType type, RedirectAttributes flash) {
        if (!isAvailable(type)) {
            flash.addFlashAttribute("error", "error.migration.type.unknown");
            return null;
        }
        MigrationTypeHandler handler = registry.find(type).orElse(null);
        if (handler == null) {
            flash.addFlashAttribute("error", "error.migration.type.unknown");
        }
        return handler;
    }

    private static MigrationType resolve(String key, RedirectAttributes flash) {
        try {
            return MigrationType.fromKey(key);
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", "error.migration.type.unknown");
            return null;
        }
    }

    /** One card on the landing page; unavailable types keep their place without a handler. */
    public record TypeCard(MigrationType type, boolean available, long taskCount) {
    }
}
