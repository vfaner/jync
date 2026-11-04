package com.qqmu.jync.service.migration;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.dto.MigrationConnectionForm;
import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.service.audit.AuditDetail;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.repository.MigrationConnectionRepository;
import com.qqmu.jync.repository.MigrationTaskRepository;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.migration.connection.MigrationConnectionAdapterRegistry;
import com.qqmu.jync.service.migration.connection.MigrationConnectionEndpoint;
import com.qqmu.jync.service.migration.connection.MigrationConnectionKindAdapter;
import com.qqmu.jync.service.migration.connection.MigrationConnectionTestResult;
import com.qqmu.jync.service.migration.connection.MigrationDiscoveryItem;
import com.qqmu.jync.util.CryptoUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * CRUD, validation and probing for dedicated-migration connections. The kind-specific endpoint
 * and protocol work lives in a {@link MigrationConnectionKindAdapter}; this service owns the
 * generic rules: names, credentials, audit.
 */
@Service
@Slf4j
public class MigrationConnectionService {

    private final MigrationConnectionRepository connections;
    private final MigrationTaskRepository tasks;
    private final CryptoUtil cryptoUtil;
    private final AdminAuditService auditService;
    private final MigrationConnectionAdapterRegistry adapters;

    public MigrationConnectionService(MigrationConnectionRepository connections,
                                      MigrationTaskRepository tasks,
                                      CryptoUtil cryptoUtil,
                                      AdminAuditService auditService,
                                      MigrationConnectionAdapterRegistry adapters) {
        this.connections = connections;
        this.tasks = tasks;
        this.cryptoUtil = cryptoUtil;
        this.auditService = auditService;
        this.adapters = adapters;
    }

    public List<MigrationConnection> findAll() {
        return connections.findAllByOrderByNameAsc();
    }

    public Page<MigrationConnection> findPage(int page, int size) {
        return connections.findAllByOrderByNameAsc(PageRequest.of(Math.max(0, page), size));
    }

    public Optional<MigrationConnection> findById(Long id) {
        return connections.findById(id);
    }

    public MigrationConnectionForm formFor(Long id) {
        return connections.findById(id)
                .map(MigrationConnectionForm::from)
                .orElseThrow(() -> new IllegalArgumentException(
                        "error.migration.connection.missing"));
    }

