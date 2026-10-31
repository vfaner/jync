package com.qqmu.jync.service.etl.mongo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bson.Document;

/**
 * Parses the task's optional extraction filter and refuses operators that can execute code
 * or write outside the source.
 *
 * <p>MongoDB's {@code $where}, {@code $function} and {@code $accumulator} run JavaScript,
 * while {@code $out} and {@code $merge} write into other collections. A read-only ETL source
 * must accept none of them, at any nesting level. Ordinary query operators — including the
 * read-only {@code $expr} — pass through unchanged.
 */
public final class MongoFilterParser {

    public static final int MAX_FILTER_LENGTH = 4000;
    public static final int MAX_DEPTH = 32;

    private static final Set<String> BLOCKED = Set.of(
            "$where", "$function", "$accumulator", "$out", "$merge");

    private MongoFilterParser() {
    }

    /** A blank filter matches every document; a non-blank one must be a JSON object. */
    public static Document parse(String source) {
        if (source == null || source.isBlank()) {
            return new Document();
        }
        if (source.length() > MAX_FILTER_LENGTH) {
            throw new MongoFilterException("error.etl.mongo.filter.tooLong");
        }
        String trimmed = source.trim();
        if (!trimmed.startsWith("{")) {
            throw new MongoFilterException("error.etl.mongo.filter.invalid");
        }
        Document filter;
        try {
            filter = Document.parse(trimmed);
        } catch (RuntimeException e) {
            throw new MongoFilterException("error.etl.mongo.filter.invalid");
        }
        scan(filter, 0);
        return filter;
    }

    private static void scan(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new MongoFilterException("error.etl.mongo.filter.invalid");
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (BLOCKED.contains(String.valueOf(entry.getKey()))) {
                    throw new MongoFilterException("error.etl.mongo.filter.blocked");
                }
                scan(entry.getValue(), depth + 1);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                scan(item, depth + 1);
            }
        } else if (value != null && value.getClass().isArray()) {
            // Document.parse never yields a Java array, but a hand-built Document might.
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                scan(java.lang.reflect.Array.get(value, i), depth + 1);
            }
        }
    }

    /**
     * Adds the resume condition without touching the caller's filter: an empty base becomes
     * {@code {key: {$gt: value}}}, anything else becomes {@code {$and: [base, condition]}}.
     */
    public static Document withResume(Document filter, String key, Object value) {
        Document condition = new Document(key, new Document("$gt", value));
        if (filter == null || filter.isEmpty()) {
            return condition;
        }
        List<Object> clauses = new ArrayList<>();
        clauses.add(filter);
        clauses.add(condition);
        return new Document("$and", clauses);
    }
}
