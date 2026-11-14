package com.qqmu.jync.service.cdc;

import java.math.BigInteger;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.repository.CdcPositionRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Transactional gateway for {@link CdcPosition}, mirroring the SyncLockStore pattern:
 * a separate bean so REQUIRES_NEW actually proxies, letting the CDC consumer persist
 * positions from its own long-running thread without an ambient transaction.
 */
@Service
@Slf4j
public class CdcPositionStore {

    /**
     * How long a committed transaction's position may wait before it reaches the metadata
     * database. Every source transaction used to buy one SELECT + one UPDATE in its own
     * transaction on the consumer's critical path; a busy stream spent more time writing
     * its own progress than applying changes. Within this window the newest position is
     * kept in memory (and served to readers), then written once.
     *
     * <p>Losing the window to a hard kill only replays up to a second of already committed
     * transactions, which the idempotent target writes absorb; a graceful stop flushes it
     * ({@link #flush}), so an ordinary restart loses nothing.
     */
    static final long ADVANCE_PERSIST_INTERVAL_MS = 1_000L;

    private final CdcPositionRepository repository;
    private final long advancePersistIntervalMs;
    /** Test seam for the throttle window. */
    private LongSupplier clock = System::currentTimeMillis;
    /** Newest committed but not yet persisted position per project. */
    private final Map<Long, Pending> pendingByProject = new ConcurrentHashMap<>();

    @Autowired
    public CdcPositionStore(CdcPositionRepository repository) {
        this(repository, ADVANCE_PERSIST_INTERVAL_MS);
    }

    /** Tests pin the window (0 = persist on every advance) and the clock. */
    CdcPositionStore(CdcPositionRepository repository, long advancePersistIntervalMs) {
        this(repository, advancePersistIntervalMs, System::currentTimeMillis);
    }

