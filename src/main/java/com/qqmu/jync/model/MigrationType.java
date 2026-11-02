package com.qqmu.jync.model;

import java.util.Arrays;

/**
 * The migration types the dedicated-migration module can offer.
 *
 * <p>A type only appears as an actionable card when {@link #available()} is true and a matching
 * {@code MigrationTypeHandler} is registered. A future type can be declared with
 * {@code available=false} so the home page advertises it without wiring an engine.
 */
public enum MigrationType {

    ES_TO_EASYSEARCH("es-to-easysearch", "migration.type.esToEasysearch.name", true),
    MONGODB_TO_MONGODB("mongodb-to-mongodb", "migration.type.mongoToMongo.name", true),
    MONGODB_TO_KINGBASE("mongodb-to-kingbase", "migration.type.mongoToKingbase.name", true);

    private final String key;
    private final String messageKey;
    private final boolean available;

    MigrationType(String key, String messageKey, boolean available) {
        this.key = key;
        this.messageKey = messageKey;
        this.available = available;
    }

    /** URL segment used by the type-parameterized pages. */
    public String key() {
        return key;
    }

    public String messageKey() {
        return messageKey;
    }

    /** The one-line description shown under the type's name on the landing card. */
    public String descriptionKey() {
        // The constants hold the name key; the description sits beside it under the same
        // prefix, not nested under the name key.
        String base = messageKey.endsWith(".name")
                ? messageKey.substring(0, messageKey.length() - ".name".length())
                : messageKey;
        return base + ".description";
    }

    public boolean available() {
        return available;
    }

    public static MigrationType fromKey(String key) {
        return Arrays.stream(values())
                .filter(type -> type.key.equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown migration type: " + key));
    }
}
