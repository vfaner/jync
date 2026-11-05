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

import com.qqmu.jync.dto.RedisTargetForm;
import com.qqmu.jync.service.projection.redis.RedisTargetService;
import com.qqmu.jync.service.projection.redis.VerifyResult;

/**
 * Server-rendered CRUD for Redis cache targets.
 *
 * <p>Its own controller and templates: the JDBC connection pages only gain a tab link, and
 * nothing here touches {@code DatabaseConfig}.
 */
@Controller
@RequestMapping("/redis/targets")
public class RedisTargetController {

    private final RedisTargetService service;

    public RedisTargetController(RedisTargetService service) {
        this.service = service;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("targets", service.findAll());
        return "redis-targets";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("form", new RedisTargetForm());
        return "redis-target-form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes flash) {
        try {
            model.addAttribute("form", service.formFor(id));
            return "redis-target-form";
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/redis/targets";
        }
    }

    @PostMapping("/save")
    public String save(@ModelAttribute RedisTargetForm form,
                       @RequestParam(required = false) String rawPassword,
                       RedirectAttributes flash) {
        try {
            service.save(form, rawPassword);
            flash.addFlashAttribute("message", "msg.redis.target.saved");
            return "redirect:/redis/targets";
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return form.getId() != null
                    ? "redirect:/redis/targets/" + form.getId() + "/edit"
                    : "redirect:/redis/targets/new";
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        try {
            service.delete(id);
            flash.addFlashAttribute("message", "msg.redis.target.deleted");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/redis/targets";
    }

    /**
     * Probes the values in the form (or the stored credential when the password box is empty).
     * The result is flashed back onto the form page because this is a server-rendered flow.
     */
    @PostMapping("/verify")
    public String verify(@ModelAttribute RedisTargetForm form,
                         @RequestParam(required = false) String rawPassword,
                         Model model) {
        VerifyResult result = service.verify(form, rawPassword);
        model.addAttribute("form", form);
        if (result.ok()) {
            model.addAttribute("message", result.messageKey());
        } else {
            model.addAttribute("error", result.messageKey());
            if (result.detail() != null) {
                model.addAttribute("errorDetail", result.detail());
            }
        }
        // Render the form again instead of redirecting: a redirect would drop the values the
        // operator just typed, and the test button exists to diagnose those values.
        return "redis-target-form";
    }
}
