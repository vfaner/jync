package com.qqmu.jync.service.cdc;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.github.shyiko.mysql.binlog.event.deserialization.json.JsonBinary;
import com.qqmu.jync.dto.meta.ColumnMeta;

import lombok.extern.slf4j.Slf4j;

/**
 * Maps physical binlog cell values (as delivered by mysql-binlog-connector-java with
 * the compatibility modes pinned in BinlogClientFactory) to the Java values
 * JdbcRowMapper.bind expects. This is the single place where wire format meets the
 * JDBC type system — task book risk R1 lives and dies here, so every branch has a
 * matrix test.
 */
@Slf4j
public final class CdcRowMapper {

    /** Zero dates arrive as Long.MIN_VALUE under INVALID_DATE_AND_TIME_AS_MIN_VALUE. */
    static final long ZERO_DATE_SENTINEL = Long.MIN_VALUE;

    public Map<String, Object> mapRow(Serializable[] binlogRow, CdcTableBinding binding,
                                      CdcRuntimeStats stats) throws CdcUnmappableValueException {
        return mapRow(binlogRow, binding, stats, CdcLobSpiller.disabled());
    }

    /**
     * Maps one row image and hands oversized values to {@code spiller}. The spiller is the
     * transaction buffer's: it owns the files until the buffer is cleared, so the mapper
     * only rewrites values in place.
     */
    public Map<String, Object> mapRow(Serializable[] binlogRow, CdcTableBinding binding,
                                      CdcRuntimeStats stats, CdcLobSpiller spiller)
            throws CdcUnmappableValueException {
        java.util.List<ColumnMeta> columns = binding.getMeta().getColumns();
        if (binlogRow.length != columns.size()) {
            throw new CdcUnmappableValueException(binding.getTable(), "row-image",
                    "binlog row has " + binlogRow.length + " cells but metadata has "
                            + columns.size() + " columns (stale binding after DDL?)");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < binlogRow.length; i++) {
            ColumnMeta column = columns.get(i);
            String charset = binding.getCharsetByUpperColumn()
                    .get(column.getName().toUpperCase(Locale.ROOT));
            row.put(column.getName(), mapValue(binlogRow[i], column, charset, stats));
        }
        return spiller.spill(binding, row);
    }

