package com.qqmu.jync.service.migration.es;

import com.fasterxml.jackson.databind.JsonNode;

/** One raw REST response. Non-2xx responses are represented here before being thrown. */
public record SearchResponse(int status, String reason, String body, JsonNode json) {

    public boolean isSuccessful() {
        return status >= 200 && status < 300;
    }

    public boolean isNotFound() {
        return status == 404;
    }
}
