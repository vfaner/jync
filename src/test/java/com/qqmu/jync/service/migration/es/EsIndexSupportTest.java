package com.qqmu.jync.service.migration.es;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class EsIndexSupportTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void settingsCleanupDropsClusterSpecificKeysAndKeepsAnalysis() throws Exception {
        JsonNode response = JSON.readTree("{\"orders-2026\":{\"settings\":{"
                + "\"index\":{"
                + "\"number_of_shards\":\"3\","
                + "\"number_of_replicas\":\"1\","
                + "\"refresh_interval\":\"30s\","
                + "\"max_result_window\":\"50000\","
                + "\"uuid\":\"abc\","
                + "\"creation_date\":\"1700000000000\","
                + "\"provided_name\":\"orders-2026\","
                + "\"version\":{\"created\":\"7172200\"},"
                + "\"history\":{\"uuid\":\"hist\"},"
                + "\"lifecycle\":{\"name\":\"hot\"},"
                + "\"default_pipeline\":\"p\","
                + "\"blocks\":{\"read_only\":\"true\",\"write\":\"true\"},"
                + "\"routing\":{\"allocation\":{\"initial_recovery\":{\"_id\":\"n\"}}},"
                + "\"store\":{\"snapshot\":{\"uuid\":\"s\"}},"
                + "\"resize\":{\"source\":{\"name\":\"src\"}}"
                + "},"
                + "\"analysis\":{\"analyzer\":{\"my_cn\":{\"type\":\"smartcn\"}}}"
                + "}}}");

        ObjectNode cleaned = EsIndexSupport.cleanSettings(response, "orders-2026");

        JsonNode index = cleaned.path("index");
        assertThat(index.path("number_of_shards").asString()).isEqualTo("3");
        assertThat(index.path("number_of_replicas").asString()).isEqualTo("1");
        assertThat(index.path("refresh_interval").asString()).isEqualTo("30s");
        assertThat(index.path("max_result_window").asString()).isEqualTo("50000");
        assertThat(cleaned.path("analysis").path("analyzer").path("my_cn").path("type").asString())
                .isEqualTo("smartcn");

        assertThat(index.has("uuid")).isFalse();
        assertThat(index.has("creation_date")).isFalse();
        assertThat(index.has("provided_name")).isFalse();
        assertThat(index.has("version")).isFalse();
        assertThat(index.path("history").has("uuid")).isFalse();
        assertThat(index.has("lifecycle")).isFalse();
        assertThat(index.has("default_pipeline")).isFalse();
        assertThat(index.path("blocks").has("read_only")).isFalse();
        assertThat(index.path("blocks").has("write")).isFalse();
        assertThat(index.path("routing").path("allocation").has("initial_recovery")).isFalse();
        assertThat(index.path("store").has("snapshot")).isFalse();
        assertThat(index.path("resize").has("source")).isFalse();
    }

    @Test
    void flatDottedSettingsAreCleanedToo() throws Exception {
        JsonNode response = JSON.readTree("{\"users\":{\"settings\":{"
                + "\"index.number_of_shards\":\"1\","
                + "\"index.uuid\":\"u\","
                + "\"index.frozen\":\"true\","
                + "\"index.lifecycle.name\":\"hot\""
                + "}}}");

        ObjectNode cleaned = EsIndexSupport.cleanSettings(response, "users");

        assertThat(cleaned.path("index").path("number_of_shards").asString()).isEqualTo("1");
        assertThat(cleaned.path("index").has("uuid")).isFalse();
        assertThat(cleaned.path("index").has("frozen")).isFalse();
        assertThat(cleaned.path("index").has("lifecycle")).isFalse();
    }

    @Test
    void globMatchingHandlesStarQuestionMarkAndLiterals() {
        List<String> patterns = List.of("orders-*", "audit-?", "users");
        assertThat(EsIndexSupport.matches("orders-2026", patterns)).isTrue();
        assertThat(EsIndexSupport.matches("audit-1", patterns)).isTrue();
        assertThat(EsIndexSupport.matches("audit-12", patterns)).isFalse();
        assertThat(EsIndexSupport.matches("users", patterns)).isTrue();
        assertThat(EsIndexSupport.matches("users-v2", patterns)).isFalse();
        // A regex metacharacter in the pattern is a literal index-name character.
        assertThat(EsIndexSupport.matches("a.b", List.of("a.b"))).isTrue();
        assertThat(EsIndexSupport.matches("axb", List.of("a.b"))).isFalse();
    }

    @Test
    void systemIndicesAreExcludedUnlessThePatternNamesOne() {
        assertThat(EsIndexSupport.isSystemIndex(".security")).isTrue();
        assertThat(EsIndexSupport.isSystemIndex("orders")).isFalse();
        assertThat(EsIndexSupport.isSystemIndex(".orders")).isTrue();
    }

    @Test
    void targetNamesAreValidatedAgainstElasticsearchRules() {
        assertThat(EsIndexSupport.targetName("orders", EsIndexSupport.TargetNaming.SAME,
                "", "_migrated")).isEqualTo("orders");
        assertThat(EsIndexSupport.targetName("orders",
                EsIndexSupport.TargetNaming.PREFIX, "bak_", "")).isEqualTo("bak_orders");
        assertThat(EsIndexSupport.targetName("orders",
                EsIndexSupport.TargetNaming.SUFFIX, "", "_v2")).isEqualTo("orders_v2");

        assertThatThrownBy(() -> EsIndexSupport.validateTargetName("Orders"))
                .hasMessage("error.migration.target.name.invalid");
        assertThatThrownBy(() -> EsIndexSupport.validateTargetName("_orders"))
                .hasMessage("error.migration.target.name.invalid");
        assertThatThrownBy(() -> EsIndexSupport.validateTargetName("orders/2026"))
                .hasMessage("error.migration.target.name.invalid");
        assertThatThrownBy(() -> EsIndexSupport.validateTargetName(""))
                .hasMessage("error.migration.target.name.invalid");
        assertThatThrownBy(() -> EsIndexSupport.validateTargetName("x".repeat(256)))
                .hasMessage("error.migration.target.name.invalid");
    }
}
