package com.qqmu.jync.service.migration.es;

/**
 * A non-2xx search-engine response.
 *
 * <p>The body is truncated and secrets are already redacted by {@link SearchHttpClient}; this
 * exception is stored in run history and rendered on the detail page.
 */
public class MigrationHttpException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String reason;
    private final String body;

    public MigrationHttpException(String method, String path, int status, String reason,
                                  String body) {
        super(method + " " + path + " -> " + status + " " + reason
                + (body == null || body.isBlank() ? "" : ": " + body));
        this.status = status;
        this.reason = reason;
        this.body = body;
    }

    public int getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getBody() {
        return body;
    }
}
