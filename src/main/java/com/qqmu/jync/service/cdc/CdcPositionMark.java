package com.qqmu.jync.service.cdc;

import lombok.Getter;

/** One persistable position: binlog file + next-position, plus the GTID set when available. */
@Getter
public final class CdcPositionMark {
    private final String file;
    private final long pos;
    private final String gtidSet;

    /** LogMiner variant: SCN-only watermark. Null for binlog marks. */
    private final Long scn;

    public CdcPositionMark(String file, long pos, String gtidSet) {
        this.file = file;
        this.pos = pos;
        this.gtidSet = gtidSet;
        this.scn = null;
    }

    private CdcPositionMark(long scn) {
        this.file = null;
        this.pos = 0L;
        this.gtidSet = null;
        this.scn = scn;
    }

    /** LogMiner watermark factory — binlog marks constructed via the 3-arg constructor. */
    public static CdcPositionMark ofScn(long scn) {
        return new CdcPositionMark(scn);
    }
}
