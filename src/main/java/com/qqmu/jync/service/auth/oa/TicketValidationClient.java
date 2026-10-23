package com.qqmu.jync.service.auth.oa;

import com.qqmu.jync.model.OaAuthConfig;

/** Validates a one-time ticket against the login centre configured by the administrator. */
public interface TicketValidationClient {

    OaAuthResult validate(OaAuthConfig config, String ticket);
}
