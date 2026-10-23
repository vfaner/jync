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

    @Id
    private Long id;

    @Column(nullable = false)
    private boolean enabled;

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

    /** Response field carrying the signed-in user's identifier (dot path, e.g. data.user). */
    @Column(name = "user_field", length = 128)
    private String userField = "username";

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
}
