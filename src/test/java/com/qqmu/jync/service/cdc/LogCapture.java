package com.qqmu.jync.service.cdc;

import java.util.List;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Test utility: captures logback events emitted to one class's logger so tests can
 * assert on what a level carries (real behavior — the fix under test IS which level a
 * line is on, or whether it is emitted at all). Public so any test package can use it.
 */
public final class LogCapture {

    private LogCapture() {
    }

    public static ListAppender<ILoggingEvent> attach(Class<?> loggedClass) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger(loggedClass).addAppender(appender);
        return appender;
    }

    public static void detach(Class<?> loggedClass, ListAppender<ILoggingEvent> appender) {
        logger(loggedClass).detachAppender(appender);
        appender.stop();
    }

    /** Messages of the given level, in emission order; everything else is dropped. */
    public static List<String> messagesAt(Level level, ListAppender<ILoggingEvent> logs) {
        List<String> messages = new java.util.ArrayList<>();
        for (ILoggingEvent event : logs.list) {
            if (event.getLevel() == level) {
                messages.add(event.getFormattedMessage());
            }
        }
        return messages;
    }

    private static Logger logger(Class<?> loggedClass) {
        return (Logger) LoggerFactory.getLogger(loggedClass);
    }
}
