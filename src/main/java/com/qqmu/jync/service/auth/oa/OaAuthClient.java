package com.qqmu.jync.service.auth.oa;

import com.qqmu.jync.model.OaAuthConfig;

/**
 * Calls the OA's sign-in API with a user-supplied username and password.
 *
 * <p>Implementations must never log the password or the response body, and must translate every
 * transport failure into an {@link OaAuthResult#failure(String)} instead of throwing.
 */
public interface OaAuthClient {

    OaAuthResult authenticate(OaAuthConfig config, String username, String password);
}
