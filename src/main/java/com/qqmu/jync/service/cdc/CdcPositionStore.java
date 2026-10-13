package com.qqmu.jync.service.cdc;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private final CdcPositionRepository repository;

    public CdcPositionStore(CdcPositionRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<CdcPosition> find(Long projectId) {
        return repository.findByProjectId(projectId);
    }

    /** Upsert the start-of-baseline position; baselineDone resets to false. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CdcPosition recordBaselineStart(Long projectId, String file, long pos,
                                           String gtidSet, long serverId) {
        CdcPosition position = repository.findByProjectId(projectId).orElseGet(() -> {
            CdcPosition created = new CdcPosition();
            created.setProjectId(projectId);
            return created;
        });
        position.setBinlogFile(file);
        position.setBinlogPos(pos);
        position.setGtidSet(gtidSet);
        position.setServerId(serverId);
        position.setBaselineDone(false);
        position.setUpdatedAt(Instant.now());
        return repository.save(position);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markBaselineDone(Long projectId) {
        repository.findByProjectId(projectId).ifPresent(position -> {
            position.setBaselineDone(true);
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
        Optional<CdcPosition> existing = repository.findByProjectId(projectId);
        if (!existing.isPresent()) {
            log.warn("CDC position for project {} not advanced to {}:{}: no position row"
                    + " (deleted underneath the consumer?); the transaction replays after"
                    + " the next reconnect", projectId, file, pos);
            return;
        }
        CdcPosition position = existing.get();
        if (position.getBinlogFile() != null && position.getBinlogPos() != null) {
            int cmp = compareBinlog(position.getBinlogFile(), position.getBinlogPos(), file, pos);
            if (cmp < 0) {
                log.warn("CDC position for project {} not advanced to {}:{}: behind the"
                        + " stored position {}:{} — refusing to rewind (replay bug or"
                        + " competing writer?)", projectId, file, pos,
                        position.getBinlogFile(), position.getBinlogPos());
                return;
            }
            if (cmp == 0) {
                return; // same file, same pos — already there, nothing to save
            }
        }
        position.setBinlogFile(file);
        position.setBinlogPos(pos);
        if (gtidSet != null) {
            position.setGtidSet(gtidSet);
        }
        position.setUpdatedAt(Instant.now());
        repository.save(position);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delete(Long projectId) {
        repository.deleteByProjectId(projectId);
    }

    /**
     * LogMiner variant of {@link #recordBaselineStart}: captures the current SCN as the
     * baseline anchor (the mining session later resumes from {@code CSCN > this scn}).
     * Mirrors the binlog contract: baselineDone resets to false; other columns left alone.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CdcPosition recordBaselineStartScn(Long projectId, long scn) {
        CdcPosition position = repository.findByProjectId(projectId).orElseGet(() -> {
            CdcPosition created = new CdcPosition();
            created.setProjectId(projectId);
            return created;
        });
        position.setScn(scn);
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
        Optional<CdcPosition> existing = repository.findByProjectId(projectId);
        if (!existing.isPresent()) {
            log.warn("CDC position for project {} not advanced to SCN {}: no position row"
                    + " (deleted underneath the consumer?); the transaction replays after"
                    + " the next reconnect", projectId, scn);
            return;
        }
        CdcPosition position = existing.get();
        // P1-3: monotonic — SCN is a plain number, no format ambiguity.
        if (position.getScn() != null && scn <= position.getScn()) {
            if (scn < position.getScn()) {
                log.warn("CDC position for project {} not advanced to SCN {}: behind the"
                        + " stored SCN {} — refusing to rewind (replay bug or competing"
                        + " writer?)", projectId, scn, position.getScn());
            }
            return;
        }
        position.setScn(scn);
        position.setUpdatedAt(Instant.now());
        repository.save(position);
    }

    /**
     * LSN variant of {@link #recordBaselineStart}: captures the current source LSN as the
     * baseline anchor. Used by {@code PG_LOGICAL_CDC} (pg_lsn) and {@code SQLSERVER_CDC}
     * (cdc __$start_lsn hex). baselineDone resets to false; other columns left alone.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CdcPosition recordBaselineStartLsn(Long projectId, String lsn) {
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
        Optional<CdcPosition> existing = repository.findByProjectId(projectId);
        if (!existing.isPresent()) {
            log.warn("CDC position for project {} not advanced to LSN {}: no position row"
                    + " (deleted underneath the consumer?); the transaction replays after"
                    + " the next reconnect", projectId, lsn);
            return;
        }
        CdcPosition position = existing.get();
        // P1-3: monotonic. compareLsn gives no verdict (0) on null/mixed/unparseable
        // formats — a 0 with identical text means "already there", a 0 with different
        // text passes through so an unknown format can never stall the position.
        String stored = position.getLsn();
        if (stored != null && lsn != null) {
            int cmp = compareLsn(lsn, stored);
            if (cmp < 0) {
                log.warn("CDC position for project {} not advanced to LSN {}: behind the"
                        + " stored LSN {} — refusing to rewind (replay bug or competing"
                        + " writer?)", projectId, lsn, stored);
                return;
            }
            if (cmp == 0 && stored.equals(lsn)) {
                return; // already at this exact LSN — nothing to save
            }
        }
        position.setLsn(lsn);
        position.setUpdatedAt(Instant.now());
        repository.save(position);
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
}
