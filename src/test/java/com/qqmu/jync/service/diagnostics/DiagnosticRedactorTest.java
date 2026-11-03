package com.qqmu.jync.service.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The second line of defence behind the bundle whitelist: anything that still looks like a
 * credential is replaced before it reaches an entry.
 */
class DiagnosticRedactorTest {

    private final DiagnosticRedactor redactor = new DiagnosticRedactor();

    @Test
    void redactsAssignmentValues() {
        String out = redactor.redact("password=secret1 token: abc api_key = k1"
                + " credential=cred secret = s");
        assertThat(out).doesNotContain("secret1", "abc", "= k1", "cred", " = s");
        assertThat(out).contains("<redacted>");
    }

    @Test
    void redactsEncryptedPayloads() {
        String out = redactor.redact("passwordEnc=enc:AbCdEf+/123=");
        assertThat(out).doesNotContain("AbCdEf+/123=");
        assertThat(out).contains("enc:<redacted>");
    }

    @Test
    void redactsBearerTokens() {
        String out = redactor.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig");
        assertThat(out).doesNotContain("eyJhbGci", "payload.sig");
        assertThat(out).contains("<redacted>");
    }

    @Test
    void redactsUrlUserinfoAndSensitiveQueryParameters() {
        String out = redactor.redact("jdbc:mysql://user:pa55@db:3306/app?token=tok123&password=pq");
        assertThat(out).doesNotContain("pa55", "tok123", "pq");
        assertThat(out).contains("db:3306");
    }

    @Test
    void redactsPrivateKeyBlocks() {
        String out = redactor.redact("before\n-----BEGIN RSA PRIVATE KEY-----\nMIIEow\n"
                + "-----END RSA PRIVATE KEY-----\nafter");
        assertThat(out).doesNotContain("MIIEow");
        assertThat(out).contains("[redacted private key]", "before", "after");
    }

    @Test
    void doesNotTouchWordsThatMerelyStartLikeSecrets() {
        String text = "tokenizer=words passwordless=true secretive value";
        assertThat(redactor.redact(text)).isEqualTo(text);
    }

    @Test
    void isNullSafeAndIdempotent() {
        assertThat(redactor.redact(null)).isEmpty();
        assertThat(redactor.redact("")).isEmpty();
        String once = redactor.redact("password=hunter2 and Bearer abc.def");
        assertThat(redactor.redact(once)).isEqualTo(once);
    }

    @Test
    void truncatesLongLines() {
        String out = redactor.redact("x".repeat(10_000));
        assertThat(out).hasSize(4000);
    }
}
