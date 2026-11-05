package com.qqmu.jync.service.projection.redis;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.dto.RedisTargetForm;
import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.RedisTargetRepository;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.audit.AuditDetail;
import com.qqmu.jync.util.CryptoUtil;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import lombok.extern.slf4j.Slf4j;

/**
 * CRUD, validation and probing for Redis targets.
 *
 * <p>Separate from {@code DatabaseConfigService} on purpose: the JDBC connection flow keeps its
 * own entity, form, validation and pages, and none of it changes because a cache target exists.
 */
@Service
@Slf4j
public class RedisTargetService {

    private final RedisTargetRepository targets;
    private final ProjectRepository projects;
    private final CryptoUtil cryptoUtil;
    private final AdminAuditService auditService;

    public RedisTargetService(RedisTargetRepository targets, ProjectRepository projects,
                              CryptoUtil cryptoUtil, AdminAuditService auditService) {
        this.targets = targets;
        this.projects = projects;
        this.cryptoUtil = cryptoUtil;
        this.auditService = auditService;
    }

    public List<RedisTarget> findAll() {
        return targets.findAllByOrderByNameAsc();
    }

    public Optional<RedisTarget> find(Long id) {
        return targets.findById(id);
    }

    public RedisTargetForm formFor(Long id) {
        return targets.findById(id)
                .map(RedisTargetForm::from)
                .orElseThrow(() -> new IllegalArgumentException("error.redis.target.not.found"));
    }

