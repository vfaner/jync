package com.qqmu.jync.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.qqmu.jync.dto.MigrationConnectionForm;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.service.migration.MigrationConnectionService;

/** Server-rendered CRUD for the dedicated-migration module's own search connections. */
@Controller
@RequestMapping("/migrations/connections")
public class MigrationConnectionController {

    static final int PAGE_SIZE = 10;

    private final MigrationConnectionService service;

    public MigrationConnectionController(MigrationConnectionService service) {
        this.service = service;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("connections", service.findPage(page, PAGE_SIZE));
        return "migration-connections";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("form", new MigrationConnectionForm());
        addChoices(model);
        return "migration-connection-form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes flash) {
        try {
            model.addAttribute("form", service.formFor(id));
            addChoices(model);
            return "migration-connection-form";
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/migrations/connections";
        }
    }

    @PostMapping("/save")
    public String save(@ModelAttribute MigrationConnectionForm form,
                       @RequestParam(required = false) String rawPassword,
                       @RequestParam(required = false) String rawApiKey,
                       RedirectAttributes flash) {
        try {
            service.save(form, rawPassword, rawApiKey);
            flash.addFlashAttribute("message", "msg.migration.connection.saved");
            return "redirect:/migrations/connections";
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return form.getId() != null
                    ? "redirect:/migrations/connections/" + form.getId() + "/edit"
                    : "redirect:/migrations/connections/new";
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        try {
            service.delete(id);
            flash.addFlashAttribute("message", "msg.migration.connection.deleted");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/migrations/connections";
    }

    private static void addChoices(Model model) {
        model.addAttribute("kinds", MigrationConnectionKind.values());
        model.addAttribute("authTypes", MigrationConnectionAuthType.values());
    }
}
