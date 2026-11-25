package com.qqmu.jync.service.fanout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import lombok.extern.slf4j.Slf4j;

/**
 * Wire format of one fan-out event, shared by the spool and the Redis Stream transport.
 *
 * <p>Java serialization is deliberate: a row map carries {@code byte[]}, {@code BigDecimal}
 * and LOB values whose types must survive the hand-off unchanged, which a JSON text form does
 * not guarantee. Both ends run the same build, so the class layout is identical.
 */
@Slf4j
final class EventCodec {

    /**
     * Both transports read bytes that may not have been written by this process — the Redis
     * stream key is writable by anyone with Redis access, and the spool file is writable on
     * disk. Plain Java deserialization is a gadget-code surface (CWE-502), so only the event
     * graph and the value types a row can carry are allowed through; everything else is
     * refused and the entry is skipped with a logged error.
     */
    /**
     * Concrete value types a row may carry. Package-prefix allowances would also admit
     * gadget primitives such as SerializedLambda and Proxy, which is exactly what the
     * allow-list exists to keep out.
     */
    private static final java.util.Set<Class<?>> ALLOWED_TYPES = java.util.Set.of(
            java.lang.Enum.class, java.lang.Number.class, Object.class,
            String.class, Boolean.class, Byte.class, Short.class, Integer.class,
            Long.class, Float.class, Double.class, Character.class,
            java.math.BigDecimal.class, java.math.BigInteger.class,
            java.util.Date.class, java.sql.Date.class, java.sql.Time.class,
            java.sql.Timestamp.class,
            java.time.Instant.class, java.time.LocalDate.class, java.time.LocalTime.class,
            java.time.LocalDateTime.class, java.time.OffsetTime.class,
            java.time.OffsetDateTime.class, java.time.ZonedDateTime.class,
            java.time.ZoneId.class, java.time.ZoneOffset.class,
            java.time.Duration.class, java.time.Period.class);

    private static boolean allowedClass(Class<?> type) {
        if (type.isPrimitive()) {
            return true;
        }
        String name = type.getName();
        if (ALLOWED_TYPES.contains(type)
                || name.startsWith(ChangeEvent.class.getName() + "$")
                || type == ChangeEvent.class
                || type == java.util.LinkedHashMap.class
                || type == java.util.HashMap.class
                || type == java.util.ArrayList.class
                || type == java.util.UUID.class
                || java.util.Map.Entry.class.isAssignableFrom(type)
                // Map.of / List.of and unmodifiable wrappers: plain containers, no gadgets.
                || name.startsWith("java.util.ImmutableCollections$")
                || name.startsWith("java.util.Collections$Unmodifiable")
                || name.startsWith("java.util.Arrays$")
                // Serialization proxy of Map.of / List.of.
                || name.startsWith("java.util.CollSer")) {
            return true;
        }
        return false;
    }

    private static final ObjectInputFilter FILTER = info -> {
        Class<?> type = info.serialClass();
        if (type == null) {
            return ObjectInputFilter.Status.UNDECIDED;
        }
        Class<?> element = type.isArray() ? type.getComponentType() : type;
        if (allowedClass(element)) {
            return ObjectInputFilter.Status.ALLOWED;
        }
        log.warn("Refusing an unexpected class in a fan-out event: {}", type.getName());
        return ObjectInputFilter.Status.REJECTED;
    };

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
            in.setObjectInputFilter(FILTER);
            return (ChangeEvent) in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("unknown fan-out event class", e);
        }
    }
}
