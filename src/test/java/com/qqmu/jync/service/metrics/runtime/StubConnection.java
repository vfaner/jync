package com.qqmu.jync.service.metrics.runtime;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A connection that hands out prepared statements in order and keeps handing out the last
 * one, so a reader's Nth query gets the Nth answer.
 *
 * <p>Build the statements with {@link #statement(ResultSet)} / {@link #denied(SQLException)}
 * <em>before</em> calling {@link #returning(Statement...)}: creating a mock inside
 * {@code thenReturn(...)} leaves Mockito with an unfinished stubbing.
 */
final class StubConnection {

    private StubConnection() {
    }

    static Connection returning(ResultSet... results) throws SQLException {
        Statement[] statements = new Statement[results.length];
        for (int i = 0; i < results.length; i++) {
            statements[i] = statement(results[i]);
        }
        return returning(statements);
    }

    static Connection returning(Statement... statements) throws SQLException {
        Connection connection = mock(Connection.class);
        AtomicInteger call = new AtomicInteger();
        when(connection.createStatement()).thenAnswer(inv ->
                statements[Math.min(call.getAndIncrement(), statements.length - 1)]);
        return connection;
    }

    static Statement statement(ResultSet resultSet) throws SQLException {
        Statement statement = mock(Statement.class);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        return statement;
    }

    static Statement denied(SQLException failure) throws SQLException {
        Statement statement = mock(Statement.class);
        when(statement.executeQuery(anyString())).thenThrow(failure);
        return statement;
    }
}
