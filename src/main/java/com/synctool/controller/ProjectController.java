package com.synctool.controller;

import java.util.LinkedHashMap;
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

import com.synctool.dto.SyncConfig;
import com.synctool.model.Project;
import com.synctool.model.SyncTask;
import com.synctool.service.DatabaseConfigService;
import com.synctool.service.ProjectService;

import lombok.extern.slf4j.Slf4j;

/** Server-rendered project management and configuration. */
@Controller
@RequestMapping("/projects")
@Slf4j
public class ProjectController {

    private final ProjectService projectService;
    private final DatabaseConfigService databaseConfigService;

    public ProjectController(ProjectService projectService,
                            DatabaseConfigService databaseConfigService) {
        this.projectService = projectService;
        this.databaseConfigService = databaseConfigService;
    }

    @GetMapping
    public String list(Model model) {
        List<Project> projects = projectService.findAll();

        // Collectors.toMap rejects null values, and a project has no task row until its first
        // save completes, so the map is built explicitly to allow absent entries.
        Map<Long, SyncTask> taskLookup = new LinkedHashMap<>();
        for (Project project : projects) {
            taskLookup.put(project.getId(), projectService.findTask(project.getId()).orElse(null));
        }

        model.addAttribute("projects", projects);
        model.addAttribute("taskLookup", taskLookup);
        model.addAttribute("activeNav", "projects");
        return "projects";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("project", new Project());
        model.addAttribute("databases", databaseConfigService.findAll());
        model.addAttribute("activeNav", "projects");
        return "project-form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes flash) {
        return projectService.findById(id).map(project -> {
            model.addAttribute("project", project);
            model.addAttribute("databases", databaseConfigService.findAll());
            model.addAttribute("activeNav", "projects");
            return "project-form";
        }).orElseGet(() -> {
            flash.addFlashAttribute("error", "error.project.not.found");
            return "redirect:/projects";
        });
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
        model.addAttribute("task", projectService.findTask(id).orElse(null));
        model.addAttribute("progressList", projectService.findProgress(id));
        model.addAttribute("scheduled", projectService.isScheduled(id));
        model.addAttribute("activeNav", "projects");

        try {
            ProjectService.SourceObjects objects = projectService.listSourceObjects(id);
            model.addAttribute("sourceObjects", objects);
        } catch (RuntimeException e) {
            log.debug("Could not list source objects for project {}: {}", id, e.getMessage());
            model.addAttribute("sourceWarning", e.getMessage());
        }
        return "project-detail";
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
                            RedirectAttributes flash) {
        try {
            var project = projectService.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
            SyncConfig config = projectService.loadConfig(project);

            // At least one table must be selected, per the requirement. An empty submission
            // would otherwise silently mean "all tables", which is not what unchecking implies.
            if (tables == null || tables.isEmpty()) {
                flash.addFlashAttribute("error", "error.select.at.least.one.table");
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
            config.setPollIntervalMs(pollIntervalMs);
            config.setCronExpression(cronExpression == null || cronExpression.isBlank()
                    ? null : cronExpression.trim());
            config.setBatchSize(batchSize);

            projectService.saveConfig(id, config);
            flash.addFlashAttribute("message", "msg.config.saved");
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

    /** Selects every object in the source, matching the documented select-all default. */
    @PostMapping("/{id}/select-all")
    public String selectAll(@PathVariable Long id, RedirectAttributes flash) {
        try {
            SyncConfig config = projectService.selectAll(id);
            projectService.saveConfig(id, config);
            flash.addFlashAttribute("message", "msg.selected.all");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/projects/" + id;
    }
}
