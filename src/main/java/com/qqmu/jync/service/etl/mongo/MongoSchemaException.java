package com.qqmu.jync.service.etl.mongo;

/**
 * A sampled document that cannot become a target column. The message is an i18n key plus the
 * offending field name, because the name is what tells the operator which document to fix.
 */
public class MongoSchemaException extends IllegalArgumentException {

    private final String messageKey;
    private final String detail;

    public MongoSchemaException(String messageKey, String detail) {
        super(messageKey + ": " + detail);
        this.messageKey = messageKey;
        this.detail = detail;
    }

    public String messageKey() {
        return messageKey;
    }

    public String detail() {
        return detail;
    }
}
