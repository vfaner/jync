package com.qqmu.jync.service.settings;

/** Keys this application stores in {@code system_setting}. */
public enum SystemSettingKey {

    /** Whether plaintext requests are refused; see {@link RequireHttpsResolver}. */
    SECURITY_REQUIRE_HTTPS("security.require-https"),

    /** Whether Jync asks GitHub/Gitee for newer releases; see {@code UpdateCheckResolver}. */
    UPDATE_CHECK_ENABLED("update-check.enabled");

    private final String key;

    SystemSettingKey(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
