package com.qqmu.jync.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.qqmu.jync.dto.AlertConfigForm;
import com.qqmu.jync.model.AlertCategory;
import com.qqmu.jync.model.AlertConfig;
import com.qqmu.jync.model.AlertWebhookFormat;
import com.qqmu.jync.service.alert.AlertConfigService;
import com.qqmu.jync.service.alert.AlertNotifier;

import lombok.extern.slf4j.Slf4j;

/**
 * The administrator's page for webhook alerting.
 *
 * <p>Admin-only end to end ({@code SecurityConfig.ADMIN_ONLY_GET}): the webhook URL is a bearer
 * credential. It is stored encrypted and rendered back only as a masked tail; a blank field on
 * save means "keep the stored one".
 */
@Controller
@RequestMapping("/alert-settings")
@Slf4j
public class AlertSettingsController {

    private final AlertConfigService configService;
    private final AlertNotifier notifier;
    private final MessageSource messages;

    public AlertSettingsController(AlertConfigService configService,
                                   AlertNotifier notifier,
                                   MessageSource messages) {
        this.configService = configService;
        this.notifier = notifier;
        this.messages = messages;
    }

    @GetMapping
    public String form(Model model) {
        AlertConfig config = configService.load();
        AlertConfigForm form = new AlertConfigForm();
        form.setEnabled(config.isEnabled());
        form.setFormat(config.formatOrDefault());
        form.setCooldownMinutes(config.cooldownMinutesOrDefault());
        List<String> selected = new ArrayList<>();
        for (AlertCategory category : config.enabledCategorySet()) {
            selected.add(category.name());
        }
        form.setCategories(selected);
        addPageModel(model, form);
        return "alert-settings";
    }

    @PostMapping
    public String save(@ModelAttribute("form") AlertConfigForm form,
                       Model model, RedirectAttributes flash) {
        try {
            configService.save(form);
            flash.addFlashAttribute("message", "alert.saved");
            return "redirect:/alert-settings";
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Keep what was typed on screen (the URL field itself is never rendered back) and
            // let the template resolve the i18n key. Nothing was written.
            model.addAttribute("formError", e.getMessage());
            addPageModel(model, form);
            return "alert-settings";
        }
    }

    /** One real message to the configured endpoint; the URL is never echoed back. */
    @PostMapping("/test")
    public String test(@ModelAttribute("form") AlertConfigForm form,
                       RedirectAttributes flash) {
        String url = StringUtils.hasText(form.getWebhookUrl())
                ? form.getWebhookUrl().trim() : storedUrl();
        if (url == null) {
            flash.addFlashAttribute("message", "error.alert.url.required");
            return "redirect:/alert-settings";
        }
        AlertWebhookFormat format = form.getFormat() == null
                ? AlertWebhookFormat.GENERIC : form.getFormat();
        boolean sent = notifier.sendNow(url, format,
                messages.getMessage("alert.webhook.test", null, locale()));
        flash.addFlashAttribute("message", sent ? "alert.testSent" : "alert.testFailed");
        return "redirect:/alert-settings";
    }

    private String storedUrl() {
        try {
            return configService.webhookUrlOrNull();
        } catch (RuntimeException e) {
            // An undecryptable stored URL behaves like none: the form asks for a new one.
            log.debug("Stored webhook URL cannot be read: {}", e.toString());
            return null;
        }
    }

    private void addPageModel(Model model, AlertConfigForm form) {
        model.addAttribute("form", form);
        model.addAttribute("categories", AlertCategory.values());
        model.addAttribute("formats", AlertWebhookFormat.values());
        String masked = configService.maskedUrl();
        model.addAttribute("maskedUrl", masked);
        model.addAttribute("urlSet", masked != null);
    }

    private static Locale locale() {
        return LocaleContextHolder.getLocale();
    }
}
