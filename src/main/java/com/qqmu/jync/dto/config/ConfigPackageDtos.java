package com.qqmu.jync.dto.config;

import java.time.Instant;
import java.util.List;

import com.qqmu.jync.model.AiProtocol;
import com.qqmu.jync.model.AiReasoningEffort;
import com.qqmu.jync.model.AlertCategory;
import com.qqmu.jync.model.AlertWebhookFormat;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.model.UserRole;

/**
 * The JSON shapes inside a configuration package.
 *
 * <p>Every record is a flat projection of an entity with the identity and bookkeeping columns
 * removed: {@code id} is the source database's, and reusing it would either collide on import or
 * silently point at the wrong row, so the importer builds its own ids and remaps references.
 * {@code createdAt}/{@code updatedAt}/{@code optVersion} and the {@code lastRun*}/{@code lastTest*}
 * columns describe the old deployment's history, not its configuration.
 *
 * <p>Encrypted columns keep their stored {@code enc:} value. The importer decrypts them with the
 * source key recorded in {@link Meta#sourceCrypto()} and re-encrypts with its own.
 */
public final class ConfigPackageDtos {

    private ConfigPackageDtos() {
    }

    /** What the exporter knew about itself and the key its ciphertext was written with. */
    public record Meta(int schemaVersion,
                       String appVersion,
                       Instant exportedAt,
                       boolean includeState,
                       SourceCrypto sourceCrypto,
                       Counts counts) {
    }

    /**
     * The exporting deployment's {@code sync.crypto-password}/{@code sync.crypto-salt}.
     *
     * <p>Without these, the {@code enc:} values in the package are unreadable on the target: a
     * non-H2 metadata database never loads {@code data/jync.key}, so the target runs on the
     * shipped default key. Carrying the source key inside the passphrase-sealed envelope is what
     * makes the migration possible at all.
     */
    public record SourceCrypto(String password, String salt, boolean usingDefaultKey) {
    }

    public record Counts(int databases,
                         int redisTargets,
                         int migrationConnections,
                         int projects,
                         int etlBatches,
                         int etlTasks,
                         int migrationTasks,
                         int aiProviders,
                         int users,
                         int systemSettings) {
    }

    public record DatabaseConfigDto(Long id,
                                    String name,
                                    DatabaseType type,
                                    DatabaseType.DialectFamily dialectFamily,
                                    ConnectionRole role,
                                    String host,
                                    Integer port,
                                    String databaseName,
                                    String schemaName,
                                    String username,
                                    String password,
                                    String minerUsername,
                                    String minerPassword,
                                    String customUrl,
                                    String customDriver,
                                    String customJarPath,
                                    String extraParams) {
    }

    public record RedisTargetDto(Long id,
                                 String name,
                                 String host,
                                 Integer port,
                                 Integer databaseIndex,
                                 String username,
                                 String passwordEnc,
                                 boolean tlsEnabled,
                                 boolean verifyTls,
                                 boolean clusterMode,
                                 int connectTimeoutMs,
                                 int commandTimeoutMs,
                                 String description) {
    }

    public record MigrationConnectionDto(Long id,
                                         String name,
                                         MigrationConnectionKind kind,
                                         String baseUrl,
                                         String databaseName,
                                         String schemaName,
                                         MigrationConnectionAuthType authType,
                                         String username,
                                         String passwordEnc,
                                         String apiKeyEnc,
                                         String extraParams,
                                         boolean verifyTls,
                                         int connectTimeoutMs,
                                         int requestTimeoutMs,
                                         String description) {
    }

    public record ProjectDto(Long id,
                             String name,
                             String description,
                             Long sourceDbId,
                             Long targetDbId,
                             SyncTargetKind targetKind,
                             Long targetRedisId,
                             String syncConfig,
                             Boolean enabled) {
    }

    public record EtlBatchDto(Long id,
                              String name,
                              String description,
                              Long sourceDbId,
                              Long targetDbId,
                              String cronExpression,
                              Boolean enabled,
                              Integer timeoutSeconds) {
    }

