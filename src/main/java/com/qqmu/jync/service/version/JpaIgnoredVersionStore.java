package com.qqmu.jync.service.version;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.qqmu.jync.model.UpdateVersionDismissal;
import com.qqmu.jync.repository.UpdateVersionDismissalRepository;

/**
 * Stores the ignored release in the metadata database, one row for the whole instance.
 *
 * <p>Version comparison reuses {@link VersionService#compareVersions(String, String)} so the
 * store agrees with the update check itself. A database failure must not break the version API:
 * both reads and writes swallow it and answer "nothing ignored", i.e. the dialog keeps working
 * (possibly one time too many) rather than the page erroring.
 */
@Service
public class JpaIgnoredVersionStore implements IgnoredVersionStore {

    private final UpdateVersionDismissalRepository repository;

    public JpaIgnoredVersionStore(UpdateVersionDismissalRepository repository) {
        this.repository = repository;
    }

    @Override
    public String ignoredVersion() {
        try {
            return repository.findById(UpdateVersionDismissal.SINGLETON_ID)
                    .map(UpdateVersionDismissal::getIgnoredVersion)
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean isIgnored(String latestVersion) {
        String ignored = ignoredVersion();
        if (!StringUtils.hasText(ignored) || !StringUtils.hasText(latestVersion)) {
            return false;
        }
        return VersionService.compareVersions(latestVersion, ignored) <= 0;
    }

    @Transactional
    @Override
    public boolean ignore(String version) {
        String normalized = normalize(version);
        if (normalized == null) {
            return false;
        }
        UpdateVersionDismissal row = repository.findById(UpdateVersionDismissal.SINGLETON_ID)
                .orElseGet(() -> {
                    UpdateVersionDismissal fresh = new UpdateVersionDismissal();
                    fresh.setId(UpdateVersionDismissal.SINGLETON_ID);
                    return fresh;
                });
        row.setIgnoredVersion(normalized);
        row.setDismissedAt(Instant.now());
        repository.save(row);
        return true;
    }

    /**
     * Accepts what a release tag can plausibly contain: trims, drops one leading {@code v},
     * and rejects blank, over-long, or space/control-character input. Invalid versions record
     * nothing instead of poisoning every later comparison.
     */
    static String normalize(String version) {
        if (version == null) {
            return null;
        }
        String value = version.trim();
        if (value.startsWith("v") || value.startsWith("V")) {
            value = value.substring(1);
        }
        if (value.isEmpty() || value.length() > 64
                || value.chars().anyMatch(cp -> Character.isWhitespace(cp)
                        || Character.isISOControl(cp))) {
            return null;
        }
        return value;
    }
}
