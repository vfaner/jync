package com.qqmu.jync.service.version;

import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.qqmu.jync.model.UpdateArtifactDismissal;
import com.qqmu.jync.repository.UpdateArtifactDismissalRepository;

/**
 * Stores the dismissal in the metadata database (one row). Created through Spring; tests may
 * construct it directly with the repository, which is why the constructor is not private.
 */
@Component
public class JpaArtifactDismissal implements ArtifactDismissal {

    private final UpdateArtifactDismissalRepository repository;

    public JpaArtifactDismissal(UpdateArtifactDismissalRepository repository) {
        this.repository = repository;
    }

    @Override
    public String dismissedKey() {
        return repository.findById(UpdateArtifactDismissal.SINGLETON_ID)
                .map(UpdateArtifactDismissal::getArtifactKey)
                .orElse(null);
    }

    @Override
    public void dismiss(String artifactKey) {
        if (!StringUtils.hasText(artifactKey)) {
            return; // Nothing published to remember; keeping an older key would be a lie.
        }
        UpdateArtifactDismissal row =
                repository.findById(UpdateArtifactDismissal.SINGLETON_ID)
                        .orElseGet(() -> {
                            UpdateArtifactDismissal fresh = new UpdateArtifactDismissal();
                            fresh.setId(UpdateArtifactDismissal.SINGLETON_ID);
                            return fresh;
                        });
        row.setArtifactKey(artifactKey.trim());
        row.setDismissedAt(Instant.now());
        repository.save(row);
    }
}
