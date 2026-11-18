package com.qqmu.jync.service.settings;

/** Keys this application stores in {@code system_setting}. */
public enum SystemSettingKey {

    /** Whether plaintext requests are refused; see {@link RequireHttpsResolver}. */
    SECURITY_REQUIRE_HTTPS("security.require-https"),

    /** Whether Jync asks GitHub/Gitee for newer releases; see {@code UpdateCheckResolver}. */
    UPDATE_CHECK_ENABLED("update-check.enabled"),

    /** Whether the AI assistant is on; see {@code AiAvailability}. */
    AI_ENABLED("ai.enabled"),

    /**
     * Local one-time marker: the {@code ops} account has already been seeded or deliberately
     * skipped. Excluded from configuration packages — it is machine state, not configuration.
     * Every {@code seed.*} key shares that rule.
     */
    LOCAL_OPS_SEEDED("seed.local-ops");

    private final String key;

    SystemSettingKey(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
