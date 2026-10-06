package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Unexpected API failures must not echo the raw exception to the browser: SQL errors and
 * file paths are internals. The fixed i18n key is returned instead — the front-end
 * dictionary resolves it, and the raw cause stays in the server log.
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void unexpectedErrorsReturnTheI18nKeyNotTheRawMessage() {
        ResponseEntity<Map<String, Object>> response = handler.onUnexpected(
                new RuntimeException("ORA-01017: invalid username/password from /etc/jync/db.conf"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("success")).isEqualTo(false);
        assertThat(response.getBody().get("message")).isEqualTo("error.unexpected");
    }

    @Test
    void badRequestAndConflictKeepTheirServiceMessages() {
        // 400/409 的文案本来就是给用户的 i18n key（服务层抛出），不受 500 收敛影响
        assertThat(handler.onBadRequest(new IllegalArgumentException("error.project.name.required"))
                .getBody().get("message")).isEqualTo("error.project.name.required");
        assertThat(handler.onConflict(new IllegalStateException("error.project.stop.before.reset"))
                .getBody().get("message")).isEqualTo("error.project.stop.before.reset");
    }
}
