package com.qqmu.jync.service.lob;

import java.io.IOException;
import java.io.InputStream;

/** Replays an already-buffered prefix, then continues with the live driver stream. */
final class ConcatInputStream extends InputStream {

    private InputStream first;
    private final InputStream second;

    ConcatInputStream(InputStream first, InputStream second) {
        this.first = first;
        this.second = second;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n == -1 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
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
