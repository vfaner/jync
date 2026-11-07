package com.qqmu.jync.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Audited and rendered URLs must never carry a fragment of the password. */
class JdbcUrlSanitizerTest {

    @Test
    void stripsUserInfoWhosePasswordContainsAnAtSign() {
        // An unencoded '@' in the password used to leave the tail of the password behind:
        // only the first '@' bounded the userinfo match.
        assertThat(JdbcUrlSanitizer.sanitize("jdbc:mysql://app:p@ss@db:3306/x"))
                .isEqualTo("jdbc:mysql://db:3306/x");
        assertThat(JdbcUrlSanitizer.sanitize("jdbc:postgresql://app:p@ss@db/x"))
                .isEqualTo("jdbc:postgresql://db/x");
    }

    @Test
    void keepsTheHostAndDatabaseNameThatMakeTheUrlUseful() {
        assertThat(JdbcUrlSanitizer.sanitize("jdbc:mysql://app:secret@db.example:3306/app"))
                .isEqualTo("jdbc:mysql://db.example:3306/app");
        assertThat(JdbcUrlSanitizer.sanitize("jdbc:postgresql://db.example/app"))
                .isEqualTo("jdbc:postgresql://db.example/app");
    }

    @Test
    void masksOracleThinCredentialsEvenWhenThePasswordContainsAnAtSign() {
        assertThat(JdbcUrlSanitizer.sanitize("jdbc:oracle:thin:app/secret@db:1521/orcl"))
                .isEqualTo("jdbc:oracle:thin:app/***@db:1521/orcl");
        assertThat(JdbcUrlSanitizer.sanitize("jdbc:oracle:thin:app/p@ss@db:1521/orcl"))
                .isEqualTo("jdbc:oracle:thin:app/***@db:1521/orcl");
    }

    @Test
    void masksPasswordAndUserQueryParameters() {
        assertThat(JdbcUrlSanitizer.sanitize(
                "jdbc:postgresql://db/app?user=app&password=p%40ss&ssl=true"))
                .isEqualTo("jdbc:postgresql://db/app?user=***&password=***&ssl=true");
    }

    @Test
    void leavesACredentialFreeUrlAlone() {
        assertThat(JdbcUrlSanitizer.sanitize("jdbc:h2:file:./data/jync"))
                .isEqualTo("jdbc:h2:file:./data/jync");
        assertThat(JdbcUrlSanitizer.sanitize(null)).isEmpty();
        assertThat(JdbcUrlSanitizer.sanitize("  ")).isEmpty();
    }
}
