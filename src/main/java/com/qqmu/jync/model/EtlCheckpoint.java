package com.qqmu.jync.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

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
