package com.qqmu.jync.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

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
    @Column(name = "gtid_set", length = Integer.MAX_VALUE)
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

    /**
     * Per-table column layouts as of the last applied MySQL binlog transaction, encoded by
     * {@code CdcPositionStore}: {@code <keyLength>:<database>.<table><sha256-hex>} repeated.
     *
     * <p>Binlog row images are positional. On restart the consumer reads the catalog as it is
     * NOW, which is not necessarily the schema those images were written with; without this
     * record a DDL that ran while the consumer was down silently puts values in the wrong
     * columns. A mismatch parks the project and points at the re-baseline action. Null for
     * every other capture strategy and for a position written before this column existed.
     */
    @Lob
    @Column(name = "schema_signatures", length = Integer.MAX_VALUE)
    private String schemaSignatures;

    /**
     * The Redis key-space contract ({@code valueFormat|keyPrefix}) the cache was last built or
     * rebuilt with; null for JDBC targets and for rows written before this column existed.
     *
     * <p>Existing keys have a type. Flipping JSON &lt;-&gt; HASH leaves them in the other type,
     * and the next baseline or CDC write answers {@code WRONGTYPE} instead of overwriting — the
     * consumer parks and the cache stays as it was. Remembering the contract lets start and
     * rebuild refuse that mismatch with an actionable message (rebuild with the prefix sweep)
     * instead of discovering it one failed key at a time.
     */
    @Lob
    @Column(name = "projection_signature", length = Integer.MAX_VALUE)
    private String projectionSignature;

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
