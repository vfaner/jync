package com.qqmu.jync.service.metadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqmu.jync.model.MetadataSnapshot;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.repository.MetadataSnapshotRepository;

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

    /**
     * In-memory copy of what is in the metadata store, keyed by (project, type, name).
     *
     * <p>The sync cycle re-saves every table's snapshot and re-reads all snapshots of a type on
     * every poll, and nearly always nothing has changed. Remembering the content hash (and the
     * deserialized object) lets {@link #save} skip the database round-trip and lets
     * {@link #loadAll} skip re-reading and re-parsing unchanged JSON. The database stays the
     * source of truth: every loadAll reads the name-and-hash list, and any hash mismatch falls
     * back to the stored row — so a snapshot written or deleted by another path is never
     * masked by this cache. Entries
     * are only ever written by the holder of the project's sync lock, so no cross-thread
     * invalidation is needed beyond the explicit evictions in {@link #delete} and
     * {@link #deleteAllForProject}.
     *
     * <p>Entries are published inside {@link #save}'s transaction, before it commits, so a
     * commit failure would leave the cache briefly ahead of the store. That heals itself:
     * the next {@link #loadAll} reads the real rows, and any hash mismatch or missing name
     * overwrites or evicts the stale entry before anything can act on it.
     */
    private final Map<SnapshotKey, CachedSnapshot> cache = new ConcurrentHashMap<>();

    private record SnapshotKey(Long projectId, ObjectType type, String name) {
    }

    private record CachedSnapshot(String contentHash, Object value) {
    }

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

    /**
     * All snapshots of one type for a project, keyed by upper-cased object name.
     *
     * <p>Reads names and content hashes only; an object's stored JSON is fetched (and
     * parsed) solely when the cache does not already hold that exact hash, so a steady
     * poll cycle touches no snapshot blob at all.
     */
    public <T> Map<String, T> loadAll(Long projectId, ObjectType type, Class<T> clazz) {
        Map<String, T> result = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (MetadataSnapshotRepository.NameAndHash row
                : repository.findNameAndHashByProjectIdAndObjectType(projectId, type)) {
            String name = row.getObjectName();
            seen.add(name);
            SnapshotKey key = new SnapshotKey(projectId, type, name);
            // A cache entry whose hash matches the stored row is still current — reuse the
            // already-parsed object instead of paying for a row read and Jackson every poll.
            // The stored hash can be null (rows written before v1.3.0 have no content_hash),
            // so neither side may lead an equals() unguarded.
            CachedSnapshot cached = cache.get(key);
            if (cached != null && cached.contentHash() != null
                    && cached.contentHash().equals(row.getContentHash())
                    && clazz.isInstance(cached.value())) {
                result.put(name.toUpperCase(), clazz.cast(cached.value()));
                continue;
            }
            // Hash differs or was never cached: this one object's blob is worth reading.
            load(projectId, type, name, clazz).ifPresent(value -> {
                result.put(name.toUpperCase(), value);
                // A legacy row without a hash must not enter the cache: null would
                // poison every later hit comparison. The row is still returned above,
                // and the next save() backfills the hash, after which caching resumes.
                if (row.getContentHash() != null) {
                    cache.put(key, new CachedSnapshot(row.getContentHash(), value));
                }
            });
        }
        // Names that vanished from the store (deleted through any path) must not linger here.
        evictAbsent(projectId, type, seen);
        return result;
    }

    private void evictAbsent(Long projectId, ObjectType type, Set<String> seen) {
        Iterator<SnapshotKey> it = cache.keySet().iterator();
        while (it.hasNext()) {
            SnapshotKey key = it.next();
            if (key.projectId().equals(projectId) && key.type() == type
                    && !seen.contains(key.name())) {
                it.remove();
            }
        }
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

        // The cache remembers what this JVM last wrote or read. When it agrees with what we are
        // about to write, the stored row already holds exactly this content and even the read
        // that would confirm it can be skipped — which is the common case on an idle database,
        // where every table is re-saved unchanged on every poll.
        SnapshotKey key = new SnapshotKey(projectId, type, name);
        CachedSnapshot cached = cache.get(key);
        // The computed hash leads the comparison: sha256() never returns null, so a cache
        // entry without one degrades to a miss instead of an NPE.
        if (cached != null && hash.equals(cached.contentHash())) {
            return;
        }

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
        // every poll of an idle database. The cached value is left null: the caller may keep
        // mutating the definition it handed in, so the parsed copy is rebuilt from the stored
        // JSON on the next loadAll instead of aliasing a live object.
        if (hash.equals(snapshot.getContentHash())) {
            cache.put(key, new CachedSnapshot(hash, null));
            return;
        }
        snapshot.setSnapshotJson(json);
        snapshot.setContentHash(hash);
        repository.save(snapshot);
        cache.put(key, new CachedSnapshot(hash, null));
    }

    @Transactional
    public void delete(Long projectId, ObjectType type, String name) {
        repository.deleteByProjectIdAndObjectTypeAndObjectName(projectId, type, name);
        cache.remove(new SnapshotKey(projectId, type, name));
    }

    @Transactional
    public void deleteAllForProject(Long projectId) {
        repository.deleteByProjectId(projectId);
        cache.keySet().removeIf(key -> key.projectId().equals(projectId));
        log.info("Cleared all metadata snapshots for project {}", projectId);
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