    CdcPositionStore(CdcPositionRepository repository, long advancePersistIntervalMs,
                     LongSupplier clock) {
        this.repository = repository;
        this.advancePersistIntervalMs = advancePersistIntervalMs;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<CdcPosition> find(Long projectId) {
        Optional<CdcPosition> stored = repository.findByProjectId(projectId);
        Pending pending = pendingByProject.get(projectId);
        if (!stored.isPresent() || pending == null) {
            return stored;
        }
        synchronized (pending) {
            return pending.dirty ? Optional.of(pending.overlay(stored.get())) : stored;
        }
    }

    /**
     * Writes the position buffered by the throttle, if any. Consumers call this when a
     * cycle ends and when the run stops, so a graceful exit never leaves the newest
     * committed position only in memory.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void flush(Long projectId) {
        Pending pending = pendingByProject.get(projectId);
        if (pending == null) {
            return;
        }
        synchronized (pending) {
            if (!pending.dirty) {
                return;
            }
            try {
                persist(projectId, pending, clock.getAsLong());
            } catch (RuntimeException e) {
                // The source transaction is already committed on the target and the buffer
                // survives, so a failed flush must not fail the cycle: it retries at the
                // next advance or cycle boundary. Losing it entirely only replays an
                // idempotent window after a restart.
                log.warn("CDC position for project {} could not be flushed ({}); it is"
                        + " retried at the next advance", projectId, e.getMessage());
            }
        }
    }

    /**
     * The persisted per-table layouts, decoded. Empty when the project has no position row
     * or the row predates the column — the precheck reports that as "no layout recorded"
     * rather than treating every table as drifted.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Map<String, String> layoutSignatures(Long projectId) {
        return find(projectId)
                .map(position -> decodeLayoutSignatures(position.getSchemaSignatures()))
                .orElseGet(java.util.Collections::emptyMap);
    }

    /** Upsert the start-of-baseline position; baselineDone resets to false. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CdcPosition recordBaselineStart(Long projectId, String file, long pos,
                                           String gtidSet, long serverId) {
        discard(projectId); // the baseline replaces whatever was buffered
        CdcPosition position = repository.findByProjectId(projectId).orElseGet(() -> {
            CdcPosition created = new CdcPosition();
            created.setProjectId(projectId);
            return created;
        });
        position.setBinlogFile(file);
        position.setBinlogPos(pos);
        position.setGtidSet(gtidSet);
        position.setServerId(serverId);
        // A baseline re-reads every table: whatever layouts the interrupted run recorded no
        // longer describe the snapshot the next stream will decode.
        position.setSchemaSignatures(null);
        // The projection signature is NOT cleared here. It describes the keys that are in
        // Redis *now*, and an anchored-but-unfinished baseline has not rewritten them yet —
        // dropping it would lose the only record of their type if the load fails or is
        // stopped. A cache baseline replaces it when it completes (markBaselineDone with the
        // contract it actually wrote); a destructive path (rebuild sweep) resets the anchor
        // through resetAnchorKeepingProjectionSignature.
        position.setBaselineDone(false);
        position.setUpdatedAt(Instant.now());
        return repository.save(position);
    }

    /**
     * Marks a database baseline complete. The Redis key-space contract, if any, is left
     * untouched: this baseline wrote to a database and never looked at the cache, so what it
     * says about the keys still in Redis remains true.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markBaselineDone(Long projectId) {
        repository.findByProjectId(projectId).ifPresent(position -> {
            position.setBaselineDone(true);
            position.setUpdatedAt(Instant.now());
            repository.save(position);
        });
    }

    /**
     * Marks the baseline complete and records the Redis key-space contract the cache was built
     * with (null for database targets). Start and rebuild compare it with the project's current
     * settings: a JSON &lt;-&gt; HASH switch, or a prefix change, would otherwise only surface as
     * a WRONGTYPE on the next write, after the consumer already parked.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markBaselineDone(Long projectId, String projectionSignature) {
        repository.findByProjectId(projectId).ifPresent(position -> {
            position.setBaselineDone(true);
            position.setProjectionSignature(projectionSignature);
            position.setUpdatedAt(Instant.now());
            repository.save(position);
        });
    }

    /**
     * Post-commit position advance. Ignored when the row vanished underneath us — the
     * pinned "no exception" semantics stay (the transaction is already committed on the
     * target and replays from whatever position survives) — but no longer SILENTLY (final
     * review FIX 1): a holder advancing against a deleted row is the visible symptom of a
     * cross-node position kill, so it earns a WARN naming the project.
     *
     * <p>P1-3: monotonic. A position that goes backwards is rejected with a WARN — once
     * the source slot/binlog position is confirmed, a rewound row no longer describes the
     * target's real state (replay bug or competing writer). Undecidable input passes
     * through: a wrong rejection stalls position advance (= real data loss), a wrong pass
     * costs at most one idempotent replay.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void advance(Long projectId, String file, long pos, String gtidSet) {
        advance(projectId, file, pos, gtidSet, null, false);
    }

    /**
     * Post-commit advance that also records the column layouts the committed transaction was
     * decoded with. See {@link CdcPosition#getSchemaSignatures()} for why the position must
     * carry them: a restart builds bindings from the catalog as it is now, and row images are
     * positional.
     *
     * <p>Only tables in this transaction are replaced; another table's entry keeps describing
     * the layout of the last transaction that touched it, which is exactly what a later
     * binding check needs.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void advance(Long projectId, String file, long pos, String gtidSet,
                        Map<String, String> layoutSignatures) {
        advance(projectId, file, pos, gtidSet, layoutSignatures, false);
    }

    /**
     * As above, with {@code replaceLayouts} saying whether the persisted per-table entries are
     * still trustworthy. A DDL invalidates every binding at once but the binlog does not say
     * which table it touched, so entries for untouched tables now describe a layout that no
     * longer exists; they are dropped wholesale at the next advance (the next transaction
     * re-records the tables it touches). Merging instead would make a later restart compare
     * the new catalog against those obsolete entries and park a project over a DDL it already
     * streamed past.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void advance(Long projectId, String file, long pos, String gtidSet,
                        Map<String, String> layoutSignatures, boolean replaceLayouts) {
        Pending pending = pending(projectId);
        synchronized (pending) {
            seedIfNeeded(projectId, pending);
            if (!pending.seeded) {
                log.warn("CDC position for project {} not advanced to {}:{}: no position row"
                        + " (deleted underneath the consumer?); the transaction replays after"
                        + " the next reconnect", projectId, file, pos);
                return;
            }
            if (pending.file != null && pending.pos != null) {
                int cmp = compareBinlog(pending.file, pending.pos, file, pos);
                if (cmp < 0) {
                    log.warn("CDC position for project {} not advanced to {}:{}: behind the"
                            + " stored position {}:{} — refusing to rewind (replay bug or"
                            + " competing writer?)", projectId, file, pos,
                            pending.file, pending.pos);
                    return;
                }
                if (cmp == 0) {
                    return; // same file, same pos — already there, nothing to record
                }
            }
            pending.file = file;
            pending.pos = pos;
            if (gtidSet != null) {
                pending.gtidSet = gtidSet;
            }
            pending.recordLayouts(layoutSignatures, replaceLayouts);
            pending.dirty = true;
            maybePersist(projectId, pending);
        }
    }

    /** Length of one {@link CdcTableBinding#layoutSignature()} in the encoded text. */
    static final int LAYOUT_SIGNATURE_CHARS = 64;

    /**
     * Encodes {@code "database.table" -> signature} so it survives any character a MySQL
     * identifier may contain (including digits, colons and newlines): each entry is
     * {@code <keyLength>:<key><64-char signature>}. Malformed input decodes to what it can —
     * this text lives in the position row, and refusing to read it would stall every
     * advance, not just the corrupted entry.
     */
    static String encodeLayoutSignatures(Map<String, String> signatures) {
        if (signatures == null || signatures.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder(signatures.size() * 96);
        for (Map.Entry<String, String> entry : signatures.entrySet()) {
            String key = entry.getKey();
            String signature = entry.getValue();
            if (key == null || key.length() > 4096 || signature == null
                    || signature.length() != LAYOUT_SIGNATURE_CHARS) {
                continue;
            }
            out.append(key.length()).append(':').append(key).append(signature);
        }
        return out.length() == 0 ? null : out.toString();
    }

    static Map<String, String> decodeLayoutSignatures(String encoded) {
        Map<String, String> out = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return out;
        }
        int i = 0;
        while (i < encoded.length()) {
            int colon = encoded.indexOf(':', i);
            if (colon < 0) {
                break;
            }
            int keyLength;
            try {
                keyLength = Integer.parseInt(encoded.substring(i, colon));
            } catch (NumberFormatException e) {
                break;                                       // corrupt: keep what is readable
            }
            int keyStart = colon + 1;
            // Bound the key before adding it to an offset: a corrupt length near
            // Integer.MAX_VALUE would overflow keyStart + keyLength into a negative index and
            // throw out of every position read instead of decoding the readable prefix.
            if (keyLength < 0
                    || keyLength > encoded.length() - keyStart - LAYOUT_SIGNATURE_CHARS) {
                break;
            }
            int signatureStart = keyStart + keyLength;
            int signatureEnd = signatureStart + LAYOUT_SIGNATURE_CHARS;
            out.put(encoded.substring(keyStart, signatureStart),
                    encoded.substring(signatureStart, signatureEnd));
            i = signatureEnd;
        }
        return out;
    }

    // —— advance throttle (see ADVANCE_PERSIST_INTERVAL_MS) ——

    private Pending pending(Long projectId) {
        return pendingByProject.computeIfAbsent(projectId, id -> new Pending(clock.getAsLong()));
    }

    /** Reads the stored anchor once; later advances compare against the buffered copy. */
    private void seedIfNeeded(Long projectId, Pending pending) {
        if (!pending.seeded) {
            repository.findByProjectId(projectId).ifPresent(pending::seedFrom);
        }
    }

    private void maybePersist(Long projectId, Pending pending) {
        long now = clock.getAsLong();
        if (pending.dirty && now - pending.persistedAt >= advancePersistIntervalMs) {
            persist(projectId, pending, now);
        }
    }

    /**
     * Writes the buffered position. The monotonic checks run again here against the row as
     * it is NOW — the buffer only spares the round trips, it never mutes the "a competing
     * writer moved ahead" protection.
     */
    private void persist(Long projectId, Pending pending, long now) {
        Optional<CdcPosition> existing = repository.findByProjectId(projectId);
        if (!existing.isPresent()) {
            log.warn("CDC position for project {} not advanced to {}: no position row"
                    + " (deleted underneath the consumer?); the transaction replays after"
                    + " the next reconnect", projectId, pending.describeTarget());
            pending.afterPersist(null, now);
            return;
        }
        CdcPosition position = existing.get();
        boolean changed = false;
        boolean rewound = false;
        if (pending.file != null && pending.pos != null) {
            if (position.getBinlogFile() == null || position.getBinlogPos() == null) {
                position.setBinlogFile(pending.file);
                position.setBinlogPos(pending.pos);
                if (pending.gtidSet != null) {
                    position.setGtidSet(pending.gtidSet);
                }
                changed = true;
            } else {
                int cmp = compareBinlog(position.getBinlogFile(), position.getBinlogPos(),
                        pending.file, pending.pos);
                if (cmp < 0) {
                    log.warn("CDC position for project {} not advanced to {}:{}: behind the"
                            + " stored position {}:{} — refusing to rewind (replay bug or"
                            + " competing writer?)", projectId, pending.file, pending.pos,
                            position.getBinlogFile(), position.getBinlogPos());
                    rewound = true;
                } else if (cmp > 0) {
                    position.setBinlogFile(pending.file);
                    position.setBinlogPos(pending.pos);
                    if (pending.gtidSet != null) {
                        position.setGtidSet(pending.gtidSet);
                    }
                    changed = true;
                }
            }
        }
        if (pending.scn != null) {
            Long stored = position.getScn();
            if (stored != null && pending.scn <= stored) {
                if (pending.scn < stored) {
                    log.warn("CDC position for project {} not advanced to SCN {}: behind the"
                            + " stored SCN {} — refusing to rewind (replay bug or competing"
                            + " writer?)", projectId, pending.scn, stored);
                    rewound = true;
                }
            } else {
                position.setScn(pending.scn);
                changed = true;
            }
        }
        if (pending.lsn != null) {
            String stored = position.getLsn();
            if (stored == null) {
                position.setLsn(pending.lsn);
                changed = true;
            } else {
                int cmp = compareLsn(pending.lsn, stored);
                if (cmp < 0) {
                    log.warn("CDC position for project {} not advanced to LSN {}: behind the"
                            + " stored LSN {} — refusing to rewind (replay bug or competing"
                            + " writer?)", projectId, pending.lsn, stored);
                    rewound = true;
                } else if (!(cmp == 0 && stored.equals(pending.lsn))) {
                    position.setLsn(pending.lsn);
                    changed = true;
                }
            }
        }
        // A rejected rewind must not record the layouts of the transactions it describes:
        // the row already points past them, and a competing writer may have changed them.
        if (!rewound) {
            if (pending.replaceLayouts) {
                position.setSchemaSignatures(encodeLayoutSignatures(pending.layouts));
                changed = true;
            } else if (!pending.layouts.isEmpty()) {
                mergeLayoutSignatures(position, pending.layouts);
                changed = true;
            }
        }
        if (changed) {
            position.setUpdatedAt(Instant.now());
            repository.save(position);
        }
        pending.afterPersist(position, now);
    }

    private static void mergeLayoutSignatures(CdcPosition position,
                                              Map<String, String> signatures) {
        if (signatures == null || signatures.isEmpty()) {
            return;
        }
        Map<String, String> merged = decodeLayoutSignatures(position.getSchemaSignatures());
        merged.putAll(signatures);
        position.setSchemaSignatures(encodeLayoutSignatures(merged));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delete(Long projectId) {
        discard(projectId);
        repository.deleteByProjectId(projectId);
    }

    /**
     * Drops the buffered position. Every anchor-mutating operation calls this: a reset, a
     * baseline start or a delete supersedes whatever the throttle was holding, and a later
     * flush must never resurrect it.
     */
    private void discard(Long projectId) {
        pendingByProject.remove(projectId);
    }

    /**
     * Drops the anchor and the per-table layouts — they describe a destination this project
     * is no longer writing to — while keeping the Redis key-space contract.
     *
     * <p>The contract is not tied to the anchor: it describes the keys this project wrote
     * into Redis, and neither a target-kind switch nor a rebuild sweep has touched them yet.
     * Deleting it with the row lost the only record of their type, so a value-format change
     * made during a Redis &rarr; database &rarr; Redis round trip reached the baseline
     * unannounced and the first write answered {@code WRONGTYPE}. Because {@code baselineDone}
     * resets, the next start or rebuild still reruns the whole baseline and replaces the
     * contract when it completes.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetAnchorKeepingProjectionSignature(Long projectId) {
        discard(projectId);
        repository.findByProjectId(projectId).ifPresent(position -> {
            position.setBinlogFile(null);
            position.setBinlogPos(null);
            position.setGtidSet(null);
            position.setServerId(null);
            position.setScn(null);
            position.setLsn(null);
            position.setSchemaSignatures(null);
            position.setBaselineDone(false);
            position.setUpdatedAt(Instant.now());
            repository.save(position);
        });
    }

    /**
     * LogMiner variant of {@link #recordBaselineStart}: captures the current SCN as the
     * baseline anchor (the mining session later resumes from {@code CSCN > this scn}).
     * Mirrors the binlog contract: baselineDone resets to false; other columns left alone.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CdcPosition recordBaselineStartScn(Long projectId, long scn) {
        discard(projectId);
        CdcPosition position = repository.findByProjectId(projectId).orElseGet(() -> {
            CdcPosition created = new CdcPosition();
            created.setProjectId(projectId);
            return created;
        });
        position.setScn(scn);
        // Same rule as the binlog variant: the signature describes the keys still in Redis
        // and is only replaced by a completed cache baseline.
        position.setBaselineDone(false);
        position.setUpdatedAt(Instant.now());
        return repository.save(position);
    }

    /**
     * LogMiner variant of {@link #advance}: post-commit SCN advance with the same
     * "row vanished → WARN, never throw" semantics (the transaction already committed on
     * the target; the next mining session resumes from whatever survives).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void advanceScn(Long projectId, long scn) {
        Pending pending = pending(projectId);
        synchronized (pending) {
            seedIfNeeded(projectId, pending);
            if (!pending.seeded) {
                log.warn("CDC position for project {} not advanced to SCN {}: no position row"
                        + " (deleted underneath the consumer?); the transaction replays after"
                        + " the next reconnect", projectId, scn);
                return;
            }
            // P1-3: monotonic — SCN is a plain number, no format ambiguity.
            if (pending.scn != null && scn <= pending.scn) {
                if (scn < pending.scn) {
                    log.warn("CDC position for project {} not advanced to SCN {}: behind the"
                            + " stored SCN {} — refusing to rewind (replay bug or competing"
                            + " writer?)", projectId, scn, pending.scn);
                }
                return;
            }
            pending.scn = scn;
            pending.dirty = true;
            maybePersist(projectId, pending);
        }
    }

    /**
     * LSN variant of {@link #recordBaselineStart}: captures the current source LSN as the
     * baseline anchor. Used by {@code PG_LOGICAL_CDC} (pg_lsn) and {@code SQLSERVER_CDC}
     * (cdc __$start_lsn hex). baselineDone resets to false; other columns left alone.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CdcPosition recordBaselineStartLsn(Long projectId, String lsn) {
        discard(projectId);
        CdcPosition position = repository.findByProjectId(projectId).orElseGet(() -> {
            CdcPosition created = new CdcPosition();
            created.setProjectId(projectId);
            return created;
        });
        position.setLsn(lsn);
        position.setBaselineDone(false);
        position.setUpdatedAt(Instant.now());
        return repository.save(position);
    }

    /**
     * LSN variant of {@link #advance}: post-commit LSN advance with the same
     * "row vanished → WARN, never throw" semantics.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void advanceLsn(Long projectId, String lsn) {
        Pending pending = pending(projectId);
        synchronized (pending) {
            seedIfNeeded(projectId, pending);
            if (!pending.seeded) {
                log.warn("CDC position for project {} not advanced to LSN {}: no position row"
                        + " (deleted underneath the consumer?); the transaction replays after"
                        + " the next reconnect", projectId, lsn);
                return;
            }
            // P1-3: monotonic. compareLsn gives no verdict (0) on null/mixed/unparseable
            // formats — a 0 with identical text means "already there", a 0 with different
            // text passes through so an unknown format can never stall the position.
            String stored = pending.lsn;
            if (stored != null && lsn != null) {
                int cmp = compareLsn(lsn, stored);
                if (cmp < 0) {
                    log.warn("CDC position for project {} not advanced to LSN {}: behind the"
                            + " stored LSN {} — refusing to rewind (replay bug or competing"
                            + " writer?)", projectId, lsn, stored);
                    return;
                }
                if (cmp == 0 && stored.equals(lsn)) {
                    return; // already at this exact LSN — nothing to record
                }
            }
            pending.lsn = lsn;
            pending.dirty = true;
            maybePersist(projectId, pending);
        }
    }

    // —— P1-3 monotonicity helpers (package-private for tests) ——

    /**
     * Compares two position strings. Understands PG's {@code high/low} pg_lsn text form
     * and SQL Server's {@code 0x...} binary(10) hex, both by numeric value. Returns 0
     * (no verdict) for nulls, mixed vendors, or unparseable text — see {@link #advanceLsn}
     * for how callers keep that conservative.
     */
    static int compareLsn(String a, String b) {
        if (a == null || b == null) {
            return 0;
        }
        String x = a.trim();
        String y = b.trim();
        if (x.contains("/") || y.contains("/")) {
            BigInteger[] px = parsePgLsn(x);
            BigInteger[] py = parsePgLsn(y);
            if (px == null || py == null) {
                return 0;
            }
            int cmp = px[0].compareTo(py[0]);
            return cmp != 0 ? cmp : px[1].compareTo(py[1]);
        }
        if (isHexLiteral(x) && isHexLiteral(y)) {
            return new BigInteger(x.substring(2), 16)
                    .compareTo(new BigInteger(y.substring(2), 16));
        }
        // YStream packs its five-tuple at fixed width in commit order, so text order is
        // commit order; this is the only form where a plain string compare is correct.
        if (YStreamPositionCodec.isPacked(x) && YStreamPositionCodec.isPacked(y)) {
            return x.compareTo(y);
        }
        return 0;
    }

    /**
     * Binlog position order: &lt;0 rewind, 0 identical (same file, same pos), &gt;0
     * forward — or undecidable (unparseable file names), which callers must treat as
     * forward because a wrong rejection stalls the position.
     */
    static int compareBinlog(String oldFile, long oldPos, String newFile, long newPos) {
        if (oldFile != null && oldFile.equals(newFile)) {
            return Long.compare(newPos, oldPos);
        }
        Long oldNum = trailingNumber(oldFile);
        Long newNum = trailingNumber(newFile);
        if (oldNum == null || newNum == null) {
            return 1; // unparseable names — pass through
        }
        int cmp = Long.compare(newNum, oldNum);
        return cmp != 0 ? cmp : 1; // equal numbers, different names — pass through
    }

    /** {@code high/low} hex segments → [high, low], or null when the text isn't one. */
    private static BigInteger[] parsePgLsn(String s) {
        int slash = s.indexOf('/');
        if (slash <= 0 || slash == s.length() - 1 || s.indexOf('/', slash + 1) >= 0) {
            return null;
        }
        String high = s.substring(0, slash);
        String low = s.substring(slash + 1);
        if (!isHexDigits(high) || !isHexDigits(low)) {
            return null;
        }
        return new BigInteger[]{new BigInteger(high, 16), new BigInteger(low, 16)};
    }

    private static boolean isHexLiteral(String s) {
        return s.length() > 2 && (s.startsWith("0x") || s.startsWith("0X"))
                && isHexDigits(s.substring(2));
    }

    private static boolean isHexDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    private static final Pattern TRAILING_NUMBER = Pattern.compile("(\\d+)$");

    /** Trailing digit run of a binlog file name ("mysql-bin.000007" → 7), or null. */
    private static Long trailingNumber(String fileName) {
        if (fileName == null) {
            return null;
        }
        Matcher m = TRAILING_NUMBER.matcher(fileName);
        if (!m.find()) {
            return null;
        }
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return null; // absurdly long digit run — undecidable
        }
    }

    /**
     * The newest committed position of one project that has not reached the database yet.
     * Guarded by its own monitor so a slow write for one project never blocks another.
     */
    private static final class Pending {

        private String file;
        private Long pos;
        private String gtidSet;
        private Long scn;
        private String lsn;
        private final Map<String, String> layouts = new LinkedHashMap<>();
        private boolean replaceLayouts;
        private boolean dirty;
        private boolean seeded;
        private long persistedAt;

        private Pending(long now) {
            this.persistedAt = now;
        }

        private void seedFrom(CdcPosition row) {
            file = row.getBinlogFile();
            pos = row.getBinlogPos();
            gtidSet = row.getGtidSet();
            scn = row.getScn();
            lsn = row.getLsn();
            seeded = true;
        }

        private void recordLayouts(Map<String, String> signatures, boolean replace) {
            if (replace) {
                replaceLayouts = true;
                layouts.clear();
            }
            if (signatures != null && !signatures.isEmpty()) {
                layouts.putAll(signatures);
            }
        }

        /** The buffered values applied over the stored row; readers see the newest position. */
        private CdcPosition overlay(CdcPosition stored) {
            CdcPosition copy = new CdcPosition();
            copy.setId(stored.getId());
            copy.setProjectId(stored.getProjectId());
            copy.setServerId(stored.getServerId());
            copy.setProjectionSignature(stored.getProjectionSignature());
            copy.setBaselineDone(stored.isBaselineDone());
            copy.setUpdatedAt(stored.getUpdatedAt());
            copy.setOptVersion(stored.getOptVersion());
            copy.setBinlogFile(file != null ? file : stored.getBinlogFile());
            copy.setBinlogPos(pos != null ? pos : stored.getBinlogPos());
            copy.setGtidSet(gtidSet != null ? gtidSet : stored.getGtidSet());
            copy.setScn(scn != null ? scn : stored.getScn());
            copy.setLsn(lsn != null ? lsn : stored.getLsn());
            if (replaceLayouts || !layouts.isEmpty()) {
                Map<String, String> merged = replaceLayouts
                        ? new LinkedHashMap<>()
                        : decodeLayoutSignatures(stored.getSchemaSignatures());
                merged.putAll(layouts);
                copy.setSchemaSignatures(encodeLayoutSignatures(merged));
            } else {
                copy.setSchemaSignatures(stored.getSchemaSignatures());
            }
            return copy;
        }

        /** {@code row} is the entity just written (or read when the row vanished). */
        private void afterPersist(CdcPosition row, long now) {
            dirty = false;
            layouts.clear();
            replaceLayouts = false;
            persistedAt = now;
            if (row == null) {
                seeded = false;         // the next advance re-reads the row
            } else {
                seedFrom(row);
            }
        }

        private String describeTarget() {
            if (file != null) {
                return file + ":" + pos;
            }
            if (scn != null) {
                return "SCN " + scn;
            }
            if (lsn != null) {
                return "LSN " + lsn;
            }
            return "unknown position";
        }
    }
}
