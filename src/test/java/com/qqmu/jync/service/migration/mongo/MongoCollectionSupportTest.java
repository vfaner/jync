package com.qqmu.jync.service.migration.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import org.bson.Document;
import org.junit.jupiter.api.Test;

class MongoCollectionSupportTest {

    @Test
    void collectionOptionsDropReadOnlyFields() {
        Document options = new Document("uuid", "abc")
                .append("name", "orders")
                .append("type", "collection")
                .append("capped", true)
                .append("size", 1048576L)
                .append("max", 1000L)
                .append("validator", new Document("qty", new Document("$gt", 0)));

        Document cleaned = MongoCollectionSupport.cleanOptions(options);

        assertThat(cleaned).doesNotContainKeys("uuid", "name", "type")
                .containsEntry("capped", true)
                .containsEntry("size", 1048576L)
                .containsEntry("max", 1000L);
        assertThat(cleaned.get("validator")).isInstanceOf(Document.class);
    }

    @Test
    void indexesDropServerManagedFields() {
        Document index = new Document("v", 2)
                .append("key", new Document("email", 1))
                .append("name", "email_1")
                .append("ns", "orders.users")
                .append("background", true)
                .append("unique", true)
                .append("sparse", true);

        Document cleaned = MongoCollectionSupport.cleanIndex(index);

        assertThat(cleaned).doesNotContainKeys("v", "ns", "background")
                .containsKey("key")
                .containsEntry("name", "email_1")
                .containsEntry("unique", true)
                .containsEntry("sparse", true);
    }

    @Test
    void theIdIndexIsRecognised() {
        assertThat(MongoCollectionSupport.isIdIndex(
                new Document("name", "_id_").append("key", new Document("_id", 1)))).isTrue();
        assertThat(MongoCollectionSupport.isIdIndex(
                new Document("name", "email_1").append("key", new Document("email", 1)))).isFalse();
    }

    @Test
    void systemCollectionsAreRecognised() {
        assertThat(MongoCollectionSupport.isSystem("system.views")).isTrue();
        assertThat(MongoCollectionSupport.isSystem("orders")).isFalse();
        assertThat(MongoCollectionSupport.isSystem(null)).isFalse();
    }
}
