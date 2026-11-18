package com.qqmu.jync.service.ai.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.auth.AccessGuard;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SupportToolRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private SupportTool tool(String name, Object result) {
        return new SupportTool() {
            public String name() { return name; }
            public String description() { return "test tool"; }
            public Map<String, Object> parameters() { return Map.of("type", "object"); }
            public Set<String> allowedKeys() { return Set.of("id"); }
            public Object execute(JsonNode args, ToolContext ctx) { return result; }
        };
    }

    @Test
    void definitionCarriesTheFunctionWrapper() {
        var registry = new SupportToolRegistry(List.of(tool("t", Map.of("ok", true))));
        assertThat(registry.definitions()).singleElement().satisfies(def -> {
            assertThat(def.get("type")).isEqualTo("function");
            assertThat(((Map<?, ?>) def.get("function")).get("name")).isEqualTo("t");
        });
    }

    @Test
    void unknownToolIsAnErrorForTheModelNotAnException() {
        var registry = new SupportToolRegistry(List.of(tool("t", Map.of())));
        assertThat(registry.execute("nope", "{}", context())).contains("error.support.tool.unknown");
    }

    @Test
    void unknownArgumentIsRejected() throws Exception {
        var registry = new SupportToolRegistry(List.of(tool("t", Map.of())));
        String out = registry.execute("t", "{\"id\":1,\"surprise\":2}", context());
        assertThat(mapper.readTree(out).path("error").asString())
                .isEqualTo("error.support.tool.args");
    }

    @Test
    void malformedArgumentsAreRejectedTheSameWay() {
        var registry = new SupportToolRegistry(List.of(tool("t", Map.of())));
        assertThat(registry.execute("t", "{\"id\":", context()))
                .contains("error.support.tool.args");
    }

    @Test
    void oversizedResultsAreTruncatedAndFlagged() {
        var registry = new SupportToolRegistry(List.of(tool("t", "x".repeat(9000))));
        String out = registry.execute("t", "{}", context());
        assertThat(out).contains("\"truncated\":true");
        assertThat(out.length()).isLessThan(9000);
    }

    @Test
    void aThrowingToolBecomesAnErrorObject() {
        SupportTool boom = new SupportTool() {
            public String name() { return "boom"; }
            public String description() { return ""; }
            public Map<String, Object> parameters() { return Map.of(); }
            public Set<String> allowedKeys() { return Set.of(); }
            public Object execute(JsonNode args, ToolContext ctx) {
                throw new IllegalStateException("kaboom");
            }
        };
        var registry = new SupportToolRegistry(List.of(boom));
        assertThat(registry.execute("boom", "{}", context()))
                .contains("error.support.tool.failed");
    }

    @Test
    void aToolErrorKeyPassesThroughUnchanged() {
        SupportTool refusing = new SupportTool() {
            public String name() { return "refuse"; }
            public String description() { return ""; }
            public Map<String, Object> parameters() { return Map.of(); }
            public Set<String> allowedKeys() { return Set.of(); }
            public Object execute(JsonNode args, ToolContext ctx) {
                throw new IllegalArgumentException("error.project.not.found");
            }
        };
        var registry = new SupportToolRegistry(List.of(refusing));
        assertThat(registry.execute("refuse", "{}", context()))
                .isEqualTo("{\"error\":\"error.project.not.found\"}");
    }

    @Test
    void duplicateToolNamesAreRefusedAtStartup() {
        assertThatThrownBy(() ->
                new SupportToolRegistry(List.of(tool("t", Map.of()), tool("t", Map.of()))))
                .isInstanceOf(IllegalStateException.class);
    }

    private ToolContext context() {
        return new ToolContext(mock(AccessGuard.class));
    }
}
