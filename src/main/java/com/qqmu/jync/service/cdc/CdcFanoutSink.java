package com.qqmu.jync.service.cdc;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.qqmu.jync.service.fanout.ChangeEvent;
import com.qqmu.jync.service.fanout.EventTransport;

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
                            ChangeEvent.ChangeOperation.UPSERT, row, null,
                            System.currentTimeMillis()));
                }
                for (Map<String, Object> row : change.deletes()) {
                    transport.publish(ChangeEvent.row(position, transactionId,
                            binding.getDatabase(), binding.getTable(),
                            ChangeEvent.ChangeOperation.DELETE, null, row,
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
