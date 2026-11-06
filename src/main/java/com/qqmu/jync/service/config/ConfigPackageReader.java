package com.qqmu.jync.service.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqmu.jync.dto.config.ConfigPackageDtos;

/**
 * Opens a {@code .jync} package and parses its entries.
 *
 * <p>The envelope is authenticated, so the manifest's hashes are not re-checked here — a package
 * that opens at all is byte-for-byte the one that was sealed. The checks that remain are about the
 * zip: entry names that try to escape, duplicate names, and inflated sizes, because the package is
 * a file someone else produced.
 */
@Component
public class ConfigPackageReader {

    /** Everything a package carries, already parsed. */
    public record Content(ConfigPackageDtos.Meta meta,
                          List<ConfigPackageDtos.DatabaseConfigDto> databaseConfigs,
                          List<ConfigPackageDtos.RedisTargetDto> redisTargets,
                          List<ConfigPackageDtos.MigrationConnectionDto> migrationConnections,
                          List<ConfigPackageDtos.ProjectDto> projects,
                          List<ConfigPackageDtos.EtlBatchDto> etlBatches,
                          List<ConfigPackageDtos.EtlTaskDto> etlTasks,
                          List<ConfigPackageDtos.MigrationTaskDto> migrationTasks,
                          List<ConfigPackageDtos.AiProviderDto> aiProviders,
                          ConfigPackageDtos.AlertConfigDto alertConfig,
                          ConfigPackageDtos.OaAuthConfigDto oaAuthConfig,
                          List<ConfigPackageDtos.AppUserDto> users,
                          List<ConfigPackageDtos.SystemSettingDto> systemSettings,
                          ConfigPackageDtos.State state) {
    }

    private final ConfigPackageCrypto crypto;
    private final ObjectMapper json;

    public ConfigPackageReader(ConfigPackageCrypto crypto, ObjectMapper objectMapper) {
        this.crypto = crypto;
        this.json = objectMapper;
    }

    public Content read(byte[] sealed, char[] passphrase) {
        ConfigPackageCrypto.Opened opened = crypto.decrypt(sealed, passphrase);
        Map<String, byte[]> entries = unzip(opened.zip());

        ConfigPackageDtos.Meta meta = parse(entries, "meta.json",
                new TypeReference<ConfigPackageDtos.Meta>() {
                });
        if (meta.schemaVersion() > ConfigPackageFormat.SCHEMA_VERSION) {
            throw new ConfigPackageException("error.config.import.schemaTooNew");
        }

        ConfigPackageDtos.State state = meta.includeState()
                ? new ConfigPackageDtos.State(
                        parse(entries, "state/sync-progress.json",
                                new TypeReference<List<ConfigPackageDtos.SyncProgressDto>>() {
                                }),
                        parse(entries, "state/cdc-positions.json",
                                new TypeReference<List<ConfigPackageDtos.CdcPositionDto>>() {
                                }),
                        parse(entries, "state/metadata-snapshots.json",
                                new TypeReference<List<ConfigPackageDtos.MetadataSnapshotDto>>() {
                                }),
                        parse(entries, "state/etl-checkpoints.json",
                                new TypeReference<List<ConfigPackageDtos.EtlCheckpointDto>>() {
                                }),
                        parse(entries, "state/alert-states.json",
                                new TypeReference<List<ConfigPackageDtos.AlertStateDto>>() {
                                }))
                : new ConfigPackageDtos.State(List.of(), List.of(), List.of(), List.of(), List.of());

        return new Content(meta,
                parse(entries, "config/database-configs.json",
                        new TypeReference<List<ConfigPackageDtos.DatabaseConfigDto>>() {
                        }),
                parse(entries, "config/redis-targets.json",
                        new TypeReference<List<ConfigPackageDtos.RedisTargetDto>>() {
                        }),
                parse(entries, "config/migration-connections.json",
                        new TypeReference<List<ConfigPackageDtos.MigrationConnectionDto>>() {
                        }),
                parse(entries, "config/projects.json",
                        new TypeReference<List<ConfigPackageDtos.ProjectDto>>() {
                        }),
                parse(entries, "config/etl-batches.json",
                        new TypeReference<List<ConfigPackageDtos.EtlBatchDto>>() {
                        }),
                parse(entries, "config/etl-tasks.json",
                        new TypeReference<List<ConfigPackageDtos.EtlTaskDto>>() {
                        }),
                parse(entries, "config/migration-tasks.json",
                        new TypeReference<List<ConfigPackageDtos.MigrationTaskDto>>() {
                        }),
                parse(entries, "config/ai-providers.json",
                        new TypeReference<List<ConfigPackageDtos.AiProviderDto>>() {
                        }),
                parse(entries, "config/alert-config.json", new TypeReference<ConfigPackageDtos.AlertConfigDto>() {
                }),
                parse(entries, "config/oa-auth-config.json", new TypeReference<ConfigPackageDtos.OaAuthConfigDto>() {
                }),
                parse(entries, "config/app-users.json",
                        new TypeReference<List<ConfigPackageDtos.AppUserDto>>() {
                        }),
                parse(entries, "config/system-settings.json",
                        new TypeReference<List<ConfigPackageDtos.SystemSettingDto>>() {
                        }),
                state);
    }

    private <T> T parse(Map<String, byte[]> entries, String name, TypeReference<T> type) {
        byte[] body = entries.get(name);
        if (body == null) {
            throw new ConfigPackageException("error.config.import.badFormat");
        }
        try {
            return json.readValue(body, type);
        } catch (IOException e) {
            throw new ConfigPackageException("error.config.import.badFormat", e);
        }
    }

    private static Map<String, byte[]> unzip(byte[] zip) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.contains("..") || name.startsWith("/") || name.contains("\\")
                        || entries.containsKey(name)) {
                    throw new ConfigPackageException("error.config.import.badFormat");
                }
                // Bounded while reading: a zip bomb must be stopped before it is all in memory.
                java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
                int read;
                while ((read = in.read(buffer)) > 0) {
                    total += read;
                    if (total > ConfigPackageFormat.MAX_UNCOMPRESSED_BYTES) {
                        throw new ConfigPackageException("error.config.import.fileTooLarge");
                    }
                    body.write(buffer, 0, read);
                }
                entries.put(name, body.toByteArray());
            }
        } catch (IOException e) {
            throw new ConfigPackageException("error.config.import.badFormat", e);
        }
        return entries;
    }
}