    /**
     * Creates or updates a connection. A blank raw secret on edit keeps the stored one; the
     * form never receives ciphertext or plaintext back from the browser.
     */
    @Transactional
    public MigrationConnection save(MigrationConnectionForm form, String rawPassword,
                                    String rawApiKey) {
        validateCommon(form);

        MigrationConnection entity;
        MigrationConnection existing = null;
        if (form.getId() != null) {
            Long id = form.getId();
            // The duplicate check comes first: a name clash on an id that no longer exists is
            // still a name clash, and this is the more useful error to show.
            connections.findByName(form.getName()).ifPresent(found -> {
                if (!found.getId().equals(id)) {
                    throw new IllegalArgumentException(
                            "error.migration.connection.name.duplicate");
                }
            });
            existing = connections.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "error.migration.connection.not.found"));
            // Reuse the loaded instance. A fresh entity built from the form has a null
            // @Version, so Spring Data treats it as new and INSERTs it — an edit would then
            // create a duplicate row (or hit the unique name index) instead of updating.
            entity = existing;
        } else {
            connections.findByName(form.getName()).ifPresent(found -> {
                throw new IllegalArgumentException(
                        "error.migration.connection.name.duplicate");
            });
            entity = new MigrationConnection();
        }

        if (existing != null && existing.getKind() != form.getKind()
                && tasks.countBySourceConnectionIdOrTargetConnectionId(
                        existing.getId(), existing.getId()) > 0) {
            // A saved task keeps the kind it was validated against; switching the connection
            // underneath it would turn a valid task into one that can no longer run.
            throw new IllegalStateException("error.migration.connection.kind.inUse");
        }
        MigrationConnectionKindAdapter adapter = adapters.forKind(form.getKind());
        MigrationConnectionEndpoint endpoint = adapter.normalize(form);
        adapter.validateAuth(form, rawPassword, rawApiKey, existing);

        entity.setName(form.getName());
        entity.setKind(form.getKind());
        entity.setBaseUrl(endpoint.baseUrl());
        entity.setDatabaseName(endpoint.databaseName());
        entity.setSchemaName(endpoint.schemaName());
        entity.setExtraParams(endpoint.extraParams());
        entity.setAuthType(form.getAuthType());
        entity.setUsername(form.getUsername());
        entity.setVerifyTls(form.isVerifyTls());
        entity.setConnectTimeoutMs(form.getConnectTimeoutMs() == null
                ? 5000 : form.getConnectTimeoutMs());
        entity.setRequestTimeoutMs(form.getRequestTimeoutMs() == null
                ? 60000 : form.getRequestTimeoutMs());
        entity.setDescription(form.getDescription());

        applyCredentials(entity, form.getAuthType(), rawPassword, rawApiKey, existing);

        MigrationConnection saved = connections.save(entity);
        boolean isNew = form.getId() == null;
        auditService.record(AuditAction.MIGRATION_CONNECTION_SAVE,
                AuditDetail.of("audit.detail.migrationConnectionSave",
                        isNew ? "@created" : "@updated", saved.getName(),
                        "@kind:" + saved.getKind()));
        if (!saved.isVerifyTls()) {
            auditService.record(AuditAction.MIGRATION_CONNECTION_SAVE,
                    AuditDetail.of("audit.detail.migrationConnectionTls", saved.getName()));
        }
        log.info("Saved dedicated-migration connection '{}' ({})", saved.getName(),
                saved.getKind());
        return saved;
    }

    private void applyCredentials(MigrationConnection entity,
                                  MigrationConnectionAuthType authType,
                                  String rawPassword, String rawApiKey,
                                  MigrationConnection existing) {
        if (authType == MigrationConnectionAuthType.NONE) {
            entity.setPasswordEnc(null);
            entity.setApiKeyEnc(null);
            entity.setUsername(null);
            return;
        }
        if (authType == MigrationConnectionAuthType.BASIC) {
            entity.setApiKeyEnc(null);
            if (rawPassword != null && !rawPassword.isEmpty()) {
                entity.setPasswordEnc(cryptoUtil.encrypt(rawPassword));
            }
            return;
        }
        entity.setPasswordEnc(null);
        if (rawApiKey != null && !rawApiKey.isBlank()) {
            entity.setApiKeyEnc(cryptoUtil.encrypt(rawApiKey));
        } else if (existing == null || existing.getApiKeyEnc() == null) {
            throw new IllegalArgumentException("error.migration.connection.apiKey.required");
        }
    }

    private void validateCommon(MigrationConnectionForm form) {
        if (form.getName() == null || form.getName().isBlank()) {
            throw new IllegalArgumentException("error.migration.connection.name.required");
        }
        form.setName(form.getName().trim());
        if (form.getName().length() > 128) {
            throw new IllegalArgumentException("error.migration.connection.name.too.long");
        }
        if (form.getKind() == null) {
            throw new IllegalArgumentException("error.migration.connection.kind.required");
        }
        if (form.getAuthType() == null) {
            throw new IllegalArgumentException("error.migration.connection.auth.required");
        }
        if (form.getBaseUrl() == null || form.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("error.migration.connection.url.invalid");
        }
        if (form.getConnectTimeoutMs() != null
                && (form.getConnectTimeoutMs() < 500 || form.getConnectTimeoutMs() > 60000)) {
            throw new IllegalArgumentException("error.migration.connection.timeout.invalid");
        }
        if (form.getRequestTimeoutMs() != null
                && (form.getRequestTimeoutMs() < 1000
                        || form.getRequestTimeoutMs() > 600000)) {
            throw new IllegalArgumentException("error.migration.connection.timeout.invalid");
        }
        if (form.getDescription() != null && form.getDescription().length() > 512) {
            throw new IllegalArgumentException(
                    "error.migration.connection.description.too.long");
        }
    }

    @Transactional
    public void delete(Long id) {
        MigrationConnection victim = connections.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "error.migration.connection.missing"));
        if (tasks.countBySourceConnectionIdOrTargetConnectionId(id, id) > 0) {
            throw new IllegalStateException("error.migration.connection.inUse");
        }
        connections.deleteById(id);
        auditService.record(AuditAction.MIGRATION_CONNECTION_DELETE,
                AuditDetail.of("audit.detail.migrationConnectionDelete", victim.getName(),
                        "@kind:" + victim.getKind()));
        log.info("Deleted dedicated-migration connection {}", id);
    }

    public MigrationConnectionTestResult test(Long id) {
        MigrationConnection connection = connections.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "error.migration.connection.missing"));
        return adapters.forKind(connection.getKind()).test(connection);
    }

    /**
     * Tests form values before saving. A saved form with blank secrets (the edit page never
     * receives them) falls back to the stored values.
     */
    public MigrationConnectionTestResult testTransient(MigrationConnectionForm form,
                                                       String rawPassword, String rawApiKey) {
        MigrationConnection entity = form.toEntity();
        String password = rawPassword;
        String apiKey = rawApiKey;
        if (form.getId() != null) {
            MigrationConnection existing = connections.findById(form.getId()).orElse(null);
            if (existing != null) {
                if ((password == null || password.isEmpty())
                        && existing.getPasswordEnc() != null) {
                    password = cryptoUtil.decrypt(existing.getPasswordEnc());
                }
                if ((apiKey == null || apiKey.isBlank()) && existing.getApiKeyEnc() != null) {
                    apiKey = cryptoUtil.decrypt(existing.getApiKeyEnc());
                }
            }
        }
        MigrationConnectionKindAdapter adapter = adapters.forKind(form.getKind());
        MigrationConnectionEndpoint endpoint = adapter.normalize(form);
        entity.setBaseUrl(endpoint.baseUrl());
        entity.setDatabaseName(endpoint.databaseName());
        entity.setSchemaName(endpoint.schemaName());
        entity.setExtraParams(endpoint.extraParams());
        return adapter.testTransient(entity, password, apiKey);
    }

    /** Discovers copyable objects on the source connection, never touching the target. */
    public List<MigrationDiscoveryItem> listIndices(Long connectionId, String pattern) {
        MigrationConnection connection = connections.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "error.migration.connection.missing"));
        return adapters.forKind(connection.getKind()).list(connection, pattern);
    }
}
