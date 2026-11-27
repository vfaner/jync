package com.qqmu.jync.controller;

import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.persistence.PersistenceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

import lombok.extern.slf4j.Slf4j;

/**
 * A metadata-store I/O failure is not a page-specific bug. When H2 has closed its file after
 * a failed MVStore write, every subsequent query fails; without this advice each request
 * renders a generic 500 and repeats a long Hibernate stack trace.
 *
 * <p>The advice only claims exceptions caused by H2. A source/target database failure that
 * happens to surface through a controller still follows its normal error path. API callers
 * receive a stable 503 JSON body; browser requests get a small standalone maintenance page
 * that does not render the layout (the layout itself reads settings from the broken store).
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class MetadataStoreFailureAdvice {

    private static final long LOG_INTERVAL_MS = 60_000L;
    private final AtomicLong lastLogAt = new AtomicLong();

    @ExceptionHandler({DataAccessException.class, TransactionSystemException.class,
            PersistenceException.class, SQLException.class})
    public Object metadataStoreUnavailable(Exception failure,
                                           HttpServletRequest request,
                                           HttpServletResponse response) throws Exception {
        if (!isH2Failure(failure)) {
            // Not this advice's failure; let Spring's normal exception resolution continue.
            throw failure;
        }
        logOnce(failure);
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        if (isApiRequest(request)) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"success\":false,\"message\":\"error.metadata.unavailable\"}");
            return null;
        }
        return new ModelAndView("error-metadata", HttpStatus.SERVICE_UNAVAILABLE);
    }

    private void logOnce(Exception failure) {
        long now = System.currentTimeMillis();
        long previous = lastLogAt.get();
        if (now - previous >= LOG_INTERVAL_MS && lastLogAt.compareAndSet(previous, now)) {
            Throwable root = root(failure);
            log.error("Metadata store is unavailable; serving the maintenance response: {}",
                    root.toString(), failure);
        }
    }

    private static boolean isH2Failure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            Class<?> type = current.getClass();
            if (type.getName().startsWith("org.h2.")) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && (message.contains("database has been closed")
                    || message.contains("MVStoreException")
                    || message.contains("Writing to sun.nio.ch.FileChannelImpl"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static Throwable root(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isApiRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return path.startsWith("/api/");
    }
}
