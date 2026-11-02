package com.qqmu.jync.service.migration.mongo;

/** One entry of {@code listCollections}: a collection, a view or a time-series collection. */
public record MongoCollectionInfo(String name, String type) {

    public boolean isCollection() {
        return type == null || "collection".equals(type);
    }
}
