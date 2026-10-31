package com.qqmu.jync.service.etl.mongo;

/**
 * A user-supplied Mongo filter that cannot be used. Extends {@link IllegalArgumentException}
 * so the controller's existing i18n flash handling shows the message key as-is.
 */
public class MongoFilterException extends IllegalArgumentException {

    public MongoFilterException(String messageKey) {
        super(messageKey);
    }
}
