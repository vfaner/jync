package com.qqmu.jync.model;

/**
 * The wire shape used to post an alert into a chat room.
 *
 * <p>Generic JSON is the default because it is the only shape that stays readable everywhere;
 * the three named products each wrap a plain text message in their own envelope.
 */
public enum AlertWebhookFormat {

    GENERIC("alert.format.generic"),
    WE_COM("alert.format.weCom"),
    DING_TALK("alert.format.dingTalk"),
    FEISHU("alert.format.feishu");

    private final String messageKey;

    AlertWebhookFormat(String messageKey) {
        this.messageKey = messageKey;
    }

    /** i18n key of the format's display name. */
    public String messageKey() {
        return messageKey;
    }
}
