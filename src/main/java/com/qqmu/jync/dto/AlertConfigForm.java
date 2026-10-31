package com.qqmu.jync.dto;

import java.util.ArrayList;
import java.util.List;

import com.qqmu.jync.model.AlertWebhookFormat;

import lombok.Getter;
import lombok.Setter;

/**
 * What the alerting settings page may submit.
 *
 * <p>The stored webhook URL is a ciphertext the page never shows, so the form carries the plain
 * text only when the administrator typed a new one. A blank field means "keep what is stored";
 * removal needs the explicit checkbox, which keeps an accidental empty save from silently
 * disabling alert delivery.
 */
@Getter
@Setter
public class AlertConfigForm {

    private boolean enabled;
    private String webhookUrl;
    private boolean clearWebhookUrl;
    private AlertWebhookFormat format = AlertWebhookFormat.GENERIC;
    /** {@link com.qqmu.jync.model.AlertCategory} names, as submitted by the checkboxes. */
    private List<String> categories = new ArrayList<>();
    private Integer cooldownMinutes = 30;
}
