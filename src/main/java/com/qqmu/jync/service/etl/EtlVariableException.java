package com.qqmu.jync.service.etl;

/**
 * A variable definition or reference the task cannot run with.
 *
 * <p>The message is an i18n key followed by {@code :detail}, the same shape the save form and
 * the run history already render, so the operator reads what is wrong and which name it is.
 */
final class EtlVariableException extends IllegalArgumentException {

    EtlVariableException(String key, String detail) {
        super(detail == null ? key : key + ":" + detail);
    }
}
