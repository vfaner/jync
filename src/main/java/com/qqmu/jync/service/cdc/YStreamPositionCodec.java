package com.qqmu.jync.service.cdc;

/**
 * The packed text form of a YStream commit position.
 *
 * <p>The client's position is a five-tuple {@code scn / instanceId / lsn / offset / rowId}.
 * Jync is the authority on where to resume (a client checkpoint only lands on the server's
 * next periodic flush, so it cannot be trusted), and it stores that tuple in
 * {@code cdc_position.lsn} — the VARCHAR(64) column PG and SQL Server already share,
 * mutually exclusive per project.
 *
 * <p>Each field is written at a fixed width, in commit order, so plain string order is
 * commit order. That is what lets {@link CdcPositionStore#compareLsn} keep its monotonicity
 * guard without knowing anything else about YStream. The human-readable
 * {@code <scn>-<inst>-<lsn>-<off>-<batch>} form is only for logs and pages.
 */
public final class YStreamPositionCodec {

    /** {@code 16 + 2 + 16 + 8 + 8} — fixed width is the whole point. */
    public static final int PACKED_LENGTH = 50;

    private YStreamPositionCodec() {
    }

    /**
     * A YStream position: the commit SCN plus the log position inside that commit.
     *
     * @param commitScn   SCN of the transaction's COMMIT
     * @param instanceId  source instance id, an unsigned byte ({@code 0..255})
     * @param groupLsn    log group LSN
     * @param groupOffset offset inside the log group
     * @param batchRowId  row index inside the batch
     */
    public record YStreamPosition(long commitScn, int instanceId, long groupLsn,
                                  int groupOffset, int batchRowId) {

        /** The readable form used in logs and pages — never stored. */
        public String text() {
            return commitScn + "-" + instanceId + "-" + groupLsn + "-"
                    + groupOffset + "-" + batchRowId;
        }
    }

    /**
     * Packs a position into its 50-character uppercase hex form.
     *
     * @throws IllegalArgumentException when the instance id does not fit an unsigned byte
     */
    public static String encode(YStreamPosition position) {
        if (position.instanceId() < 0 || position.instanceId() > 0xFF) {
            throw new IllegalArgumentException(
                    "YStream instance id is a byte, got " + position.instanceId());
        }
        return String.format("%016X%02X%016X%08X%08X",
                position.commitScn(), position.instanceId(), position.groupLsn(),
                position.groupOffset(), position.batchRowId());
    }

    /**
     * Unpacks a stored position.
     *
     * @throws IllegalArgumentException on anything that is not our exact shape — a resume
     *         point that silently decodes to a different position would replay from the
     *         wrong place, which is worse than refusing to start
     */
    public static YStreamPosition decode(String packed) {
        if (!isPacked(packed)) {
            throw new IllegalArgumentException("Not a packed YStream position: " + packed);
        }
        return new YStreamPosition(
                Long.parseUnsignedLong(packed.substring(0, 16), 16),
                Integer.parseInt(packed.substring(16, 18), 16),
                Long.parseUnsignedLong(packed.substring(18, 34), 16),
                (int) Long.parseLong(packed.substring(34, 42), 16),
                (int) Long.parseLong(packed.substring(42, 50), 16));
    }

    /**
     * Parses the readable {@code <scn>-<inst>-<lsn>-<off>-<batch>} form — the spelling
     * {@code V$YSTREAM_SERVER.RESTART_POSITION} uses, and the one this codec's
     * {@link YStreamPosition#text()} writes.
     *
     * <p>Used for the baseline anchor, which can only come from the server's own row:
     * {@code START_POSITION} is a four-tuple (no {@code batchRowId}), so anchoring from it
     * would produce a position the resume path cannot decode.
     *
     * @throws IllegalArgumentException on anything that is not exactly five non-negative
     *         decimal fields in range — a guess here decides how much data is lost
     */
    public static YStreamPosition parseText(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Not a YStream position: null");
        }
        String[] parts = text.split("-", -1);
        if (parts.length != 5) {
            throw new IllegalArgumentException("Not a YStream position: " + text);
        }
        for (String part : parts) {
            if (part.isEmpty() || !isDecimal(part)) {
                throw new IllegalArgumentException("Not a YStream position: " + text);
            }
        }
        try {
            long commitScn = Long.parseLong(parts[0]);
            int instanceId = Integer.parseInt(parts[1]);
            long groupLsn = Long.parseLong(parts[2]);
            int groupOffset = Integer.parseInt(parts[3]);
            int batchRowId = Integer.parseInt(parts[4]);
            if (instanceId < 0 || instanceId > 0xFF) {
                throw new IllegalArgumentException(
                        "YStream instance id is a byte, got " + instanceId + " in " + text);
            }
            return new YStreamPosition(commitScn, instanceId, groupLsn, groupOffset, batchRowId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a YStream position: " + text, e);
        }
    }

    private static boolean isDecimal(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code text} is this codec's own 50-character uppercase hex form. */
    public static boolean isPacked(String text) {
        if (text == null || text.length() != PACKED_LENGTH) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
