package com.qqmu.jync.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.qqmu.jync.dto.OaAuthConfigForm;
import com.qqmu.jync.service.auth.oa.OaAuthConfigService;

/**
 * The administrator's page for the OA sign-in endpoint and its response mapping.
 *
 * <p>The whole page is admin-only (see {@code SecurityConfig.ADMIN_ONLY_GET}): it holds the
 * address Jync sends credentials to, and a viewer has no use for it.
 */
@Controller
@RequestMapping("/oa/settings")
public class OaAuthSettingsController {

    private final OaAuthConfigService configService;

    public OaAuthSettingsController(OaAuthConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public String form(Model model) {
        model.addAttribute("config", configService.load());
        model.addAttribute("requestFormats", com.qqmu.jync.model.OaAuthConfig.RequestFormat.values());
        return "oa-settings";
    }

    @PostMapping
    public String save(@ModelAttribute OaAuthConfigForm form, RedirectAttributes flash) {
        try {
            configService.save(form.toEntity());
            flash.addFlashAttribute("message", "msg.oa.saved");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/oa/settings";
    }
}