    static Object mapValue(Serializable raw, ColumnMeta column, String charsetName,
                           CdcRuntimeStats stats) throws CdcUnmappableValueException {
        if (raw == null) {
            return null;
        }
        int jdbcType = column.getJdbcType();

        if (raw instanceof Long && isTemporal(jdbcType)) {
            long micros = (Long) raw;
            if (micros == ZERO_DATE_SENTINEL) {
                if (stats != null) {
                    stats.addZeroDateCoerced(1);
                }
                log.warn("Zero date in column '{}' coerced to NULL (counted)", column.getName());
                return null;
            }
            switch (jdbcType) {
                case Types.DATE:
                    return java.sql.Date.valueOf(Instant.ofEpochMilli(micros / 1000L)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate());
                case Types.TIME:
                    // Ruling R5: java.sql.Time is a 1970-epoch wall clock — it cannot carry
                    // MySQL's TIME domain (>24h, negative, fractional) through either driver
                    // direction. Emit the server's literal text instead; bindValue sends it
                    // back as a statement literal, exactly like the baseline getString path.
                    return formatTimeLiteral(micros);
                case Types.TIMESTAMP:
                default:
                    // floorDiv keeps the seconds base aligned with the floored (always >= 0)
                    // nanos for pre-1970 fractions; truncating division lands a second late.
                    java.sql.Timestamp ts =
                            new java.sql.Timestamp(Math.floorDiv(micros, 1_000_000L) * 1000L);
                    ts.setNanos((int) (Math.floorMod(micros, 1_000_000L) * 1000L));
                    return ts;
            }
        }

        if (raw instanceof byte[]) {
            byte[] bytes = (byte[]) raw;
            if (isJson(column.getTypeName())) {
                // Ruling R2: in the binlog a JSON column carries MySQL's INTERNAL BINARY
                // format (type-code byte + offset tables), not text — charset-decoding it
                // produces garbage. The client library bundles the JsonBinary codec (the
                // same one its JSON_STRING compatibility mode uses), so decode to the
                // canonical text form here and keep the baseline path's String contract.
                return decodeBinaryJson(bytes, column);
            }
            if (isCharLike(jdbcType, column.getTypeName())) {
                return new String(bytes, resolveCharset(charsetName, column));
            }
            if (jdbcType == Types.BIT) {
                if (column.getSize() != null && column.getSize() == 1) {
                    return Boolean.valueOf(bytes.length > 0 && bytes[0] != 0);
                }
                return bytes; // wider BIT: hand the raw bits to setBytes, targets cope
            }
            if (isBinaryLike(jdbcType)) {
                return bytes;
            }
            throw new CdcUnmappableValueException(column.getName(), column.getTypeName(), "byte[" + bytes.length + "]");
        }

        if (raw instanceof java.util.BitSet) {
            // Ruling R4: BIT(n) cells arrive as java.util.BitSet (bit index i = value bit
            // 2^i) — the compat modes do not cover BIT, so the old byte[] branch never saw
            // them and every BIT row died as "unmappable" (skip+count, silent row loss).
            if (jdbcType == Types.BIT) {
                return toBigEndianBytes((java.util.BitSet) raw, column);
            }
            throw new CdcUnmappableValueException(column.getName(), column.getTypeName(), raw);
        }

        if (raw instanceof Integer || raw instanceof Long || raw instanceof java.math.BigDecimal
                || raw instanceof Double || raw instanceof Float || raw instanceof Boolean
                || raw instanceof Short || raw instanceof Byte || raw instanceof BigInteger) {
            return raw;
        }

        throw new CdcUnmappableValueException(column.getName(), column.getTypeName(), raw);
    }

    private static boolean isTemporal(int jdbcType) {
        return jdbcType == Types.DATE || jdbcType == Types.TIME || jdbcType == Types.TIMESTAMP;
    }

    /**
     * Formats duration-based TIME micros as MySQL's literal text (ruling R5):
     * {@code [-]HH:MM:SS} with the hours left UNCAPPED (MySQL's domain is
     * -838:59:59 … 838:59:59, not a wall clock) and a {@code .uuuuuu} fraction appended
     * only when sub-second micros are present — whole-second values then render exactly
     * like {@code rs.getString} does on the baseline path for TIME(0) columns.
     */
    static String formatTimeLiteral(long micros) {
        StringBuilder sb = new StringBuilder(18);
        long abs = micros;
        if (abs < 0) {
            sb.append('-');
            abs = -abs;
        }
        long hours = abs / 3_600_000_000L;
        long minutes = (abs / 60_000_000L) % 60;
        long seconds = (abs / 1_000_000L) % 60;
        long fraction = abs % 1_000_000L;
        if (hours < 10) {
            sb.append('0');
        }
        sb.append(hours).append(':');
        if (minutes < 10) {
            sb.append('0');
        }
        sb.append(minutes).append(':');
        if (seconds < 10) {
            sb.append('0');
        }
        sb.append(seconds);
        if (fraction != 0) {
            sb.append('.');
            String frac = Long.toString(fraction);
            for (int pad = frac.length(); pad < 6; pad++) {
                sb.append('0');
            }
            sb.append(frac);
        }
        return sb.toString();
    }

