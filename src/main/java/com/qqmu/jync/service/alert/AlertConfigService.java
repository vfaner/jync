package com.qqmu.jync.service.alert;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.qqmu.jync.dto.AlertConfigForm;
import com.qqmu.jync.model.AlertCategory;
import com.qqmu.jync.model.AlertConfig;
import com.qqmu.jync.model.AlertWebhookFormat;
import com.qqmu.jync.repository.AlertConfigRepository;
import com.qqmu.jync.util.CryptoUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * Reads and writes the single alerting settings row.
 *
 * <p>Validation throws i18n keys rather than binding errors: the settings page shows them
 * inline, and the evaluator must never start posting to a half-configured endpoint.
 */
@Service
@Slf4j
public class AlertConfigService {

    /** Fixed primary key; the table is a settings singleton. */
    public static final long SINGLETON_ID = 1L;

    public static final int DEFAULT_COOLDOWN_MINUTES = 30;
    public static final int MIN_COOLDOWN_MINUTES = 5;
    public static final int MAX_COOLDOWN_MINUTES = 1440;

    /** Long enough for a real webhook URL; short enough not to store an unbounded blob. */
    static final int MAX_URL_LENGTH = 2048;

    private final AlertConfigRepository repository;
    private final CryptoUtil crypto;

    /**
     * Whether a webhook may point inside the private network. Off by default: the URL is typed
     * by an administrator but fetched by this process, so a loopback or RFC 1918 address lets
     * "save a webhook" become a request to an internal service. Intranet deployments that
     * genuinely post to a private address turn it on.
     */
    private final boolean allowPrivateWebhook;

    public AlertConfigService(AlertConfigRepository repository, CryptoUtil crypto,
                              @Value("${alert.webhook.allow-private:false}")
                              boolean allowPrivateWebhook) {
        this.repository = repository;
        this.crypto = crypto;
        this.allowPrivateWebhook = allowPrivateWebhook;
    }

    /** The stored settings, or an all-default disabled instance when nothing was saved yet. */
    @Transactional(readOnly = true)
    public AlertConfig load() {
        AlertConfig config = repository.findById(SINGLETON_ID).orElseGet(AlertConfig::new);
        if (config.getFormat() == null) {
            config.setFormat(AlertWebhookFormat.GENERIC);
        }
        if (config.getCooldownMinutes() == null) {
            config.setCooldownMinutes(DEFAULT_COOLDOWN_MINUTES);
        }
        return config;
    }

    /** The decrypted URL the notifier posts to, or null when none is stored. */
    @Transactional(readOnly = true)
    public String webhookUrlOrNull() {
        AlertConfig config = repository.findById(SINGLETON_ID).orElse(null);
        return config == null ? null : decryptOrNull(config.getWebhookUrl());
    }

    /** The tail of the saved URL, for the settings page; null when none is stored. */
    @Transactional(readOnly = true)
    public String maskedUrl() {
        String url;
        try {
            url = webhookUrlOrNull();
        } catch (IllegalStateException e) {
            // Ciphertext under a key this instance no longer holds — the key file was replaced
            // or sync.crypto-* changed. The page must still open: it is where the URL gets
            // re-entered, so report it as not set (the controller's storedUrl() does the same).
            log.warn("Stored webhook URL cannot be decrypted with the current crypto key; "
                    + "showing it as not set: {}", e.toString());
            return null;
        }
        if (url == null) {
            return null;
        }
        String tail = url.length() <= 4 ? url : url.substring(url.length() - 4);
        return "••••" + tail;
    }

    /**
     * Stores the submitted settings, keeping the stored URL when the form left it blank.
     *
     * @throws IllegalArgumentException carrying an i18n key when the input is unusable
     */
    @Transactional
    public void save(AlertConfigForm form) {
        AlertConfig config = repository.findById(SINGLETON_ID).orElseGet(() -> {
            AlertConfig created = new AlertConfig();
            created.setId(SINGLETON_ID);
            return created;
        });

        String typed = StringUtils.hasText(form.getWebhookUrl())
                ? form.getWebhookUrl().trim() : null;
        if (typed != null) {
            validateUrl(typed);
        }
        boolean willHaveUrl = typed != null || (!form.isClearWebhookUrl()
                && hasReadableStoredUrl(config));
        if (form.isEnabled() && !willHaveUrl) {
            throw new IllegalArgumentException("error.alert.url.required");
        }

        Set<AlertCategory> categories = parseCategories(form.getCategories());
        if (form.isEnabled() && categories.isEmpty()) {
            throw new IllegalArgumentException("error.alert.categories.required");
        }
        int cooldown = form.getCooldownMinutes() == null
                ? DEFAULT_COOLDOWN_MINUTES : form.getCooldownMinutes();
        if (cooldown < MIN_COOLDOWN_MINUTES || cooldown > MAX_COOLDOWN_MINUTES) {
            throw new IllegalArgumentException("error.alert.cooldown");
        }
        String language = normalizeLanguage(form.getLanguage());

        config.setEnabled(form.isEnabled());
        config.setLanguage(language);
        config.setFormat(form.getFormat() == null
                ? AlertWebhookFormat.GENERIC : form.getFormat());
        config.setCategories(AlertCategory.toCsv(categories));
        config.setCooldownMinutes(cooldown);
        // A replacement URL wins over the clear checkbox: the checkbox stays visible while the
        // user types, and letting it win would silently drop the new URL.
        if (typed != null) {
            config.setWebhookUrl(crypto.encrypt(typed));
        } else if (form.isClearWebhookUrl()) {
            config.setWebhookUrl(null);
        }
        config.setUpdatedAt(Instant.now());
        repository.save(config);
    }

