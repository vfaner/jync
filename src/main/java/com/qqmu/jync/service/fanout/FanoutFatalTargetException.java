package com.qqmu.jync.service.fanout;

/**
 * A target's transport failed in a way that a restart cannot fix: a corrupt/undecodable
 * spool record, a spool that hit its cap, or a Redis entry this build cannot decode.
 * The supervisor must not rewind and re-read the same bytes forever; it stops restarting
 * the worker and leaves the error on the target status for a human.
 */
final class FanoutFatalTargetException extends RuntimeException {

    FanoutFatalTargetException(String message, Throwable cause) {
        super(message, cause);
    }
}
