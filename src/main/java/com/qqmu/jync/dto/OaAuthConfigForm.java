package com.qqmu.jync.dto;

import com.qqmu.jync.model.OaAuthConfig;

import lombok.Getter;
import lombok.Setter;

/**
 * What the OA sign-in settings form may submit.
 *
 * <p>Deliberately has no credential fields: Jync never stores an OA password, so there is
 * nothing here for a crafted POST to smuggle into the settings row.
 */
@Getter
@Setter
public class OaAuthConfigForm {

    private boolean enabled;
    private String loginUrl;
    private OaAuthConfig.RequestFormat requestFormat = OaAuthConfig.RequestFormat.FORM;
    private String usernameField;
    private String passwordField;
    private String successField;
    private String successValue;
    private String userField;
    private String adminField;
    private String adminValue;
    private Integer timeoutMs;
    private boolean allowInsecureHttp;

    /** A fresh entity carrying only the submitted fields; the service owns the id and defaults. */
    public OaAuthConfig toEntity() {
        OaAuthConfig config = new OaAuthConfig();
        config.setEnabled(enabled);
        config.setLoginUrl(loginUrl);
        config.setRequestFormat(requestFormat);
        config.setUsernameField(usernameField);
        config.setPasswordField(passwordField);
        config.setSuccessField(successField);
        config.setSuccessValue(successValue);
        config.setUserField(userField);
        config.setAdminField(adminField);
        config.setAdminValue(adminValue);
        config.setTimeoutMs(timeoutMs);
        config.setAllowInsecureHttp(allowInsecureHttp);
        return config;
    }
}
