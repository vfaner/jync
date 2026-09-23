package com.qqmu.jync.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.connection.ConnectionTestService;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.connection.DriverPresence;
import com.qqmu.jync.util.CryptoUtil;

import lombok.extern.slf4j.Slf4j;

/** CRUD and validation for database connection configurations. */
@Service
@Slf4j
public class DatabaseConfigService {

    private final DatabaseConfigRepository repository;
    private final ProjectRepository projectRepository;
    private final DataSourceManager dataSourceManager;
    private final ConnectionTestService connectionTestService;
    private final CryptoUtil cryptoUtil;

    public DatabaseConfigService(DatabaseConfigRepository repository,
                                 ProjectRepository projectRepository,
                                 DataSourceManager dataSourceManager,
                                 ConnectionTestService connectionTestService,
                                 CryptoUtil cryptoUtil) {
        this.repository = repository;
        this.projectRepository = projectRepository;
        this.dataSourceManager = dataSourceManager;
        this.connectionTestService = connectionTestService;
        this.cryptoUtil = cryptoUtil;
    }

    public List<DatabaseConfig> findAll() {
        return repository.findAllByOrderByNameAsc();
    }

    public List<DatabaseConfig> findByRole(ConnectionRole role) {
        return repository.findByRoleOrderByNameAsc(role);
    }

