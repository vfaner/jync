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

import com.synctool.model.AiProtocol;
import com.synctool.model.AiProvider;
import com.synctool.service.ai.AiProviderService;

import lombok.extern.slf4j.Slf4j;

/** Server-rendered CRUD for AI providers. */
@Controller
@RequestMapping("/ai")
@Slf4j
public class AiProviderController {

    private final AiProviderService service;

    public AiProviderController(AiProviderService service) {
        this.service = service;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("providers", service.findAll());
        model.addAttribute("assistAvailable", service.isAssistAvailable());
        model.addAttribute("activeNav", "ai");
        return "ai-providers";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        AiProvider provider = new AiProvider();
        provider.setProtocol(AiProtocol.OPENAI);
        provider.setBaseUrl(AiProtocol.OPENAI.getDefaultBaseUrl());
        model.addAttribute("provider", provider);
        model.addAttribute("protocols", AiProtocol.values());
        model.addAttribute("activeNav", "ai");
        return "ai-provider-form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes flash) {
        return service.findById(id).map(provider -> {
            model.addAttribute("provider", provider);
            model.addAttribute("protocols", AiProtocol.values());
            model.addAttribute("activeNav", "ai");
            return "ai-provider-form";
        }).orElseGet(() -> {
            flash.addFlashAttribute("error", "error.ai.provider.missing");
            return "redirect:/ai";
        });
    }

    @PostMapping("/save")
    public String save(@ModelAttribute AiProvider provider,
                       @RequestParam(required = false) String rawApiKey,
                       RedirectAttributes flash) {
        try {
            service.save(provider, rawApiKey);
            flash.addFlashAttribute("message", "msg.ai.saved");
            return "redirect:/ai";
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
            // Back to the form rather than the list, so the user does not lose their input.
            return provider.getId() != null
                    ? "redirect:/ai/" + provider.getId() + "/edit"
                    : "redirect:/ai/new";
        }
    }

    @PostMapping("/{id}/enable")
    public String enable(@PathVariable Long id, RedirectAttributes flash) {
        try {
            AiProvider enabled = service.enable(id);
            // The probe result is already stored; report it so enabling is not silently broken.
            if (Boolean.FALSE.equals(enabled.getLastTestOk())) {
                flash.addFlashAttribute("error", "msg.ai.enabled.untested");
            } else {
                flash.addFlashAttribute("message", "msg.ai.enabled");
            }
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/ai";
    }

    @PostMapping("/{id}/disable")
    public String disable(@PathVariable Long id, RedirectAttributes flash) {
        service.disable(id);
        flash.addFlashAttribute("message", "msg.ai.disabled");
        return "redirect:/ai";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        service.delete(id);
        flash.addFlashAttribute("message", "msg.ai.deleted");
        return "redirect:/ai";
    }
}
