package com.qqmu.jync.model;

import java.time.Instant;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * One page-editable global switch, stored as text.
 *
 * <p>A key/value table rather than a column on a singleton row: a new switch must not need a
 * schema change, and {@code ddl-auto: update} is the only migration mechanism here.
 */
@Entity
@Table(name = "system_setting")
@Getter
@Setter
public class SystemSetting {

    @Id
    @Column(name = "setting_key", length = 64, nullable = false)
    private String key;

    @Column(name = "setting_value", length = 1024)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
