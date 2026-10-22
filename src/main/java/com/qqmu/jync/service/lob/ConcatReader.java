package com.qqmu.jync.service.lob;

import java.io.IOException;
import java.io.Reader;

/** Replays an already-buffered prefix, then continues with the live driver stream. */
final class ConcatReader extends Reader {

    private Reader first;
    private Reader second;

    ConcatReader(Reader first, Reader second) {
        this.first = first;
        this.second = second;
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        if (first != null) {
            int n = first.read(buffer, offset, length);
            if (n != -1) {
                return n;
            }
            first.close();
            first = null;
        }
        return second.read(buffer, offset, length);
    }

    @Override
    public void close() throws IOException {
        try {
            if (first != null) {
                first.close();
            }
        } finally {
            first = null;
            second.close();
        }
    }
}
