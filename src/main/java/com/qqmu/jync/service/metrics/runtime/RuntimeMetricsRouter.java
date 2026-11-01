package com.qqmu.jync.service.metrics.runtime;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseType;

import lombok.extern.slf4j.Slf4j;

/**
 * Picks the reader for a product family.
 *
 * <p>An empty result is a normal answer: analytic engines, MongoDB and H2 have no such
 * dictionary, and the page says so rather than drawing a flat line.
 */
@Component
@Slf4j
public class RuntimeMetricsRouter {

    private final List<RuntimeMetricsReader> readers;

    public RuntimeMetricsRouter(List<RuntimeMetricsReader> readers) {
        this.readers = readers;
    }

    public Optional<RuntimeMetricsReader.Reading> read(DatabaseType type, Connection connection)
            throws SQLException {
        for (RuntimeMetricsReader reader : readers) {
            if (reader.supports(type)) {
                return Optional.of(reader.read(connection));
            }
        }
        return Optional.empty();
    }
}
