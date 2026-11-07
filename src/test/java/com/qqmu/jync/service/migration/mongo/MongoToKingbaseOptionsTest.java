package com.qqmu.jync.service.migration.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.qqmu.jync.service.migration.MigrationTargetNaming;
import com.qqmu.jync.service.migration.MigrationWriteMode;

class MongoToKingbaseOptionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theDefaultsAreTheSafeOnes() {
        MongoToKingbaseOptions options = MongoToKingbaseOptions.parse(JSON.createObjectNode());

        assertThat(options.getCollectionsText()).isEmpty();
        assertThat(options.getTargetNaming()).isEqualTo(MigrationTargetNaming.SAME);
        assertThat(options.getTargetPrefix()).isEmpty();
        assertThat(options.getTargetSuffix()).isEmpty();
        assertThat(options.getWriteMode()).isEqualTo(MigrationWriteMode.OVERWRITE);
        assertThat(options.getNestedMode()).isEqualTo("JSON");
        assertThat(options.isCreatePrimaryKey()).isTrue();
        assertThat(options.isUpsertOnId()).isFalse();
        assertThat(options.isContinueOnError()).isTrue();
        assertThat(options.getBatchSize()).isEqualTo(1000);
        assertThat(options.getSampleLimit()).isEqualTo(200);
        assertThat(options.isVerifyCount()).isTrue();
        assertThat(options.getVerifySampleSize()).isEqualTo(100);
        assertThat(options.getMaxCollections()).isEqualTo(200);
    }

    @Test
    void onlyJsonNestedDocumentsAreAccepted() {
        assertThatThrownBy(() -> MongoToKingbaseOptions.parse(
                JSON.createObjectNode().put("nestedMode", "FLATTEN")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.options.nestedMode.invalid");
    }

    @Test
    void upsertOnIdRequiresAPrimaryKey() {
        assertThatThrownBy(() -> MongoToKingbaseOptions.parse(JSON.createObjectNode()
                .put("upsertOnId", true).put("createPrimaryKey", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.options.upsertOnId.invalid");

        MongoToKingbaseOptions options = MongoToKingbaseOptions.parse(
                JSON.createObjectNode().put("upsertOnId", true));
        assertThat(options.isUpsertOnId()).isTrue();
        assertThat(options.isCreatePrimaryKey()).isTrue();
    }

    @Test
    void anUnknownWriteModeIsRejectedInsteadOfFallingBackToADestructiveDefault() {
        assertThatThrownBy(() -> MongoToKingbaseOptions.parse(
                JSON.createObjectNode().put("writeMode", "MERGE")))
                .hasMessage("error.migration.options.writeMode.invalid");
    }

    @Test
    void boundsAreEnforced() {
        assertThatThrownBy(() -> MongoToKingbaseOptions.parse(
                JSON.createObjectNode().put("batchSize", 0)))
                .hasMessage("error.migration.options.batchSize.invalid");
        assertThatThrownBy(() -> MongoToKingbaseOptions.parse(
                JSON.createObjectNode().put("sampleLimit", 0)))
                .hasMessage("error.migration.options.sampleLimit.invalid");
        assertThatThrownBy(() -> MongoToKingbaseOptions.parse(
                JSON.createObjectNode().put("sampleLimit", 1001)))
                .hasMessage("error.migration.options.sampleLimit.invalid");
        assertThatThrownBy(() -> MongoToKingbaseOptions.parse(
                JSON.createObjectNode().put("verifySampleSize", -1)))
                .hasMessage("error.migration.options.verifySampleSize.invalid");
        assertThatThrownBy(() -> MongoToKingbaseOptions.parse(
                JSON.createObjectNode().put("maxCollections", 0)))
                .hasMessage("error.migration.options.maxCollections.invalid");
    }

    @Test
    void theJsonRoundTrips() {
        ObjectNode node = JSON.createObjectNode()
                .put("collectionsText", "orders,users")
                .put("targetNaming", "PREFIX")
                .put("targetPrefix", "m_")
                .put("targetSuffix", "_old")
                .put("writeMode", "APPEND")
                .put("nestedMode", "JSON")
                .put("createPrimaryKey", true)
                .put("upsertOnId", true)
                .put("continueOnError", false)
                .put("batchSize", 250)
                .put("sampleLimit", 50)
                .put("verifyCount", false)
                .put("verifySampleSize", 10)
                .put("maxCollections", 20);

        MongoToKingbaseOptions parsed = MongoToKingbaseOptions.parse(node);
        MongoToKingbaseOptions roundTripped = MongoToKingbaseOptions.parse(parsed.toJson());

        assertThat(roundTripped.toJson()).isEqualTo(parsed.toJson());
        assertThat(roundTripped.getCollectionsText()).isEqualTo("orders,users");
        assertThat(roundTripped.getBatchSize()).isEqualTo(250);
        assertThat(roundTripped.getSampleLimit()).isEqualTo(50);
        assertThat(roundTripped.isContinueOnError()).isFalse();
    }

    @Test
    void patternsAreSplitTheSameWayAsEveryOtherMigration() {
        MongoToKingbaseOptions options = MongoToKingbaseOptions.parse(
                JSON.createObjectNode().put("collectionsText", "orders, users\nevents"));

        assertThat(options.patterns()).containsExactly("orders", "users", "events");
    }
}
