package com.qqmu.jync.model;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * The alerting settings singleton: one row, {@code id = 1}.
 *
 * <p>Deliberately small. The page holds only what an operator must decide — on/off, where to
 * send, in which wire format, which categories, and how often to repeat a still-active fault.
 * The thresholds that decide <em>when</em> a fault exists stay with the subsystem that already
 * owns them ({@code sync.stuck-threshold-ms}, the PG retained-WAL ceiling, the audit interval).
 */
@Entity
@Table(name = "alert_config")
@Getter
@Setter
public class AlertConfig {

    @Id
    private Long id;

    @Column(nullable = false)
    private boolean enabled;

    /** Ciphertext (see {@code CryptoUtil}); never rendered back into the form. */
    @Lob
    @Column(name = "webhook_url")
    private String webhookUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "webhook_format", nullable = false, length = 16)
    private AlertWebhookFormat format = AlertWebhookFormat.GENERIC;

    /** Comma-separated {@link AlertCategory} names; null on a row written before the column existed. */
    @Column(length = 512)
    private String categories;

    @Column(name = "cooldown_minutes", nullable = false)
    private Integer cooldownMinutes = 30;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * The selected categories.
     *
     * <p>Null or blank means "all": a row written before categories existed must not silently
     * alert on nothing. Unknown names in a stored CSV are ignored, so a category removed by a
     * later version does not break the page.
     */
    public Set<AlertCategory> enabledCategorySet() {
        if (categories == null || categories.isBlank()) {
            return EnumSet.allOf(AlertCategory.class);
        }
        Set<AlertCategory> selected = EnumSet.noneOf(AlertCategory.class);
        for (String name : categories.split(",")) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                selected.add(AlertCategory.valueOf(trimmed));
            } catch (IllegalArgumentException ignored) {
                // A name stored by a newer version: skip it rather than fail every page load.
            }
        }
        return selected;
    }

    public boolean isCategoryEnabled(AlertCategory category) {
        return enabledCategorySet().contains(category);
    }

    /** The format, defaulted for a pre-column row. */
    public AlertWebhookFormat formatOrDefault() {
        return format == null ? AlertWebhookFormat.GENERIC : format;
    }

    /** The cooldown, defaulted for a pre-column row. */
    public int cooldownMinutesOrDefault() {
        return cooldownMinutes == null ? 30 : cooldownMinutes;
    }
}
