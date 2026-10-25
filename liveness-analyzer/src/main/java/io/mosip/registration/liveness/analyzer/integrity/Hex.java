package io.mosip.registration.liveness.analyzer.integrity;

/** Lowercase hex encoding (java.util.Base64 is not available on Android API 21). */
public final class Hex {

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() { }

    public static String encode(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[2 * i] = DIGITS[(bytes[i] >> 4) & 0xF];
            out[2 * i + 1] = DIGITS[bytes[i] & 0xF];
        }
        return new String(out);
    }

    /** Strict decode: even length, lowercase hex digits only. */
    public static byte[] decode(String hex) {
        if (hex == null || hex.length() % 2 != 0) throw new IllegalArgumentException("hex length must be even");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((digit(hex.charAt(2 * i)) << 4) | digit(hex.charAt(2 * i + 1)));
        }
        return out;
    }

    private static int digit(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        throw new IllegalArgumentException("not a lowercase hex digit");
    }
}
