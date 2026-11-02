package com.qqmu.jync.service.migration.mongo;

import org.bson.Document;

/**
 * A collection's {@code options} document and {@code listIndexes()} entries carry server-managed
 * fields. Replaying them verbatim would have the target reject the command, so the read-only
 * fields are dropped before the spec is sent.
 */
public final class MongoCollectionSupport {

    private MongoCollectionSupport() {
    }

    /** Collection options that can be replayed through a {@code create} command. */
    public static Document cleanOptions(Document options) {
        Document cleaned = new Document();
        if (options == null) {
            return cleaned;
        }
        options.forEach((key, value) -> {
            if (!"uuid".equals(key) && !"name".equals(key) && !"type".equals(key)) {
                cleaned.put(key, value);
            }
        });
        return cleaned;
    }

    /** An index spec without the fields the driver or server fills in itself. */
    public static Document cleanIndex(Document index) {
        Document cleaned = new Document();
        if (index == null) {
            return cleaned;
        }
        index.forEach((key, value) -> {
            if (!"v".equals(key) && !"ns".equals(key) && !"background".equals(key)) {
                cleaned.put(key, value);
            }
        });
        return cleaned;
    }

    public static boolean isIdIndex(Document index) {
        return index != null && "_id_".equals(index.getString("name"));
    }

    public static boolean isSystem(String name) {
        return name != null && name.startsWith("system.");
    }
}
