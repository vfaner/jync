package com.synctool.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.synctool.model.DatabaseConfig;
import com.synctool.model.DatabaseType;
import com.synctool.model.Project;
import com.synctool.repository.DatabaseConfigRepository;
import com.synctool.repository.ProjectRepository;
import com.synctool.service.connection.ConnectionTestService;
import com.synctool.service.connection.DataSourceManager;
import com.synctool.util.CryptoUtil;

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
        if (config.getName() == null || config.getName().isBlank()) {
            throw new IllegalArgumentException("error.connection.name.required");
        }
        repository.findByName(config.getName().trim()).ifPresent(existing -> {
            if (!existing.getId().equals(config.getId())) {
                throw new IllegalArgumentException("error.connection.name.duplicate");
            }
        });
        config.setName(config.getName().trim());

        if (config.getType() == DatabaseType.CUSTOM) {
            // Without these three a custom connection cannot be opened at all.
            if (config.getCustomUrl() == null || config.getCustomUrl().isBlank()) {
                throw new IllegalArgumentException("error.custom.url.required");
            }
            if (config.getCustomDriver() == null || config.getCustomDriver().isBlank()) {
                throw new IllegalArgumentException("error.custom.driver.required");
            }
        } else if (config.getHost() == null || config.getHost().isBlank()) {
            if (config.getCustomUrl() == null || config.getCustomUrl().isBlank()) {
                throw new IllegalArgumentException("error.connection.host.required");
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
        List<Project> dependents = projectRepository.findBySourceDbIdOrTargetDbId(id, id);
        if (!dependents.isEmpty()) {
            String names = dependents.stream().map(Project::getName)
                    .reduce((a, b) -> a + ", " + b).orElse("");
            throw new IllegalStateException("error.connection.in.use:" + names);
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
}