    public record EtlTaskDto(Long id,
                             String name,
                             String description,
                             Long sourceDbId,
                             Long targetDbId,
                             Long batchId,
                             String sourceSql,
                             String sourceCollection,
                             String sourceFilter,
                             String preSql,
                             String postSql,
                             String targetTable,
                             EtlWriteMode writeMode,
                             String rangeCondition,
                             String resumeKeyColumn,
                             String cronExpression,
                             Boolean enabled,
                             Integer timeoutSeconds) {
    }

    public record MigrationTaskDto(Long id,
                                   String name,
                                   String description,
                                   MigrationType type,
                                   Long sourceConnectionId,
                                   Long targetConnectionId,
                                   String optionsJson,
                                   String cronExpression,
                                   Boolean enabled,
                                   Integer timeoutSeconds) {
    }

    public record AiProviderDto(Long id,
                                String name,
                                AiProtocol protocol,
                                String baseUrl,
                                String model,
                                String apiKey,
                                boolean enabled,
                                Integer maxTokens,
                                AiReasoningEffort reasoningEffort,
                                Integer timeoutSeconds) {
    }

    public record AlertConfigDto(boolean enabled,
                                 String webhookUrl,
                                 AlertWebhookFormat format,
                                 String categories,
                                 Integer cooldownMinutes) {
    }

    public record OaAuthConfigDto(boolean enabled,
                                  OaAuthConfig.Mode mode,
                                  String adminUsers,
                                  String loginEntryUrl,
                                  String logoutRedirectUrl,
                                  Boolean localLoginEnabled,
                                  String userHeader,
                                  String adminHeader,
                                  String secretHeaderName,
                                  String secretHeaderValue,
                                  String validateUrl,
                                  OaAuthConfig.ValidateMethod validateMethod,
                                  String ticketParam,
                                  String loginUrl,
                                  OaAuthConfig.RequestFormat requestFormat,
                                  String usernameField,
                                  String passwordField,
                                  String successField,
                                  String successValue,
                                  String userField,
                                  String adminField,
                                  String adminValue,
                                  Integer timeoutMs,
                                  boolean allowInsecureHttp) {
    }

    public record AppUserDto(String username, String passwordHash, UserRole role) {
    }

    public record SystemSettingDto(String key, String value) {
    }

    public record SyncProgressDto(Long projectId,
                                  ObjectType objectType,
                                  String objectName,
                                  String lastSyncValue,
                                  String cursorColumn,
                                  String cursorStrategy,
                                  Boolean resumableLoad,
                                  Boolean initialLoadDone,
                                  Long rowsSyncedTotal,
                                  Instant lastSyncTime) {
    }

    public record CdcPositionDto(Long projectId,
                                 String binlogFile,
                                 Long binlogPos,
                                 String gtidSet,
                                 Long serverId,
                                 Long scn,
                                 String lsn,
                                 String schemaSignatures,
                                 String projectionSignature,
                                 Boolean baselineDone) {
    }

    public record MetadataSnapshotDto(Long projectId,
                                      ObjectType objectType,
                                      String objectName,
                                      String snapshotJson,
                                      String contentHash) {
    }

    public record EtlCheckpointDto(Long taskId, Integer keyType, String keyValue) {
    }

    public record AlertStateDto(AlertCategory category,
                                Long projectId,
                                String objectKey,
                                String subject,
                                boolean active,
                                Instant firstSeenAt,
                                Instant lastNotifiedAt,
                                Instant lastEventAt) {
    }

    /** Convenience for callers that hold the five optional lists. */
    public record State(List<SyncProgressDto> syncProgress,
                        List<CdcPositionDto> cdcPositions,
                        List<MetadataSnapshotDto> metadataSnapshots,
                        List<EtlCheckpointDto> etlCheckpoints,
                        List<AlertStateDto> alertStates) {
    }
}
