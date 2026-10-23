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
    private OaAuthConfig.Mode mode = OaAuthConfig.Mode.PASSWORD;
    private String loginUrl;
    private OaAuthConfig.RequestFormat requestFormat = OaAuthConfig.RequestFormat.FORM;
    private String usernameField;
    private String passwordField;
    private String successField;
    private String successValue;
    private String userField;
    private String adminField;
    private String adminValue;
    private String adminUsers;
    private String loginEntryUrl;
    private String logoutRedirectUrl;
    private boolean localLoginEnabled = true;
    private String userHeader;
    private String adminHeader;
    private String secretHeaderName;
    /** Plain text typed into the form; blank means "keep what is stored". */
    private String secretHeaderValue;
    private boolean clearSecret;
    private String validateUrl;
    private OaAuthConfig.ValidateMethod validateMethod = OaAuthConfig.ValidateMethod.GET;
    private String ticketParam;
    private Integer timeoutMs;
    private boolean allowInsecureHttp;

    /** A fresh entity carrying only the submitted fields; the service owns the id and defaults. */
    public OaAuthConfig toEntity() {
        OaAuthConfig config = new OaAuthConfig();
        config.setEnabled(enabled);
        config.setMode(mode);
        config.setLoginUrl(loginUrl);
        config.setRequestFormat(requestFormat);
        config.setUsernameField(usernameField);
        config.setPasswordField(passwordField);
        config.setSuccessField(successField);
        config.setSuccessValue(successValue);
        config.setUserField(userField);
        config.setAdminField(adminField);
        config.setAdminValue(adminValue);
        config.setAdminUsers(adminUsers);
        config.setLoginEntryUrl(loginEntryUrl);
        config.setLogoutRedirectUrl(logoutRedirectUrl);
        config.setLocalLoginEnabled(Boolean.valueOf(localLoginEnabled));
        config.setUserHeader(userHeader);
        config.setAdminHeader(adminHeader);
        config.setSecretHeaderName(secretHeaderName);
        config.setSecretHeaderValue(secretHeaderValue);
        config.setValidateUrl(validateUrl);
        config.setValidateMethod(validateMethod);
        config.setTicketParam(ticketParam);
        config.setTimeoutMs(timeoutMs);
        config.setAllowInsecureHttp(allowInsecureHttp);
        return config;
    }
}
