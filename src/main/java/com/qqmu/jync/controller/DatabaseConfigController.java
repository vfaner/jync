package com.qqmu.jync.controller;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.connection.DriverPresence;

import lombok.extern.slf4j.Slf4j;

/** Server-rendered CRUD for database connections. */
@Controller
@RequestMapping("/databases")
@Slf4j
public class DatabaseConfigController {

    static final int PAGE_SIZE = 10;

    private final DatabaseConfigService service;

    public DatabaseConfigController(DatabaseConfigService service) {
        this.service = service;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int sourcePage,
                       @RequestParam(defaultValue = "0") int targetPage,
                       Model model) {
        model.addAttribute("sources",
                service.findPageByRole(ConnectionRole.SOURCE, sourcePage, PAGE_SIZE));
        model.addAttribute("targets",
                service.findPageByRole(ConnectionRole.TARGET, targetPage, PAGE_SIZE));
        return "database-configs";
    }

    @GetMapping("/new")
    public String createForm(@RequestParam(required = false) ConnectionRole role, Model model) {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(DatabaseType.MYSQL);
        config.setRole(role != null ? role : ConnectionRole.SOURCE);
        config.setHost("localhost");
        config.setPort(DatabaseType.MYSQL.getDefaultPort());
        model.addAttribute("config", config);
        model.addAttribute("types", DatabaseType.values());
        model.addAttribute("families", FAMILIES);
        addDriverCardAttributes(model, config);
        return "database-config-form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes flash) {
        return service.findById(id).map(config -> {
            model.addAttribute("config", config);
            model.addAttribute("types", DatabaseType.values());
            model.addAttribute("families", FAMILIES);
            addDriverCardAttributes(model, config);
            return "database-config-form";
        }).orElseGet(() -> {
            flash.addFlashAttribute("error", "error.connection.missing");
            return "redirect:/databases";
        });
    }

    /**
     * Families offered for a CUSTOM connection, GENERIC first: an unset family must display as
     * generic (a browser preselects the first option), which is also how it resolves at sync
     * time, so opening the form and saving without touching it changes no behaviour.
     */
    private static final List<DatabaseType.DialectFamily> FAMILIES = List.of(
            DatabaseType.DialectFamily.GENERIC,
            DatabaseType.DialectFamily.ORACLE,
            DatabaseType.DialectFamily.MYSQL,
            DatabaseType.DialectFamily.POSTGRES,
            DatabaseType.DialectFamily.SQLSERVER,
            DatabaseType.DialectFamily.DB2);

    private static void addDriverCardAttributes(Model model, DatabaseConfig config) {
        model.addAttribute("driverCardAvailable", driverCardAvailable(config.getType()));
        model.addAttribute("driverCardRequired", driverCardRequired(config.getType()));
        model.addAttribute("driverCardEnabled", driverCardEnabled(config));
    }

    /**
     * The driver card exists for CUSTOM and for commercial / 信创 presets; a standard product
     * (MySQL, PostgreSQL, Oracle, ...) never shows it. A preset whose driver is not on the
     * classpath keeps the card regardless: without a jar it cannot connect at all.
     */
    private static boolean driverCardAvailable(DatabaseType type) {
        if (type == null || type == DatabaseType.CUSTOM || type.supportsVendorDriver()) {
            return true;
        }
        return !DriverPresence.isPresent(type.getDriverClassName());
    }

    /**
     * Types that cannot connect without a user-supplied driver: CUSTOM (no preset at all) and
     * presets whose driver class is not on the classpath (GBase, Oscar, Huawei GaussDB, ...).
     * Their card is required and open on the first paint, with no switch.
     */
    private static boolean driverCardRequired(DatabaseType type) {
        if (type == null || type == DatabaseType.CUSTOM) {
            return true;
        }
        return !DriverPresence.isPresent(type.getDriverClassName());
    }

    /**
     * A bundled-driver preset (GoldenDB, TiDB, MySQL, ...) keeps the override controls locked
     * until the form's vendor-driver switch is on. A saved override of any of the three fields
     * must reopen them, otherwise an edit page would show the stored values but refuse to
     * submit them — silently switching the connection back to the bundled driver.
     */
    private static boolean driverCardEnabled(DatabaseConfig config) {
        return driverCardRequired(config.getType())
                || StringUtils.hasText(config.getCustomUrl())
                || StringUtils.hasText(config.getCustomDriver())
                || StringUtils.hasText(config.getCustomJarPath());
    }

    @PostMapping("/save")
    public String save(@ModelAttribute DatabaseConfig config,
                       @RequestParam(required = false) String rawPassword,
                       @RequestParam(required = false) String rawMinerPassword,
                       RedirectAttributes flash) {
        try {
            service.save(config, rawPassword, rawMinerPassword);
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
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/databases";
    }
}
