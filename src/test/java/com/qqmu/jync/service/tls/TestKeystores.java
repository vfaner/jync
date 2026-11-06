package com.qqmu.jync.service.tls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Collections;

/**
 * Self-signed key material for the TLS tests.
 *
 * <p>The PKCS#12 blob is the same one {@code SearchHttpClientTlsTest} uses: a single RSA private
 * key whose certificate names {@code misconfigured.example} and is valid into the next century.
 * The JKS variant is derived from it in memory so both formats carry the identical certificate.
 *
 * <p>Test-only material — production code never reads this class.
 */
public final class TestKeystores {

    public static final char[] PASSPHRASE = "changeit".toCharArray();

    private static final String PKCS12_BASE64 =
            "MIIKMAIBAzCCCdoGCSqGSIb3DQEHAaCCCcsEggnHMIIJwzCCBaoGCSqGSIb3DQEHAaCCBZsEggWXMIIFkzCCBY8GCyqGSIb3DQEM" +
            "CgECoIIFQDCCBTwwZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFCHsdxTc+UjItt5h9WPDPT76X0ENAgInEAIBIDAMBggq" +
            "hkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQ0qRuYwUKk8cEzfsrvhmdcwSCBNC5yzDZDNOqVmgAnQ5Uv0HD7sWW8EDvlWCVt+O+OWjD" +
            "22ctw5fXiVT1mHgc6F0tjGNPyXj7P4RTs1b4qvXygd9o39O8NUS2ud6rY0m7Hq4fQqYV9allpUdl1onbGaCGkg1wjwL2IgkMx3ak" +
            "HWNeGgQ+NSxoDpzVxYLaof+vju8VPvA5spwp3bIZqf4GoqD23/DZfepLlzTyqwNpaUogjYT2ntrDcAV3YKr9kQL9H3BLBrddZUzM" +
            "L5Dxeh7ingKNyc1UHYUd0LfeXL0tQpnV/+PlUIbbAoGd+hwV23hPKy0cRT64kAH98klYGS5XTlnudE+PILiFUZ05V59Jv2bJRIwB" +
            "1wMCuM6768S+NKOQDNYMKMwJ8hruL4PpBqEQwpPojom7qdWGCPwGhDCdmKe0nn6HkKYMMGxvvjwYhTvI4+apMlXKcKsIpyoMRtWu" +
            "shTkJQSczB6DAiHUA+MO1vxNBOHxbotO3V7kCxk70yfZLhUQVBsTGNhlLb2kw1b8l7dCZ4CBWyWldpY2gpTPcJj2PsD3PV/QcJAw" +
            "SW4/CwE3m7cw1n7xnmaiPPiiwSvjIOdSyEymkcQ+vJwdTF/D9K0YvgEl1XQfsKP4WvF0xu4FrnZR4hHqwFnBdV/yY5GwNUUsf+ex" +
            "vq6ql3Rhx4cLoIs6v8LXiohtVOo+ZOukpEKn0vOIa24HcVbogvfscdUmJ7LVc1ZUXFZz2WY6r+s69yZES8CVq03Sw+UHP+wJDo4c" +
            "jViFJj8hi0eUHx7YblU7RHxsS3uFQqxnjYEHkkqvEqva/vWkaA8lRFpJDD1J0EhT51vtdXq4Oa85qgx4Nx+IgPFZuI8vx6T7cCqr" +
            "wYCAqVtvCYov2XkddLS6PkhAEHDekvlA4gWAVit8jPlFHPrJre4NxToW+VPbSTq+EtftA/+flKoeIZp2e/tF9/nblgYFqIiwl7IT" +
            "KKcsJzSm2s2+/nyjFQGJFqH5/lNUF1fdFCdPl9VXdmmJRjCU2/No0arlyIhFPL6vZPCbIX0HVmmZdWIfd2BwOO04lyeKrcjeSTEt" +
            "MDxq4PknanXegqnJXm75JC1cZWIb7KQSYk5DtfoUVdW2ipP0gvvyL3RBMhm+//149Z6pLFwCvmYPR6j8U/roGHpaIEn/syW8wccr" +
            "uL2PldetHA3Gwyus0GaihGOujmDow5cyNd26OKEjz1Dko0AwqKY8Id/gOYHE2yPJZ1vlcTa5gs2wxIjC7GFGINPPd5lkXzNRFEg9" +
            "pDvx/3QCw9fioNzll5kddriBmZuctWODzn1km+vwhfKc245TnqDOfS1TL6MBokPq5VMignxkZhbKwQHpfUbRuUFrRfapXtEeF8HP" +
            "VRnaEg04XCSztoed3UGWzPTt5JiF/m+YRC20l00OnRSND8NCg3U+srUQ3mkV+yaaA85NWP0avJatGC3JZCQY/jZ9Vjp1Zlu/3yv0" +
            "iRajOzfBF8CYS2AiZ+uelgweuZ4bUcl7NER5HuL9mch4U3fZc7dN9CZREsYLVqOtmUu0Xg+NEt+OjWAPh4YRV6kKJE7KntA/wbMk" +
            "Jy+c/h+Cy5z6E8SjqWH8kARDZv9QMqnRrpGx3TP0PMKwkQXCDnsxKOKcW2zLg9CfGEYhig+jjdHvc5AiKHZS3DTVYsuynO50sEXj" +
            "xzE8MBcGCSqGSIb3DQEJFDEKHggAdABlAHMAdDAhBgkqhkiG9w0BCRUxFAQSVGltZSAxNzkzNjI1OTQ1MTc0MIIEEQYJKoZIhvcN" +
            "AQcGoIIEAjCCA/4CAQAwggP3BgkqhkiG9w0BBwEwZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFIQ9f1ituxrFmihv9RMW" +
            "RX6YXdgOAgInEAIBIDAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQqA81m5nILQyaqq1oCaDW8YCCA4B0ifxAVtj1HTRz2fJM" +
            "D1KIPw9ugcJLjX2sBBjnT6jpQJ1nhQP35TLb/A+amUIHpfggcW/Zq2vbspNnwwEQFweXto7EfA5IEPpzneqbNYd1fZRwkaOPJZuE" +
            "LyF/KMPzE+qTDgIVxn/J9/74JSYNGPtT/HZYc9Df1MzgVVmA1vNDBcwWWHqMhYaPkrJ+M5U8dqpiEzQKiVXZbU9Ti/sTtKiMgoru" +
            "HD05ComDw8dyGb/aQLs6l0PgNFUPX4Z2aiU7Gk1k8G+BY3iQE2vkrws5mur9rAX3UkGtBzeciScSHwezB23Ll/SfUCgORBxU3Y0Q" +
            "2qVSdFU0f8rcE3UjkGcJeFa9MVzr4uFbovK+efzPZuLsQBspDOZUJqqqt+mlspjlkMSaTHMH+sjoHrAv/oM3HUUdot4pDYpMqBhg" +
            "wTugNOv0wYdDKRDCtApaODpXmFPlkgVY8HGP+BNz1VqkO19uphTEFCsdauOQqhJ/DLaBm3jmTyrJQT37ZPaaHVqrW4exT0okdp6y" +
            "h+WdYvDe7fYKXSE0KCwYYOgQQB830e4iDq5abz6C5dDyAgtgCMGwp2ei15EATNG/Sn+0870I98T9PhFu9UYKwFzCmhCM5G8l+J7d" +
            "CikcU99q2UlC3i5r3GLt+MrfE/Ys5pcPEQ0LRakodrrXpX9sSt885vPVU8zP4JAcTFFzbQm7jHq0+pZoL0+9jvy9Aer7Dh+/v6eU" +
            "XC6cMgU3hIOVcZAfDCz+luSHlJ+MqHeBvFFUG4NGwLwHlb3GcZS6AWgXQPTLO64E2OtScwhEgpqN7m7vkDnkhkRRWcitAstg0xU1" +
            "VOiiPh51fKFuwlzjR7ws/Qv049c8pCZKNSnmxiV3RMFEyXcKdmmWNaDRkbv1PUEqlOAYt0p0Ym/+0MJfEH6GGtmKj1pJgZJSh/ZI" +
            "qpEiBRbeOGiQK+5ULdf6yx1XrhKXLVtXzhXyO+A8CArnXj6qA02OcEXC5VLqiRx9daktDgaXUl9ZhtY1assPwZSQn/hODFGdgdCV" +
            "heajWzx7xugbBiBt16O0aU5MSnK+Tsair84Q0pMF69ngdh5ArLouxt4WOIn4nxsvwnEmRQoJcev0ohN2GSRqnU/W2S4md7cYGLv6" +
            "/aMDaFjpcILC9sD5qMQX3MWfPYOu5lC3q6bv6IG5OUNSOEtyS8gaCIBkcOZOEj3/xVRJrVP8fb24LjBNMDEwDQYJYIZIAWUDBAIB" +
            "BQAEINjtG4UEwYhDBdz043UBtCykJ8VoXDe9v2h0RQZ8AHr6BBQKdc813SW6VwEhSt8eU/Gpku+caQICJxA=";

    private TestKeystores() {
    }

    public static byte[] pkcs12() {
        return Base64.getDecoder().decode(PKCS12_BASE64);
    }

    /** The same key and certificate, re-encoded as a JKS keystore. */
    public static byte[] jks() {
        try {
            KeyStore source = KeyStore.getInstance("PKCS12");
            source.load(new ByteArrayInputStream(pkcs12()), PASSPHRASE);

            KeyStore target = KeyStore.getInstance("JKS");
            target.load(null, PASSPHRASE);
            for (String alias : Collections.list(source.aliases())) {
                if (source.isKeyEntry(alias)) {
                    target.setKeyEntry(alias, source.getKey(alias, PASSPHRASE), PASSPHRASE,
                            source.getCertificateChain(alias));
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            target.store(out, PASSPHRASE);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("cannot build the JKS fixture", e);
        }
    }
}
