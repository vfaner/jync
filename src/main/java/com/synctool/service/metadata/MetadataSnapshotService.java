package com.synctool.service.metadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synctool.model.MetadataSnapshot;
import com.synctool.model.ObjectType;
import com.synctool.repository.MetadataSnapshotRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Persists and retrieves metadata snapshots.
 *
 * <p>Snapshots are the durable memory that lets structural change detection work across
 * restarts: a DDL change applied to the source while the tool was stopped is discovered on
 * the next run because the stored snapshot still describes the old shape.
 */
@Service
@Slf4j
public class MetadataSnapshotService {

    private final MetadataSnapshotRepository repository;
    private final ObjectMapper objectMapper;

    public MetadataSnapshotService(MetadataSnapshotRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** Deserializes the stored snapshot for one object, if any. */
    public <T> Optional<T> load(Long projectId, ObjectType type, String name, Class<T> clazz) {
        return repository.findByProjectIdAndObjectTypeAndObjectName(projectId, type, name)
                .map(MetadataSnapshot::getSnapshotJson)
                .flatMap(json -> deserialize(json, clazz, name));
    }

    private <T> Optional<T> deserialize(String json, Class<T> clazz, String name) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, clazz));
        } catch (Exception e) {
            // A snapshot written by an incompatible version: drop it and treat the object as
            // new, which is safe because the sync itself is idempotent.
            log.warn("Discarding unreadable snapshot for {}: {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    /** All snapshots of one type for a project, keyed by upper-cased object name. */
    public <T> Map<String, T> loadAll(Long projectId, ObjectType type, Class<T> clazz) {
        Map<String, T> result = new HashMap<>();
        for (MetadataSnapshot snapshot : repository.findByProjectIdAndObjectType(projectId, type)) {
            deserialize(snapshot.getSnapshotJson(), clazz, snapshot.getObjectName())
                    .ifPresent(value -> result.put(snapshot.getObjectName().toUpperCase(), value));
        }
        return result;
    }

    /** Names of every snapshotted object of a type, upper-cased. */
    public java.util.Set<String> snapshottedNames(Long projectId, ObjectType type) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (MetadataSnapshot s : repository.findByProjectIdAndObjectType(projectId, type)) {
            names.add(s.getObjectName().toUpperCase());
        }
        return names;
    }

    /**
     * Writes or updates the snapshot for one object.
     *
     * <p>Called only after the corresponding change has been applied to the target, so a
     * crash mid-sync leaves the old snapshot in place and the change is retried.
     */
    @Transactional
    public void save(Long projectId, ObjectType type, String name, Object definition) {
        String json;
        try {
            json = objectMapper.writeValueAsString(definition);
        } catch (Exception e) {
            log.error("Could not serialize snapshot for {} {}: {}", type, name, e.getMessage());
            return;
        }
        String hash = sha256(json);

        MetadataSnapshot snapshot = repository
                .findByProjectIdAndObjectTypeAndObjectName(projectId, type, name)
                .orElseGet(() -> {
                    MetadataSnapshot s = new MetadataSnapshot();
                    s.setProjectId(projectId);
                    s.setObjectType(type);
                    s.setObjectName(name);
                    return s;
                });

        // Skip the write when nothing changed, to avoid churning the metadata store on
        // every poll of an idle database.
        if (hash.equals(snapshot.getContentHash())) {
            return;
        }
        snapshot.setSnapshotJson(json);
        snapshot.setContentHash(hash);
        snapshot.setCapturedAt(Instant.now());
        repository.save(snapshot);
    }

    @Transactional
    public void delete(Long projectId, ObjectType type, String name) {
        repository.deleteByProjectIdAndObjectTypeAndObjectName(projectId, type, name);
    }

    @Transactional
    public void deleteAllForProject(Long projectId) {
        repository.deleteByProjectId(projectId);
        log.info("Cleared all metadata snapshots for project {}", projectId);
    }

    public List<MetadataSnapshot> findByProject(Long projectId) {
        return repository.findByProjectId(projectId);
    }

    public long countForProject(Long projectId, ObjectType type) {
        return repository.findByProjectIdAndObjectType(projectId, type).size();
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform; treat absence as fatal misconfiguration.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
