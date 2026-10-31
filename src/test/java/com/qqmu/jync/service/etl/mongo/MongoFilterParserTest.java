package com.qqmu.jync.service.etl.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.Test;

/**
 * The extraction filter is user-supplied JSON. It is parsed once, checked for the operators
 * that can execute code or write outside the read-only source, and then handed to the driver
 * unmodified — so the checks have to run at every nesting level, not just the root.
 */
class MongoFilterParserTest {

    @Test
    void aBlankFilterMeansEverything() {
        assertThat(MongoFilterParser.parse(null)).isEmpty();
        assertThat(MongoFilterParser.parse("   ")).isEmpty();
    }

    @Test
    void parsesNestedJsonIntoADocument() {
        Document filter = MongoFilterParser.parse("{\"status\":\"active\",\"age\":{\"$gt\":18}}");

        assertThat(filter).isEqualTo(new Document("status", "active")
                .append("age", new Document("$gt", 18)));
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> MongoFilterParser.parse("{\"status\":"))
                .isInstanceOf(MongoFilterException.class)
                .hasMessage("error.etl.mongo.filter.invalid");
    }

    @Test
    void rejectsANonObjectRoot() {
        assertThatThrownBy(() -> MongoFilterParser.parse("[1,2,3]"))
                .isInstanceOf(MongoFilterException.class)
                .hasMessage("error.etl.mongo.filter.invalid");
    }

    @Test
    void rejectsAnOverlongFilter() {
        String longValue = "x".repeat(MongoFilterParser.MAX_FILTER_LENGTH);
        String filter = "{\"name\":\"" + longValue + "\"}";

        assertThatThrownBy(() -> MongoFilterParser.parse(filter))
                .isInstanceOf(MongoFilterException.class)
                .hasMessage("error.etl.mongo.filter.tooLong");
    }

    @Test
    void blocksCodeExecutionAndWriteOperatorsAtAnyDepth() {
        for (String filter : List.of(
                "{\"$where\":\"this.a == 1\"}",
                "{\"a\":{\"$function\":{\"body\":\"return 1\"}}}",
                "{\"a\":{\"$accumulator\":{}}}",
                "{\"a\":{\"$out\":\"other\"}}",
                "{\"a\":{\"$merge\":{\"into\":\"other\"}}}",
                "{\"a\":[{\"b\":{\"$where\":\"true\"}}]}")) {
            assertThatThrownBy(() -> MongoFilterParser.parse(filter))
                    .as(filter)
                    .isInstanceOf(MongoFilterException.class)
                    .hasMessage("error.etl.mongo.filter.blocked");
        }
    }

    @Test
    void allowsOrdinaryAndExpressionOperators() {
        Document filter = MongoFilterParser.parse(
                "{\"$expr\":{\"$gt\":[\"$total\",\"$paid\"]},\"status\":{\"$in\":[\"a\",\"b\"]}}");

        assertThat(filter).isEqualTo(new Document("$expr",
                new Document("$gt", List.of("$total", "$paid")))
                .append("status", new Document("$in", List.of("a", "b"))));
    }

    @Test
    void rejectsNestingBeyondTheDepthLimit() {
        String deep = "{\"v\":1}";
        for (int i = 0; i <= MongoFilterParser.MAX_DEPTH; i++) {
            deep = "{\"a\":" + deep + "}";
        }

        String tooDeep = deep;
        assertThatThrownBy(() -> MongoFilterParser.parse(tooDeep))
                .isInstanceOf(MongoFilterException.class)
                .hasMessage("error.etl.mongo.filter.invalid");
    }

    @Test
    void withResumeAddsAGreaterThanConditionWithoutMutatingTheBaseFilter() {
        Document empty = new Document();
        Document resumed = MongoFilterParser.withResume(empty, "_id", "abc");

        assertThat(empty).isEmpty();
        assertThat(resumed).isEqualTo(new Document("_id", new Document("$gt", "abc")));
    }

    @Test
    void withResumeCombinesAnExistingFilterWithAnd() {
        Document base = new Document("status", "active");

        Document resumed = MongoFilterParser.withResume(base, "seq", 42L);

        assertThat(base).isEqualTo(new Document("status", "active"));
        assertThat(resumed).isEqualTo(new Document("$and", List.of(
                new Document("status", "active"),
                new Document("seq", new Document("$gt", 42L)))));
    }
}
