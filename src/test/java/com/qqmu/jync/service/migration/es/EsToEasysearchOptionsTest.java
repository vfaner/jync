package com.qqmu.jync.service.migration.es;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class EsToEasysearchOptionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static EsToEasysearchOptions parse(String json) throws Exception {
        return EsToEasysearchOptions.parse((ObjectNode) JSON.readTree(json));
    }

    @Test
    void defaultsMatchTheDocumentedSafeStartingPoint() throws Exception {
        EsToEasysearchOptions options = parse("{}");

        assertThat(options.getIndicesText()).isEmpty();
        assertThat(options.getTargetNaming()).isEqualTo(EsToEasysearchOptions.TargetNaming.SAME);
        assertThat(options.getTargetPrefix()).isEmpty();
        assertThat(options.getTargetSuffix()).isEqualTo("_migrated");
        assertThat(options.getWriteMode()).isEqualTo(EsToEasysearchOptions.WriteMode.OVERWRITE);
        assertThat(options.isCopySettings()).isTrue();
        assertThat(options.isCopyMappings()).isTrue();
        assertThat(options.isCopyAliases()).isFalse();
        assertThat(options.isContinueOnError()).isTrue();
        assertThat(options.getBatchSize()).isEqualTo(1000);
        assertThat(options.getMaxBulkBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(options.getScrollTtl()).isEqualTo("5m");
        assertThat(options.isVerifyCount()).isTrue();
        assertThat(options.getVerifySampleSize()).isEqualTo(100);
        assertThat(options.isProbeCompatibility()).isTrue();
        assertThat(options.getMaxIndices()).isEqualTo(200);
    }

    @Test
    void indexPatternsSplitOnCommasAndNewlinesAndDropBlanks() throws Exception {
        EsToEasysearchOptions options = parse(
                "{\"indicesText\":\" orders-*,\\n users \\n\\n audit-? , \"}");

        assertThat(options.patterns()).containsExactly("orders-*", "users", "audit-?");
    }

    @Test
    void blankPatternsMeanEveryNonSystemIndex() throws Exception {
        assertThat(parse("{}").patterns()).isEmpty();
        assertThat(EsToEasysearchOptions.parse((ObjectNode) JSON.readTree("{}"))
                .patterns()).isEmpty();
    }

    @Test
    void targetNamesHonourSamePrefixAndSuffix() throws Exception {
        EsToEasysearchOptions same = parse("{}");
        assertThat(same.targetName("orders-2026")).isEqualTo("orders-2026");

        EsToEasysearchOptions prefix = parse(
                "{\"targetNaming\":\"PREFIX\",\"targetPrefix\":\"bak_\"}");
        assertThat(prefix.targetName("orders-2026")).isEqualTo("bak_orders-2026");

        EsToEasysearchOptions suffix = parse(
                "{\"targetNaming\":\"SUFFIX\",\"targetSuffix\":\"_v2\"}");
        assertThat(suffix.targetName("orders-2026")).isEqualTo("orders-2026_v2");
    }

    @Test
    void anInvalidTargetNameIsRejectedWithAKey() throws Exception {
        EsToEasysearchOptions upper = parse(
                "{\"targetNaming\":\"PREFIX\",\"targetPrefix\":\"BAD_\"}");
        assertThatThrownBy(() -> upper.targetName("orders"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.target.name.invalid");

        EsToEasysearchOptions tooLong = parse(
                "{\"targetNaming\":\"SUFFIX\",\"targetSuffix\":\""
                        + "x".repeat(250) + "\"}");
        assertThatThrownBy(() -> tooLong.targetName("orders"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.target.name.invalid");
    }

    @Test
    void invalidEnumValuesAndLimitsAreRejected() throws Exception {
        assertThatThrownBy(() -> parse("{\"targetNaming\":\"FANCY\"}"))
                .hasMessage("error.migration.options.targetNaming.invalid");
        assertThatThrownBy(() -> parse("{\"writeMode\":\"MERGE\"}"))
                .hasMessage("error.migration.options.writeMode.invalid");
        assertThatThrownBy(() -> parse("{\"scrollTtl\":\"5 minutes\"}"))
                .hasMessage("error.migration.options.scrollTtl.invalid");
        assertThatThrownBy(() -> parse("{\"batchSize\":0}"))
                .hasMessage("error.migration.options.batchSize.invalid");
        assertThatThrownBy(() -> parse("{\"batchSize\":10001}"))
                .hasMessage("error.migration.options.batchSize.invalid");
        assertThatThrownBy(() -> parse("{\"maxBulkBytes\":1024}"))
                .hasMessage("error.migration.options.maxBulkBytes.invalid");
        assertThatThrownBy(() -> parse("{\"verifySampleSize\":5000}"))
                .hasMessage("error.migration.options.verifySampleSize.invalid");
        assertThatThrownBy(() -> parse("{\"maxIndices\":0}"))
                .hasMessage("error.migration.options.maxIndices.invalid");
    }

    @Test
    void unknownFieldsAreIgnoredAndKnownOnesSurviveARoundTrip() throws Exception {
        EsToEasysearchOptions options = parse(
                "{\"batchSize\":250,\"futureField\":\"ignored\",\"copyAliases\":true}");

        assertThat(options.getBatchSize()).isEqualTo(250);
        assertThat(options.isCopyAliases()).isTrue();
        assertThat(options.toJson().path("futureField").isMissingNode()).isTrue();
        assertThat(EsToEasysearchOptions.parse((ObjectNode) options.toJson())
                .getBatchSize()).isEqualTo(250);
    }
}
