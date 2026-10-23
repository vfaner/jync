package com.qqmu.jync.service.auth.oa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Reads an HTTP response body with a hard cap, so a login thread never buffers more. */
final class HttpBodies {

    static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private HttpBodies() {
    }

    /** Returns {@code null} as soon as the body proves larger than the cap. */
    static byte[] readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > MAX_RESPONSE_BYTES) {
                return null;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
