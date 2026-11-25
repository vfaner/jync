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

/**
 * Resume point of one fan-out target. A fan-out project has one source and N targets; the
 * project-level {@code cdc_position} is no longer enough because each target consumes and
 * acknowledges the shared stream independently.
 */
@Getter
@Setter
@Entity
@Table(name = "fanout_position",
        uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "target_db_id"}))
public class FanoutPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "target_db_id", nullable = false)
    private Long targetDbId;

    @Column(name = "binlog_file", length = 255)
    private String binlogFile;

    @Column(name = "binlog_pos")
    private Long binlogPos;

    @Column(name = "gtid_set", length = 4096)
    private String gtidSet;

    /** PostgreSQL / SQL Server LSN, or the generic position token. */
    @Column(name = "lsn", length = 512)
    private String lsn;

    /** Oracle / Dameng LogMiner SCN. */
    @Column(name = "scn")
    private Long scn;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
