package com.qqmu.jync.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * Durable marker for a SQL Server target whose constraints this node disabled for a data
 * phase. The toggle is persistent table state, so a SIGKILL between disable and restore
 * would otherwise leave foreign keys off with no record of which tables to repair.
 */
@Entity
@Table(name = "constraint_state")
@Getter
@Setter
public class ConstraintState {

    @Id
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "target_db_id", nullable = false)
    private Long targetDbId;

    @Column(name = "schema_name", length = 256)
    private String schemaName;

    /** One target table name per line; restored in the same order they were disabled. */
    @Lob
    @Column(name = "tables", nullable = false, length = Integer.MAX_VALUE)
    private String tables;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
