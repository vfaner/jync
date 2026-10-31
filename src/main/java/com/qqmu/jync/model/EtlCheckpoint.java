package com.qqmu.jync.model;

import java.time.Instant;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.Setter;

/** The last committed resume position of one ETL task. */
@Entity
@Table(name = "etl_checkpoint",
        uniqueConstraints = @UniqueConstraint(name = "uk_etl_checkpoint_task", columnNames = "task_id"))
@Getter
@Setter
public class EtlCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** {@link java.sql.Types} constant of the resume key, used to bind it back. */
    @Column(name = "key_type", nullable = false)
    private Integer keyType;

    @Column(name = "key_value", nullable = false, length = 1024)
    private String keyValue;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }
}
