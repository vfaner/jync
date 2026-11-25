package com.qqmu.jync.service.fanout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * Wire format of one fan-out event, shared by the spool and the Redis Stream transport.
 *
 * <p>Java serialization is deliberate: a row map carries {@code byte[]}, {@code BigDecimal}
 * and LOB values whose types must survive the hand-off unchanged, which a JSON text form does
 * not guarantee. Both ends run the same build, so the class layout is identical.
 */
final class EventCodec {

    private EventCodec() {
    }

    static byte[] serialize(ChangeEvent event) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
                out.writeObject(event);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("could not serialize a fan-out event", e);
        }
    }

    static ChangeEvent deserialize(byte[] bytes) throws IOException {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (ChangeEvent) in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("unknown fan-out event class", e);
        }
    }
}
