package com.qqmu.jync.model;

import java.time.Instant;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Lob;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import javax.persistence.Version;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Intra-table resume point of a chunked initial full load.
 *
 * <p>A full load of a huge table used to be one table-long streamed read: a crash halfway
 * through meant re-reading the whole table on the next cycle, because progress was only
 * recorded once the table completed ({@link SyncProgress#getInitialLoadDone()}). With keyset
 * chunking each completed chunk persists the primary-key values of its last row here, so a
 * restarted load skips every chunk already committed and resumes at the next boundary. The
 * replayed partial chunk is harmless because all data writes are idempotent upserts.
 *
 * <p>Rows are deleted once the table's load finishes; they only ever describe an in-flight
 * load.
 */
@Entity
@Table(name = "chunk_checkpoint",
        uniqueConstraints = @UniqueConstraint(name = "uk_chunk_checkpoint",
                columnNames = {"project_id", "table_name", "chunk_index"}),
        indexes = @Index(name = "idx_chunk_checkpoint_table",
                columnList = "project_id, table_name"))
@Getter
@Setter
@NoArgsConstructor
public class ChunkCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "table_name", nullable = false, length = 256)
    private String tableName;

    /** Zero-based position of the chunk in this load; resume continues at max+1. */
    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    /**
     * Keyset boundary of this completed chunk: the primary-key values of its last row,
     * serialized as a JSON array so composite keys survive intact.
     */
    @Lob
    @Column(name = "last_pk_json")
    private String lastPkJson;

    @Column(name = "rows_copied")
    private Integer rowsCopied = 0;

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "opt_version")
    private Long optVersion;

    public ChunkCheckpoint(Long projectId, String tableName, int chunkIndex,
                           String lastPkJson, int rowsCopied) {
        this.projectId = projectId;
        this.tableName = tableName;
        this.chunkIndex = chunkIndex;
        this.lastPkJson = lastPkJson;
        this.rowsCopied = rowsCopied;
        this.updatedAt = Instant.now();
    }
}