    /**
     * Creates or updates a target. A blank password on edit keeps the stored one; the form never
     * receives ciphertext or plaintext from the browser.
     */
    @Transactional
    public RedisTarget save(RedisTargetForm form, String rawPassword) {
        validate(form);

        RedisTarget entity;
        boolean isNew = form.getId() == null;
        if (isNew) {
            targets.findByName(form.getName()).ifPresent(found -> {
                throw new IllegalArgumentException("error.redis.target.name.duplicate");
            });
            entity = new RedisTarget();
        } else {
            Long id = form.getId();
            targets.findByName(form.getName()).ifPresent(found -> {
                if (!found.getId().equals(id)) {
                    throw new IllegalArgumentException("error.redis.target.name.duplicate");
                }
            });
            // Reuse the loaded instance. A fresh entity built from the form has a null @Version,
            // so Spring Data would treat it as new and INSERT a duplicate row.
            entity = targets.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "error.redis.target.not.found"));
        }

        entity.setName(form.getName());
        entity.setHost(form.getHost());
        entity.setPort(form.getPort());
        entity.setClusterMode(form.isClusterMode());
        entity.setDatabaseIndex(form.isClusterMode() ? 0 : form.getDatabaseIndex());
        entity.setUsername(blankToNull(form.getUsername()));
        entity.setTlsEnabled(form.isTlsEnabled());
        entity.setVerifyTls(form.isVerifyTls());
        entity.setConnectTimeoutMs(form.getConnectTimeoutMs());
        entity.setCommandTimeoutMs(form.getCommandTimeoutMs());
        entity.setDescription(blankToNull(form.getDescription()));
        if (rawPassword != null && !rawPassword.isEmpty()) {
            entity.setPasswordEnc(cryptoUtil.encrypt(rawPassword));
        }

        RedisTarget saved = targets.save(entity);
        auditService.record(AuditAction.REDIS_TARGET_SAVE,
                AuditDetail.of("audit.detail.redisTargetSave",
                        isNew ? "@created" : "@updated", saved.getName()));
        if (saved.isTlsEnabled() && !saved.isVerifyTls()) {
            // Turning off certificate and hostname checks is a deliberate downgrade; the audit
            // trail has to say who chose it.
            auditService.record(AuditAction.REDIS_TARGET_SAVE,
                    AuditDetail.of("audit.detail.redisTargetTls", saved.getName()));
        }
        log.info("Saved Redis target '{}'", saved.getName());
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        RedisTarget victim = targets.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("error.redis.target.not.found"));
        if (projects.existsByTargetRedisId(id)) {
            throw new IllegalStateException("error.redis.target.inUse");
        }
        targets.deleteById(id);
        auditService.record(AuditAction.REDIS_TARGET_DELETE,
                AuditDetail.of("audit.detail.redisTargetDelete", victim.getName()));
        log.info("Deleted Redis target {}", id);
    }

    /**
     * Probes the endpoint with {@code PING}. A saved form with a blank password falls back to the
     * stored credential. Failure is returned, never thrown: the form renders it as a message.
     */
    public VerifyResult verify(RedisTargetForm form, String rawPassword) {
        String password = rawPassword;
        if ((password == null || password.isEmpty()) && form.getId() != null) {
            RedisTarget existing = targets.findById(form.getId()).orElse(null);
            if (existing != null && existing.getPasswordEnc() != null) {
                password = cryptoUtil.decrypt(existing.getPasswordEnc());
            }
        }
        RedisTarget target = form.toEntity();
        try {
            ping(target, password);
            return new VerifyResult(true, "redis.target.verify.ok", null);
        } catch (RuntimeException e) {
            return new VerifyResult(false, "redis.target.verify.failed", detailOf(e, password));
        }
    }

    private static void ping(RedisTarget target, String password) {
        RedisURI uri = RedisUris.from(target, password);
        Duration commandTimeout = Duration.ofMillis(
                target.getCommandTimeoutMs() > 0 ? target.getCommandTimeoutMs() : 10000);
        if (target.isClusterMode()) {
            RedisClusterClient client = RedisClusterClient.create(uri);
            try {
                client.setDefaultTimeout(commandTimeout);
                client.connect().sync().ping();
            } finally {
                client.shutdown();
            }
        } else {
            RedisClient client = RedisClient.create(uri);
            try {
                client.setDefaultTimeout(commandTimeout);
                client.connect().sync().ping();
            } finally {
                client.shutdown();
            }
        }
    }

    /** A short, password-free explanation for the connection form. */
    private static String detailOf(RuntimeException e, String password) {
        String message = e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage());
        if (password != null && !password.isEmpty()) {
            message = message.replace(password, "***");
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private static void validate(RedisTargetForm form) {
        if (form.getName() == null || form.getName().isBlank()) {
            throw new IllegalArgumentException("error.redis.target.name.required");
        }
        form.setName(form.getName().trim());
        if (form.getName().length() > 128) {
            throw new IllegalArgumentException("error.redis.target.name.too.long");
        }
        if (form.getHost() == null || form.getHost().isBlank()) {
            throw new IllegalArgumentException("error.redis.target.host.required");
        }
        form.setHost(form.getHost().trim());
        if (form.getHost().length() > 255) {
            throw new IllegalArgumentException("error.redis.target.host.too.long");
        }
        if (form.getPort() == null || form.getPort() < 1 || form.getPort() > 65535) {
            throw new IllegalArgumentException("error.redis.target.port.invalid");
        }
        int databaseIndex = form.getDatabaseIndex() == null ? 0 : form.getDatabaseIndex();
        // The form binds an emptied number input as null; the column is NOT NULL and
        // @PreUpdate does not normalise it, so an ordinary edit would fail with a 500. Write
        // the documented default back onto the form that save() copies from.
        form.setDatabaseIndex(databaseIndex);
        if (databaseIndex < 0) {
            throw new IllegalArgumentException("error.redis.target.database.invalid");
        }
        if (form.isClusterMode() && databaseIndex != 0) {
            throw new IllegalArgumentException("error.redis.target.cluster.database");
        }
        if (form.getConnectTimeoutMs() == null || form.getConnectTimeoutMs() < 500
                || form.getConnectTimeoutMs() > 60000) {
            throw new IllegalArgumentException("error.redis.target.timeout.invalid");
        }
        if (form.getCommandTimeoutMs() == null || form.getCommandTimeoutMs() < 1000
                || form.getCommandTimeoutMs() > 600000) {
            throw new IllegalArgumentException("error.redis.target.timeout.invalid");
        }
        form.setUsername(blankToNull(form.getUsername()));
        if (form.getUsername() != null && form.getUsername().length() > 255) {
            throw new IllegalArgumentException("error.redis.target.username.too.long");
        }
        form.setDescription(blankToNull(form.getDescription()));
        if (form.getDescription() != null && form.getDescription().length() > 512) {
            throw new IllegalArgumentException("error.redis.target.description.too.long");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