    public Page<DatabaseConfig> findPageByRole(ConnectionRole role, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), size);
        return repository.findByRoleOrderByNameAsc(role, pageable);
    }

    public Optional<DatabaseConfig> findById(Long id) {
        return repository.findById(id);
    }

    public long count() {
        return repository.count();
    }

    /**
     * Creates or updates a connection.
     *
     * <p>The password is encrypted before storage. An empty password on an update means "keep
     * the existing one", so the edit form never has to round-trip the secret to the browser.
     */
    @Transactional
    public DatabaseConfig save(DatabaseConfig config, String rawPassword) {
        validate(config);

        if (config.getId() != null) {
            DatabaseConfig existing = repository.findById(config.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Connection not found"));
            if (existing.getRole() != null && existing.getRole() != config.getRole()) {
                // Reclassifying a connection that projects already depend on would silently
                // remove it from the project form's selector on the other side.
                List<Project> blocking = config.getRole() == ConnectionRole.TARGET
                        ? projectRepository.findBySourceDbId(config.getId())
                        : projectRepository.findByTargetDbId(config.getId());
                if (!blocking.isEmpty()) {
                    String key = config.getRole() == ConnectionRole.TARGET
                            ? "error.connection.role.source.in.use:"
                            : "error.connection.role.target.in.use:";
                    throw new IllegalStateException(key + joinNames(blocking));
                }
            }
            if (rawPassword == null || rawPassword.isEmpty()) {
                config.setPassword(existing.getPassword());
            } else {
                config.setPassword(cryptoUtil.encrypt(rawPassword));
            }
            config.setCreatedAt(existing.getCreatedAt());
        } else {
            config.setPassword(cryptoUtil.encrypt(rawPassword));
        }

        // Default the port from the product when the user left it blank.
        if (config.getPort() == null && config.getType() != null && !config.getType().isCustom()) {
            config.setPort(config.getType().getDefaultPort());
        }

        DatabaseConfig saved = repository.save(config);
        // Drop the cached pool so the next connection picks up the new settings.
        dataSourceManager.evict(saved.getId());
        log.info("Saved database connection '{}' ({})", saved.getName(), saved.getType());
        return saved;
    }

    private void validate(DatabaseConfig config) {
        if (config.getRole() == null) {
            throw new IllegalArgumentException("error.connection.role.required");
        }
        if (config.getName() == null || config.getName().isBlank()) {
            throw new IllegalArgumentException("error.connection.name.required");
        }
        repository.findByName(config.getName().trim()).ifPresent(existing -> {
            if (!existing.getId().equals(config.getId())) {
                throw new IllegalArgumentException("error.connection.name.duplicate");
            }
        });
        config.setName(config.getName().trim());

        DatabaseType type = config.getType();
        if (type == DatabaseType.CUSTOM) {
            // Without these three a custom connection cannot be opened at all.
            if (config.getCustomUrl() == null || config.getCustomUrl().isBlank()) {
                throw new IllegalArgumentException("error.custom.url.required");
            }
            if (config.getCustomDriver() == null || config.getCustomDriver().isBlank()) {
                throw new IllegalArgumentException("error.custom.driver.required");
            }
        } else {
            if (config.getHost() == null || config.getHost().isBlank()) {
                if (config.getCustomUrl() == null || config.getCustomUrl().isBlank()) {
                    throw new IllegalArgumentException("error.connection.host.required");
                }
            }
            // GBase / Oscar and any future preset without a bundled driver must bring a jar,
            // otherwise the failure surfaces much later in English at sync time.
            if (DriverPresence.externalJarRequired(type, config.getCustomDriver())
                    && (config.getCustomJarPath() == null || config.getCustomJarPath().isBlank())) {
                throw new IllegalArgumentException("error.driver.jar.required");
            }
        }
    }

    /**
     * Deletes a connection, refusing when a project still points at it.
     *
     * <p>Removing it silently would leave those projects permanently broken with a confusing
     * error at sync time, so the check happens here where it can be explained.
     */
    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            // deleteById on a missing row throws EmptyResultDataAccessException, which a
            // double-clicked or two-tab delete would surface as a 500.
            throw new IllegalArgumentException("error.connection.missing");
        }
        List<Project> dependents = projectRepository.findBySourceDbIdOrTargetDbId(id, id);
        if (!dependents.isEmpty()) {
            throw new IllegalStateException("error.connection.in.use:" + joinNames(dependents));
        }
        dataSourceManager.evict(id);
        repository.deleteById(id);
        log.info("Deleted database connection {}", id);
    }

    /** Tests a saved connection. */
    public ConnectionTestService.TestResult test(Long id) {
        DatabaseConfig config = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Connection not found"));
        return connectionTestService.test(config);
    }

    /**
     * Tests an unsaved connection built from form input.
     *
     * <p>Lets the user verify settings before committing them, which matters most for custom
     * drivers where the jar path and class name are easy to get wrong.
     */
    public ConnectionTestService.TestResult testTransient(DatabaseConfig config, String rawPassword) {
        if (rawPassword != null && !rawPassword.isEmpty()) {
            config.setPassword(cryptoUtil.encrypt(rawPassword));
        } else if (config.getId() != null) {
            // Reuse the stored password when the form left the field blank.
            repository.findById(config.getId())
                    .ifPresent(existing -> config.setPassword(existing.getPassword()));
        }
        return connectionTestService.test(config);
    }

    /** The JDBC URL that would be used, shown in the UI so the user can sanity-check it. */
    public String previewUrl(DatabaseConfig config) {
        try {
            return dataSourceManager.buildJdbcUrl(config);
        } catch (RuntimeException e) {
            return "(" + e.getMessage() + ")";
        }
    }

    private static String joinNames(List<Project> projects) {
        return projects.stream().map(Project::getName)
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    /**
     * Backfills the role column for rows created before source/target classification existed:
     * ddl-auto=update adds the column as nullable, so existing connections start with NULL.
     * The role is inferred from how projects reference the connection; unreferenced rows
     * default to SOURCE and can be reclassified by the user as long as no project blocks it.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void backfillRoles() {
        List<DatabaseConfig> missing = new ArrayList<>();
        for (DatabaseConfig config : repository.findAll()) {
            if (config.getRole() != null) {
                continue;
            }
            if (!projectRepository.findBySourceDbId(config.getId()).isEmpty()) {
                config.setRole(ConnectionRole.SOURCE);
            } else if (!projectRepository.findByTargetDbId(config.getId()).isEmpty()) {
                config.setRole(ConnectionRole.TARGET);
            } else {
                config.setRole(ConnectionRole.SOURCE);
            }
            missing.add(config);
        }
        if (!missing.isEmpty()) {
            repository.saveAll(missing);
            log.info("Backfilled source/target role for {} existing connection(s)", missing.size());
        }
    }
}
