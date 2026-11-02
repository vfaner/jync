package com.qqmu.jync.service.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.qqmu.jync.dto.MigrationConnectionForm;
import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.repository.MigrationConnectionRepository;
import com.qqmu.jync.repository.MigrationTaskRepository;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.migration.es.SearchHttpClient;
import com.qqmu.jync.service.migration.es.SearchHttpClientFactory;
import com.qqmu.jync.util.CryptoUtil;

import lombok.extern.slf4j.Slf4j;

/** CRUD, validation and probing for dedicated-migration search endpoints. */
@Service
@Slf4j
public class MigrationConnectionService {

    private final MigrationConnectionRepository connections;
    private final MigrationTaskRepository tasks;
    private final CryptoUtil cryptoUtil;
    private final AdminAuditService auditService;
    private final SearchHttpClientFactory clientFactory;

    public MigrationConnectionService(MigrationConnectionRepository connections,
                                      MigrationTaskRepository tasks,
                                      CryptoUtil cryptoUtil,
                                      AdminAuditService auditService,
                                      SearchHttpClientFactory clientFactory) {
        this.connections = connections;
        this.tasks = tasks;
        this.cryptoUtil = cryptoUtil;
        this.auditService = auditService;
        this.clientFactory = clientFactory;
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
        validate(form);

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

        entity.setName(form.getName());
        entity.setKind(form.getKind());
        entity.setBaseUrl(normalizeUrl(form.getBaseUrl()));
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
                (isNew ? "Created" : "Updated") + " dedicated-migration connection '"
                        + saved.getName() + "' (" + saved.getKind() + ")");
        if (!saved.isVerifyTls()) {
            auditService.record(AuditAction.MIGRATION_CONNECTION_SAVE,
                    "TLS verification disabled for dedicated-migration connection '"
                            + saved.getName() + "'");
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

    private void validate(MigrationConnectionForm form) {
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
        if (form.getAuthType() == MigrationConnectionAuthType.BASIC
                && (form.getUsername() == null || form.getUsername().isBlank())) {
            throw new IllegalArgumentException("error.migration.connection.username.required");
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

    private static String normalizeUrl(String raw) {
        try {
            return SearchHttpClient.normalizeBaseUrl(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("error.migration.connection.url.invalid");
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
                "Deleted dedicated-migration connection '" + victim.getName() + "' ("
                        + victim.getKind() + ")");
        log.info("Deleted dedicated-migration connection {}", id);
    }

    public TestResult test(Long id) {
        MigrationConnection connection = connections.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "error.migration.connection.missing"));
        return probe(clientFactory.create(connection));
    }

    /**
     * Tests form values before saving. A saved form with blank secrets (the edit page never
     * receives them) falls back to the stored values.
     */
    public TestResult testTransient(MigrationConnectionForm form, String rawPassword,
                                    String rawApiKey) {
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
        entity.setBaseUrl(normalizeUrl(form.getBaseUrl()));
        return probe(new SearchHttpClient(entity, password, apiKey));
    }

    private TestResult probe(SearchHttpClient client) {
        long start = System.nanoTime();
        try {
            JsonNode root = client.get("/");
            String product = root.path("version").path("distribution").asText("");
            if (product.isBlank()) {
                product = root.path("tagline").asText("unknown");
            }
            String version = root.path("version").path("number").asText(null);
            String cluster = root.path("cluster_name").asText(null);
            return new TestResult(true, null, product, version, cluster, elapsedMs(start));
        } catch (RuntimeException e) {
            return new TestResult(false, e.getMessage(), null, null, null, elapsedMs(start));
        }
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** Non-system indices only, unless the user's own pattern explicitly asks for one. */
    public List<IndexInfo> listIndices(Long connectionId, String pattern) {
        MigrationConnection connection = connections.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "error.migration.connection.missing"));
        SearchHttpClient client = clientFactory.create(connection);
        String effective = pattern == null ? "" : pattern.trim();
        JsonNode cat = client.get("/_cat/indices?format=json"
                + "&h=index,docs.count,store.size,status,health&expand_wildcards=open");
        List<IndexInfo> indices = new ArrayList<>();
        if (cat != null && cat.isArray()) {
            for (JsonNode row : cat) {
                String name = row.path("index").asText("");
                if (name.isBlank() || name.startsWith(".") && !asksForDotIndex(effective)) {
                    continue;
                }
                if (!matchesAny(name, effective)) {
                    continue;
                }
                indices.add(new IndexInfo(name, row.path("docs.count").asLong(0),
                        row.path("store.size").asText(""),
                        row.path("status").asText(""),
                        row.path("health").asText("")));
            }
        }
        return indices;
    }

    private static boolean asksForDotIndex(String pattern) {
        for (String part : pattern.split(",")) {
            if (part.trim().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAny(String index, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        for (String part : pattern.split(",")) {
            String glob = part.trim();
            if (!glob.isEmpty() && globMatches(index, glob)) {
                return true;
            }
        }
        return false;
    }

    private static boolean globMatches(String value, String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (char c : glob.toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString()).matcher(value).matches();
    }

    /** What the connection card shows after a successful probe. */
    public record TestResult(boolean success, String message, String product,
                             String version, String clusterName, long elapsedMs) {
    }

    /** A row of {@code _cat/indices}; discovery never touches the target. */
    public record IndexInfo(String index, long docsCount, String storeSize,
                            String status, String health) {
    }
}
