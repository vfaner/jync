package com.qqmu.jync.model;

import java.time.Instant;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * How Jync talks to an enterprise OA's own sign-in API.
 *
 * <p>There is exactly one row ({@code id = 1}); it holds no credentials of its own — only the
 * endpoint and the response-field mapping an administrator typed. OA passwords travel from the
 * login form straight to that endpoint and are never stored.
 */
@Entity
@Table(name = "oa_auth_config")
@Getter
@Setter
public class OaAuthConfig {

    /** How the username/password pair is put on the wire. */
    public enum RequestFormat {
        /** {@code application/x-www-form-urlencoded}. */
        FORM,
        /** JSON object keyed by the configured field names. */
        JSON
    }

    /** How a user's identity reaches this application. */
    public enum Mode {
        /** The sign-in form posts a username and password to the configured OA endpoint. */
        PASSWORD,
        /** A trusted reverse proxy injects the verified identity as a request header. */
        HEADER,
        /** A login centre issues a one-time ticket that is validated server-side. */
        TICKET
    }

    /** How the ticket is carried to the validation endpoint. */
    public enum ValidateMethod {
        GET,
        POST
    }

    @Id
    private Long id;

    @Column(nullable = false)
    private boolean enabled;

    /**
     * The column is {@code auth_mode}, not {@code mode}: MODE is a reserved word in Oracle and
     * its compatible databases (DM, YashanDB, Oscar), where Hibernate's unquoted DDL fails with
     * ORA-03050 before the table is even created. The property keeps its natural name; only the
     * storage name differs.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_mode", length = 16)
    private Mode mode = Mode.PASSWORD;

    /** Comma-separated usernames that are administrators in every mode. */
    @Column(name = "admin_users", length = 1024)
    private String adminUsers;

    /** Where an unauthenticated visitor is sent when there is no local form to show. */
    @Lob
    @Column(name = "login_entry_url")
    private String loginEntryUrl;

    /** Where sign-out sends the browser; blank means the local login page. */
    @Lob
    @Column(name = "logout_redirect_url")
    private String logoutRedirectUrl;

    /**
     * Null on rows written before this column existed. Null means "allowed": an upgrade must not
     * lock the local accounts out of an installation that never opted into SSO.
     */
    @Column(name = "local_login_enabled")
    private Boolean localLoginEnabled = Boolean.TRUE;

    @Column(name = "user_header", length = 128)
    private String userHeader;

    @Column(name = "admin_header", length = 128)
    private String adminHeader;

    @Column(name = "secret_header_name", length = 128)
    private String secretHeaderName;

    /** Ciphertext (see {@code CryptoUtil}); never rendered back into the form. */
    @Lob
    @Column(name = "secret_header_value")
    private String secretHeaderValue;

    @Lob
    @Column(name = "validate_url")
    private String validateUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "validate_method", length = 8)
    private ValidateMethod validateMethod = ValidateMethod.GET;

    @Column(name = "ticket_param", length = 64)
    private String ticketParam = "ticket";

    @Lob
    @Column(name = "login_url")
    private String loginUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_format", nullable = false, length = 8)
    private RequestFormat requestFormat = RequestFormat.FORM;

    @Column(name = "username_field", length = 128)
    private String usernameField = "username";

    @Column(name = "password_field", length = 128)
    private String passwordField = "password";

    /** Response field that decides success; blank means "HTTP 2xx is success". */
    @Column(name = "success_field", length = 128)
    private String successField;

    @Column(name = "success_value", length = 255)
    private String successValue;

    /**
     * Response field carrying the signed-in user's identifier (dot path, e.g. data.user).
     *
     * <p>No field initialiser: only password mode has a sensible default ({@code username}),
     * which {@code OaAuthConfigService} applies. Ticket mode requires the administrator to
     * name the field the login centre actually returns.
     */
    @Column(name = "user_field", length = 128)
    private String userField;

    /** Response field carrying the administrator marker; blank means every user is a viewer. */
    @Column(name = "admin_field", length = 128)
    private String adminField;

    /** Comma-separated accepted values for {@link #adminField}. */
    @Column(name = "admin_value", length = 255)
    private String adminValue;

    @Column(name = "timeout_ms")
    private Integer timeoutMs = 5000;

    /** Plain http is only sent after the administrator explicitly allows it. */
    @Column(name = "allow_insecure_http")
    private boolean allowInsecureHttp;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** A null mode is a pre-v1.8 row; it behaves exactly like PASSWORD. */
    public Mode modeOrDefault() {
        return mode == null ? Mode.PASSWORD : mode;
    }

    /** Null column (old row) means allowed; only an explicit FALSE disables the local form. */
    public boolean isLocalLoginAllowed() {
        return !Boolean.FALSE.equals(localLoginEnabled);
    }
}