    /**
     * Rebuilds the big-endian byte image JDBC {@code setBytes} expects for a BIT column:
     * {@code ceil(n/8)} bytes from the declared width, set bit {@code i} landing at
     * {@code out[numBytes-1-(i>>>3)] |= 1<<(i&7)}. {@link java.util.BitSet#toByteArray()}
     * is little-endian AND trimmed, so reversing it is not equivalent (ruling R4) — the
     * rebuild is bit-by-bit. A set bit beyond the declared width is genuinely unmappable:
     * the skip+count residual path stays as-is for v1.5.0 (T17 surfaces the counter).
     */
    static byte[] toBigEndianBytes(java.util.BitSet bits, ColumnMeta column)
            throws CdcUnmappableValueException {
        Integer size = column.getSize();
        int numBytes = size != null && size > 0
                ? (size + 7) / 8
                : Math.max(1, (bits.length() + 7) / 8);
        byte[] out = new byte[numBytes];
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
            int byteIdx = numBytes - 1 - (i >>> 3);
            if (byteIdx < 0) {
                throw new CdcUnmappableValueException(column.getName(), column.getTypeName(), bits);
            }
            out[byteIdx] |= (byte) (1 << (i & 7));
        }
        return out;
    }

    private static boolean isJson(String typeName) {
        return typeName != null && typeName.toUpperCase(Locale.ROOT).contains("JSON");
    }

    /**
     * Binary JSON -> canonical text via the codec bundled with the client library.
     * Ruling R2: undecodable payloads (unknown value type codes, truncated buffers)
     * must fail LOUDLY — an {@link IllegalStateException} rides the R1 choke path into
     * strike-out/ERROR classification — never pass garbage through as if it were text.
     * Text-shaped payloads (first byte above 0x0f, e.g. a leading '{' or '"') are
     * returned verbatim by the codec, so a JSON column that somehow arrives as text
     * still works. Known upstream limitation (disclosed, not fixed here): TIME values
     * INSIDE a JSON document are already lossy in MySQL's own server-side packing
     * (hours wrap at 32) and the 0.41.5 codec renders negative in-JSON times garbled;
     * plain JSON column values (objects/arrays/strings/numbers) — everything the
     * acceptance bar exercises — decode exactly.
     */
    static String decodeBinaryJson(byte[] bytes, ColumnMeta column) {
        try {
            return JsonBinary.parseAsString(bytes);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot decode binary JSON in column '"
                    + column.getName() + "' (" + bytes.length + " bytes)", e);
        }
    }

    private static boolean isCharLike(int jdbcType, String typeName) {
        if (typeName != null && typeName.toUpperCase(Locale.ROOT).contains("JSON")) {
            return true; // MySQL JSON is reported as LONGVARCHAR; it is text to us
        }
        return jdbcType == Types.CHAR || jdbcType == Types.VARCHAR || jdbcType == Types.LONGVARCHAR
                || jdbcType == Types.NCHAR || jdbcType == Types.NVARCHAR || jdbcType == Types.LONGNVARCHAR
                || jdbcType == Types.CLOB;
    }

    private static boolean isBinaryLike(int jdbcType) {
        return jdbcType == Types.BINARY || jdbcType == Types.VARBINARY || jdbcType == Types.LONGVARBINARY
                || jdbcType == Types.BLOB;
    }

    /** MySQL charset name -> Java Charset, with a conservative fallback (Review Focus #3). */
    static Charset resolveCharset(String charsetName, ColumnMeta column) {
        if (charsetName == null || charsetName.trim().isEmpty()) {
            return StandardCharsets.UTF_8;
        }
        String normalized = charsetName.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "utf8mb4": case "utf8": case "utf8mb3":
                return StandardCharsets.UTF_8;
            case "latin1": case "binary":
                return StandardCharsets.ISO_8859_1; // byte-preserving for "binary"
            case "ascii":
                return StandardCharsets.US_ASCII;
            default:
                try {
                    return Charset.forName(normalized); // gbk, big5, sjis, euckr, ...
                } catch (RuntimeException e) {
                    log.warn("Unknown charset '{}' on column {}; falling back to UTF-8",
                            charsetName, column.getName());
                    return StandardCharsets.UTF_8;
                }
        }
    }
}
