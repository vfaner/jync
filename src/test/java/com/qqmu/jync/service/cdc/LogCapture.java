package com.qqmu.jync.service.cdc;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Test utility: captures logback events emitted to one class's logger so tests can
 * assert on WARN/ERROR output (real behavior — the fix under test IS the log line).
 */
final class LogCapture {

    private LogCapture() {
    }

    static ListAppender<ILoggingEvent> attach(Class<?> loggedClass) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger(loggedClass).addAppender(appender);
        return appender;
    }

    static void detach(Class<?> loggedClass, ListAppender<ILoggingEvent> appender) {
        logger(loggedClass).detachAppender(appender);
        appender.stop();
    }

    private static Logger logger(Class<?> loggedClass) {
        return (Logger) LoggerFactory.getLogger(loggedClass);
    }
}
