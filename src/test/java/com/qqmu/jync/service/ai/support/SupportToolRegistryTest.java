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

    /**
     * 界面要把失败的工具标成「失败」而不是「已完成」：判定必须看根对象的 error 键，
     * 业务数据里出现 lastError 之类的字段不能算工具失败；截断包装更不算。
     */
    @Test
    void anErrorObjectIsRecognisedByItsRootKey() {
        var ok = new SupportToolRegistry(List.of(tool("t", Map.of("lastError", "x"))));
        assertThat(ok.isError(ok.execute("t", "{}", context()))).isFalse();

        var unknown = new SupportToolRegistry(List.of(tool("t", Map.of())));
        assertThat(unknown.isError(unknown.execute("nope", "{}", context()))).isTrue();

        var huge = new SupportToolRegistry(
                List.of(tool("t", Map.of("text", "x".repeat(9000)))));
        assertThat(huge.isError(huge.execute("t", "{}", context()))).isFalse();
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
