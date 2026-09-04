package com.synctool.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.synctool.service.ai.ConversionAssistService;

import lombok.extern.slf4j.Slf4j;

/**
 * Server-rendered review workflow for objects the mechanical converter cannot handle.
 *
 * <p>The two pages — list and detail — are mounted under the project namespace so a side
 * navigation within the project is natural: the user goes from project detail → conversion
 * list → one object's review page, and the "back" link on each page returns to the project.
 */
@Controller
@RequestMapping("/projects/{projectId}/conversions")
@Slf4j
public class ConversionController {

    private final ConversionAssistService assistService;

    public ConversionController(ConversionAssistService assistService) {
        this.assistService = assistService;
    }

    /**
     * Lists all views and routines selected in the project, with their override state.
     */
    @GetMapping
    public String list(@PathVariable Long projectId, Model model,
                       RedirectAttributes flash) {
        try {
            model.addAttribute("overview", assistService.overview(projectId));
            model.addAttribute("projectId", projectId);
            model.addAttribute("activeNav", "projects");
            return "conversion-list";
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/projects/" + projectId;
        }
    }

    /**
     * Review page for one object: source DDL, mechanical conversion, override editor, AI draft.
     *
     * <p>The {@code :.+} pattern on the name is required, not decorative: Spring otherwise treats
     * a trailing {@code .SOMETHING} as a format suffix and truncates it, which silently breaks
     * every dotted object name a source might legitimately have.
     */
    @GetMapping("/{kind}/{name:.+}")
    public String detail(@PathVariable Long projectId, @PathVariable String kind,
                         @PathVariable String name, Model model, RedirectAttributes flash) {
        try {
            model.addAttribute("detail", assistService.load(projectId, kind, name));
            model.addAttribute("projectId", projectId);
            model.addAttribute("activeNav", "projects");
            return "conversion-detail";
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/projects/" + projectId + "/conversions";
        }
    }

    /**
     * Saves the editor content as a DDL override.
     */
    @PostMapping("/{kind}/{name:.+}/save")
    public String saveOverride(@PathVariable Long projectId, @PathVariable String kind,
                               @PathVariable String name, @RequestParam("sql") String sql,
                               RedirectAttributes flash) {
        try {
            assistService.saveOverride(projectId, kind, name, sql);
            flash.addFlashAttribute("message", "msg.override.saved");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/projects/" + projectId + "/conversions/" + kind + "/" + name;
    }

    /**
     * Removes the override, restoring automatic conversion.
     */
    @PostMapping("/{kind}/{name:.+}/delete-override")
    public String deleteOverride(@PathVariable Long projectId, @PathVariable String kind,
                                 @PathVariable String name, RedirectAttributes flash) {
        try {
            assistService.deleteOverride(projectId, kind + ":" + name);
            flash.addFlashAttribute("message", "msg.override.deleted");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/projects/" + projectId + "/conversions/" + kind + "/" + name;
    }

    /**
     * Removes an override whose object is gone from the source.
     *
     * <p>Separate from {@link #deleteOverride} because there is no object page to return to —
     * the key is all that is left of it, so the key comes in as a parameter and the redirect
     * goes back to the list.
     */
    @PostMapping("/cleanup")
    public String cleanupOrphan(@PathVariable Long projectId,
                                @RequestParam String overrideKey,
                                RedirectAttributes flash) {
        try {
            assistService.deleteOverride(projectId, overrideKey);
            flash.addFlashAttribute("message", "msg.override.deleted");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/projects/" + projectId + "/conversions";
    }
}