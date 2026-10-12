package com.qqmu.jync.model;

import java.time.Instant;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import javax.persistence.Version;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The persisted binlog position of a CDC project (task book B3). One row per project.
 *
 * <p>Written only after the target database committed the corresponding transaction —
 * data first, position second, the same contract the polling cursors follow. On restart
 * the consumer resumes from here (GTID set preferred, file+pos as the fallback).
 *
 * <p>{@code baselineDone} distinguishes "position recorded, chunked full load still in
 * flight" from "streaming": a crash mid-baseline must redo the whole baseline, never
 * resume half-way (Review Focus #4).
 */
@Entity
@Table(name = "cdc_position",
        uniqueConstraints = @UniqueConstraint(name = "uk_cdc_position_project",
                columnNames = "project_id"))
@Getter
@Setter
@NoArgsConstructor
public class CdcPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "binlog_file", length = 512)
    private String binlogFile;

    @Column(name = "binlog_pos")
    private Long binlogPos;

    /** Executed GTID set to resume from; null when the source runs without GTIDs. */
    @Lob
    @Column(name = "gtid_set")
    private String gtidSet;

    /** The fake replica id this project presented when the position was taken. */
    @Column(name = "server_id")
    private Long serverId;

    /**
     * Oracle LogMiner resume watermark (SCN &lt;= 2^48, safely fits a long). Populated only
     * for {@code LOGMINER_CDC} projects; null for binlog / polling. The binlog columns
     * above are left untouched when this is set — both strategies carry their own columns
     * in the same row, never mixed.
     */
    @Column(name = "scn")
    private Long scn;

    /**
     * Generic LSN resume watermark. Populated only for {@code PG_LOGICAL_CDC} and
     * {@code SQLSERVER_CDC} projects; null otherwise. Stored as a canonical string:
     * <ul>
     *   <li>PostgreSQL: {@code pg_lsn} text form, e.g. {@code "0/167D3F8"}.</li>
     *   <li>SQL Server: {@code __$start_lsn} as a 20-char hex string without prefix
     *       (e.g. {@code "0000002a43d7e1a50030"}).</li>
     * </ul>
     * Both share one column because they never coexist on the same row; the binlog / SCN
     * columns above remain untouched when this is set.
     */
    @Column(name = "lsn", length = 64)
    private String lsn;

    @Getter(AccessLevel.NONE) // the accessor below is isBaselineDone(), per the store contract
    @Column(name = "baseline_done", nullable = false)
    private Boolean baselineDone = false;

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "opt_version")
    private Long optVersion;

    /**
     * Hand-written because Lombok emits {@code isX()} only for primitive {@code boolean},
     * while this flag is a nullable-safe {@code Boolean} column; consumers use
     * {@code isBaselineDone()}.
     */
    public Boolean isBaselineDone() {
        return baselineDone;
    }
}
