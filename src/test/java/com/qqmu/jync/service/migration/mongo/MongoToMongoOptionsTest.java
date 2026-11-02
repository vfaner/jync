package com.qqmu.jync.service.migration.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqmu.jync.service.migration.MigrationTargetNaming;
import com.qqmu.jync.service.migration.MigrationWriteMode;

class MongoToMongoOptionsTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Test
    void theDefaultsAreTheSafeOnes() {
        MongoToMongoOptions options = MongoToMongoOptions.parse(JSON.createObjectNode());

        assertThat(options.getCollectionsText()).isEmpty();
        assertThat(options.getTargetNaming()).isEqualTo(MigrationTargetNaming.SAME);
        assertThat(options.getTargetSuffix()).isEqualTo("_migrated");
        assertThat(options.getWriteMode()).isEqualTo(MigrationWriteMode.OVERWRITE);
        assertThat(options.isCopyCollectionOptions()).isTrue();
        assertThat(options.isCopyIndexes()).isTrue();
        assertThat(options.isCopyDocuments()).isTrue();
        assertThat(options.isContinueOnError()).isTrue();
        assertThat(options.getBatchSize()).isEqualTo(1000);
        assertThat(options.getMaxBatchBytes()).isEqualTo(8L * 1024 * 1024);
        assertThat(options.isNoCursorTimeout()).isTrue();
        assertThat(options.isVerifyCount()).isTrue();
        assertThat(options.getVerifySampleSize()).isEqualTo(100);
        assertThat(options.getMaxCollections()).isEqualTo(200);
    }

    @Test
    void anUnknownEnumIsRejectedInsteadOfFallingBackToADestructiveDefault() {
        ObjectNode node = JSON.createObjectNode().put("writeMode", "MERGE");

        assertThatThrownBy(() -> MongoToMongoOptions.parse(node))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.options.writeMode.invalid");
    }

    @Test
    void batchBoundsAreEnforced() {
        assertThatThrownBy(() -> MongoToMongoOptions.parse(
                JSON.createObjectNode().put("batchSize", 0)))
                .hasMessage("error.migration.options.batchSize.invalid");
        assertThatThrownBy(() -> MongoToMongoOptions.parse(
                JSON.createObjectNode().put("batchSize", 10001)))
                .hasMessage("error.migration.options.batchSize.invalid");
        assertThatThrownBy(() -> MongoToMongoOptions.parse(
                JSON.createObjectNode().put("maxBatchBytes", 1024)))
                .hasMessage("error.migration.options.maxBatchBytes.invalid");
    }

    @Test
    void theTargetNameFollowsTheChosenNaming() {
        assertThat(options(MigrationTargetNaming.SAME, "", "").targetName("orders"))
                .isEqualTo("orders");
        assertThat(options(MigrationTargetNaming.PREFIX, "copy_", "").targetName("orders"))
                .isEqualTo("copy_orders");
        assertThat(options(MigrationTargetNaming.SUFFIX, "", "_new").targetName("orders"))
                .isEqualTo("orders_new");
    }

    @Test
    void anIllegalTargetNameIsRejected() {
        MongoToMongoOptions suffix = options(MigrationTargetNaming.SUFFIX, "", "\u0000");
        assertThatThrownBy(() -> suffix.targetName("orders"))
                .hasMessage("error.migration.options.targetName.invalid");

        MongoToMongoOptions dollar = options(MigrationTargetNaming.SUFFIX, "", "$bad");
        assertThatThrownBy(() -> dollar.targetName("orders"))
                .hasMessage("error.migration.options.targetName.invalid");

        MongoToMongoOptions system = options(MigrationTargetNaming.PREFIX, "system.", "");
        assertThatThrownBy(() -> system.targetName("orders"))
                .hasMessage("error.migration.options.targetName.invalid");

        MongoToMongoOptions tooLong = options(MigrationTargetNaming.SUFFIX, "", "x".repeat(120));
        assertThatThrownBy(() -> tooLong.targetName("orders"))
                .hasMessage("error.migration.options.targetName.invalid");
    }

    @Test
    void jsonRoundTripsThroughParse() {
        MongoToMongoOptions original = MongoToMongoOptions.parse(JSON.createObjectNode()
                .put("collectionsText", "orders, users")
                .put("targetNaming", "SUFFIX")
                .put("targetSuffix", "_copy")
                .put("writeMode", "APPEND")
                .put("batchSize", 250)
                .put("copyIndexes", false));

        MongoToMongoOptions reparsed = MongoToMongoOptions.parse(original.toJson());

        assertThat(reparsed.toJson()).isEqualTo(original.toJson());
        assertThat(reparsed.patterns()).containsExactly("orders", "users");
    }

    private static MongoToMongoOptions options(MigrationTargetNaming naming, String prefix,
                                               String suffix) {
        return MongoToMongoOptions.parse(JSON.createObjectNode()
                .put("targetNaming", naming.name())
                .put("targetPrefix", prefix)
                .put("targetSuffix", suffix));
    }
}
