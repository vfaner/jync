package com.qqmu.jync.service.cdc;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.qqmu.jync.service.fanout.ChangeEvent;
import com.qqmu.jync.service.fanout.EventTransport;
import com.qqmu.jync.service.lob.LobValue;

import lombok.extern.slf4j.Slf4j;

/**
 * Capture-side {@link CdcEventStreamHandler.TransactionSink} for a fan-out project: turns
 * one decoded source transaction into normalized events and publishes them, without writing
 * any target itself.
 *
 * <p>This is the seam that lets one source capture feed N target workers. Position ownership
 * moves to the target workers (each acknowledges after its own commit); the capture must not
 * advance a single shared position past the slowest target.
 */
@Slf4j
public final class CdcFanoutSink implements CdcEventStreamHandler.TransactionSink {

    private final EventTransport transport;

    public CdcFanoutSink(EventTransport transport) {
        this.transport = transport;
    }

    @Override
    public void apply(List<CdcEventStreamHandler.TableChange> changes, CdcPositionMark mark)
            throws SQLException {
        String position = positionOf(mark);
        // A mark identifies the commit; it is unique enough as the transaction id and lets a
        // replayed transaction be recognised by its position.
        String transactionId = position;
        try {
            for (CdcEventStreamHandler.TableChange change : changes) {
                CdcTableBinding binding = change.binding;
                for (Map<String, Object> row : change.upserts()) {
                    transport.publish(ChangeEvent.row(position, transactionId,
                            binding.getDatabase(), binding.getTable(),
                            ChangeEvent.ChangeOperation.UPSERT, wireRow(row), null,
                            System.currentTimeMillis()));
                }
                for (Map<String, Object> row : change.deletes()) {
                    transport.publish(ChangeEvent.row(position, transactionId,
                            binding.getDatabase(), binding.getTable(),
                            ChangeEvent.ChangeOperation.DELETE, null, wireRow(row),
                            System.currentTimeMillis()));
                }
            }
            transport.publish(ChangeEvent.commit(position, transactionId,
                    System.currentTimeMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("fan-out publish interrupted for transaction "
                    + transactionId, e);
        }
    }

    /**
     * The transport serializes events, and a spilled LOB is a file handle, not a value:
     * Java serialization would reject it and the transaction could never be published.
     * Materialize the (already spilled or in-memory) value into a String/byte[] exactly
     * once, here on the capture side. The spiller still owns cleanup of its temp file.
     */
    static Map<String, Object> wireRow(Map<String, Object> row) throws SQLException {
        if (row == null || row.isEmpty()) {
            return row;
        }
        Map<String, Object> copy = null;
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (!(entry.getValue() instanceof LobValue lob)) {
                continue;
            }
            if (copy == null) {
                copy = new LinkedHashMap<>(row);
            }
            copy.put(entry.getKey(), materialize(entry.getKey(), lob));
        }
        return copy == null ? row : copy;
    }

    private static Object materialize(String column, LobValue lob) throws SQLException {
        try {
            if (lob.kind() == LobValue.Kind.TEXT) {
                try (Reader reader = lob.openReader()) {
                    StringBuilder text = new StringBuilder();
                    char[] buffer = new char[8192];
                    int read;
                    while ((read = reader.read(buffer)) >= 0) {
                        text.append(buffer, 0, read);
                    }
                    return text.toString();
                }
            }
            try (InputStream in = lob.openStream()) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            throw new SQLException("could not materialize LOB column '" + column
                    + "' for the fan-out transport", e);
        }
    }

    static String positionOf(CdcPositionMark mark) {
        if (mark == null) {
            return "unknown";
        }
        if (mark.getScn() != null) {
            return "scn:" + mark.getScn();
        }
        return mark.getFile() + ":" + mark.getPos();
    }
}
