package com.qqmu.jync.service.connection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses, merges and renders the free-form {@code extraParams} JDBC string.
 *
 * <p>Kept next to {@link DataSourceManager} because that is where the runtime parser and the
 * unsafe-key blocklist live. The assistant edits only this string, never host, port, account
 * or password, and its card preview and the click both use {@link #merge} so the displayed
 * result is the saved result.
 */
public final class ConnectionParams {

    private ConnectionParams() {
    }

    /** Exact spelling to value, in stored order. */
    public static Map<String, String> parse(String extraParams) {
        Map<String, String> out = new LinkedHashMap<>();
        if (extraParams == null || extraParams.isBlank()) {
            return out;
        }
        for (String token : extraParams.trim().split("[&;]")) {
            int eq = token.indexOf('=');
            String key = (eq >= 0 ? token.substring(0, eq) : token).trim();
            String value = eq >= 0 ? token.substring(eq + 1).trim() : "";
            if (!key.isEmpty()) {
                out.put(key, value);
            }
        }
        return out;
    }

    /** Display form of one value; empty and absent are indistinguishable in the URL string. */
    public static String lookup(Map<String, String> params, String key) {
        if (key == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Applies a set/remove change to the stored string. Case-insensitive matching, existing
     * order preserved; a new key is appended. Every key and value is validated strictly, so
     * the preview cannot differ from what the driver would receive.
     */
    public static String merge(String existing, Map<String, String> set, List<String> remove) {
        Map<String, String> params = parse(existing);
        if (remove != null) {
            for (String key : remove) {
                DataSourceManager.validatedExtraParam(key, "");
                params.keySet().removeIf(k -> k.equalsIgnoreCase(key.trim()));
            }
        }
        if (set != null) {
            for (Map.Entry<String, String> entry : set.entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().trim();
                String value = entry.getValue() == null ? "" : entry.getValue().trim();
                DataSourceManager.validatedExtraParam(key, value);
                String existingKey = null;
                for (String candidate : params.keySet()) {
                    if (candidate.equalsIgnoreCase(key)) {
                        existingKey = candidate;
                        break;
                    }
                }
                if (existingKey != null) {
                    params.put(existingKey, value);
                } else {
                    params.put(key, value);
                }
            }
        }
        if (params.isEmpty()) {
            return null;
        }
        // Existing pairs are rendered as stored rather than re-validated: a legacy blocked
        // key (written before the runtime validator, which drops it at URL build time) must
        // not make an unrelated edit impossible. Newly set/removed keys were validated above.
        List<String> rendered = new ArrayList<>(params.size());
        for (Map.Entry<String, String> entry : params.entrySet()) {
            rendered.add(entry.getValue() == null || entry.getValue().isEmpty()
                    ? entry.getKey() : entry.getKey() + "=" + entry.getValue());
        }
        return String.join("&", rendered);
    }

    /**
     * A parameter whose value must never be shown to the model or editable by the assistant:
     * JDBC URLs accept account and password overrides, and a connection string is not a place
     * for the AI to handle credentials.
     */
    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("password") || lower.contains("passwd")
                || lower.contains("pwd") || lower.contains("secret")
                || lower.contains("token") || lower.contains("credential")
                || lower.contains("user") || lower.contains("key")
                || lower.contains("auth") || lower.contains("access");
    }

    /** Display copy with sensitive values replaced; keys are kept so the model knows why. */
    public static Map<String, String> redacted(Map<String, String> params) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            out.put(entry.getKey(), isSensitiveKey(entry.getKey())
                    ? "<redacted>" : entry.getValue());
        }
        return out;
    }

    /** Normalized view used for change detection (uppercase key to value). */
    public static Map<String, String> normalized(Map<String, String> params) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            out.put(entry.getKey().toUpperCase(Locale.ROOT), entry.getValue());
        }
        return out;
    }
}
