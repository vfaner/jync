package com.synctool.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.synctool.model.DatabaseConfig;
import com.synctool.model.DatabaseType;
import com.synctool.service.DatabaseConfigService;
import com.synctool.service.connection.DriverPresence;

import lombok.extern.slf4j.Slf4j;

/** Server-rendered CRUD for database connections. */
@Controller
@RequestMapping("/databases")
@Slf4j
public class DatabaseConfigController {

    private final DatabaseConfigService service;

    public DatabaseConfigController(DatabaseConfigService service) {
        this.service = service;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("configs", service.findAll());
        model.addAttribute("activeNav", "databases");
        return "database-configs";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(DatabaseType.MYSQL);
        config.setHost("localhost");
        config.setPort(DatabaseType.MYSQL.getDefaultPort());
        model.addAttribute("config", config);
        model.addAttribute("types", DatabaseType.values());
        model.addAttribute("jarCardVisible", jarCardVisible(config.getType()));
        model.addAttribute("activeNav", "databases");
        return "database-config-form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes flash) {
        return service.findById(id).map(config -> {
            model.addAttribute("config", config);
            model.addAttribute("types", DatabaseType.values());
            model.addAttribute("jarCardVisible", jarCardVisible(config.getType()));
            model.addAttribute("activeNav", "databases");
            return "database-config-form";
        }).orElseGet(() -> {
            flash.addFlashAttribute("error", "error.connection.missing");
            return "redirect:/databases";
        });
    }

    /**
     * The jar-path card is shown for CUSTOM (nothing is bundled) and for preset types whose
     * driver is not on the classpath (currently GBase and Oscar), so the first paint already
     * matches the selected type instead of flashing after the type-defaults round-trip.
     */
    private static boolean jarCardVisible(DatabaseType type) {
        if (type == null || type == DatabaseType.CUSTOM) {
            return true;
        }
        return !DriverPresence.isPresent(type.getDriverClassName());
    }

    @PostMapping("/save")
    public String save(@ModelAttribute DatabaseConfig config,
                       @RequestParam(required = false) String rawPassword,
                       RedirectAttributes flash) {
        try {
            service.save(config, rawPassword);
            flash.addFlashAttribute("message", "msg.connection.saved");
            return "redirect:/databases";
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
            // Send the user back to the form rather than losing their input to a list page.
            return config.getId() != null
                    ? "redirect:/databases/" + config.getId() + "/edit"
                    : "redirect:/databases/new";
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        try {
            service.delete(id);
            flash.addFlashAttribute("message", "msg.connection.deleted");
        } catch (IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/databases";
    }
}