    /**
     * Validates and normalises a row restored from a configuration package, in place.
     *
     * <p>Import writes the singleton straight through the repository, so without this call a
     * package exported from a hand-edited database could install states the page refuses —
     * alerting enabled with no webhook, a cooldown the evaluator would read as zero, an unknown
     * category name. The secret is already re-keyed to this instance by the time it gets here.
     *
     * @throws IllegalArgumentException carrying an i18n key when the row is unusable
     */
    public void validateImported(AlertConfig config) {
        config.setId(SINGLETON_ID);
        config.setFormat(config.getFormat() == null
                ? AlertWebhookFormat.GENERIC : config.getFormat());
        config.setLanguage(normalizeLanguage(config.getLanguage()));

        int cooldown = config.getCooldownMinutes() == null
                ? DEFAULT_COOLDOWN_MINUTES : config.getCooldownMinutes();
        if (cooldown < MIN_COOLDOWN_MINUTES || cooldown > MAX_COOLDOWN_MINUTES) {
            throw new IllegalArgumentException("error.alert.cooldown");
        }
        config.setCooldownMinutes(cooldown);

        // Categories are left as exported: unlike the page, the evaluator reads null/blank as
        // "every category" and skips names a newer version wrote, so neither is a broken row.

        String url = decryptOrNull(config.getWebhookUrl());
        if (url != null) {
            validateUrl(url);
        }
        if (config.isEnabled() && url == null) {
            throw new IllegalArgumentException("error.alert.url.required");
        }
        config.setUpdatedAt(Instant.now());
    }

    /**
     * The alert language is stored as one of two canonical values, because the evaluator maps
     * it straight to a bundle locale. Accepts a plain language tag ({@code en}, {@code en-US},
     * {@code zh-CN}); anything else is refused rather than silently falling back.
     */
    static String normalizeLanguage(String value) {
        if (!StringUtils.hasText(value)) {
            return AlertConfig.LANGUAGE_ZH_CN;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (normalized.equals("zh") || normalized.startsWith("zh_")) {
            return AlertConfig.LANGUAGE_ZH_CN;
        }
        if (normalized.equals("en") || normalized.startsWith("en_")) {
            return AlertConfig.LANGUAGE_EN;
        }
        throw new IllegalArgumentException("error.alert.language");
    }

    private static Set<AlertCategory> parseCategories(List<String> names) {
        Set<AlertCategory> categories = EnumSet.noneOf(AlertCategory.class);
        if (names == null) {
            return categories;
        }
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                categories.add(AlertCategory.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("error.alert.categories.invalid");
            }
        }
        return categories;
    }

    /**
     * Validates a webhook URL typed on the settings page without saving it.
     *
     * <p>The "send a test message" button posts to the typed URL, so it performs the same
     * server-side fetch as a saved webhook and must answer to the same address policy —
     * otherwise the private-address refusal is bypassed by pressing Test.
     */
    public void validateWebhookUrl(String url) {
        validateUrl(url);
    }

    private void validateUrl(String url) {
        if (url.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("error.alert.url.invalid");
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null
                    ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("error.alert.url.invalid");
            }
            // Resolve the host to refuse addresses that only mean something from inside this
            // host or network. A host that does not resolve right now is accepted: an intranet
            // webhook may be unreachable from this node (or DNS may be down), and the POST
            // will report the failure anyway.
            try {
                InetAddress address = InetAddress.getByName(uri.getHost());
                if (isNeverAPublicWebhookTarget(address)) {
                    throw new IllegalArgumentException("error.alert.url.invalid");
                }
                if (!allowPrivateWebhook && isPrivateOrReserved(address)) {
                    throw new IllegalArgumentException("error.alert.url.private");
                }
            } catch (UnknownHostException e) {
                // Accepted; see above.
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("error.alert.url.invalid");
        }
    }

    /**
     * Addresses with no public meaning at all: "any address", multicast, link-local (which
     * includes the cloud metadata endpoints 169.254.169.254 and 100.100.100.200, the latter
     * reported by Java as neither link-local nor site-local and therefore named explicitly).
     * Refused even when private targets are allowed.
     */
    private static boolean isNeverAPublicWebhookTarget(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isMulticastAddress()
                || address.isLinkLocalAddress()
                || "100.100.100.200".equals(address.getHostAddress());
    }

    /**
     * Loopback, RFC 1918, IPv6 unique-local (fc00::/7), carrier-grade NAT, benchmarking and
     * the reserved 240/4 block. Java reports IPv4-mapped literals ({@code ::ffff:10.1.2.3})
     * as {@link java.net.Inet4Address}, so the byte checks below cover both spellings.
     */
    private static boolean isPrivateOrReserved(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            return (bytes[0] & 0xFE) == 0xFC;
        }
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        return first == 0                                   // 0.0.0.0/8 "this network"
                || (first == 100 && (second & 0xC0) == 64)  // 100.64/10 carrier-grade NAT
                || (first == 198 && (second == 18 || second == 19)) // 198.18/15 benchmarking
                || first >= 240;                            // 240/4 reserved
    }

    /**
     * Whether the stored value is a URL the notifier could actually use.
     *
     * <p>Ciphertext under a key this instance no longer holds is not one: the page reports it as
     * not set and enabling must demand a new one, instead of saving an enabled config whose URL
     * nothing can decrypt.
     */
    private boolean hasReadableStoredUrl(AlertConfig config) {
        String stored = config.getWebhookUrl();
        if (stored == null || stored.isEmpty()) {
            return false;
        }
        try {
            crypto.decrypt(stored);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private String decryptOrNull(String stored) {
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        return crypto.decrypt(stored);
    }
}
