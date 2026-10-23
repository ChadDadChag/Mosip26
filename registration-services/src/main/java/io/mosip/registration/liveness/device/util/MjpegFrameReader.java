package io.mosip.registration.liveness.device.util;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Reads one JPEG frame from the SBI /stream byte stream.
 *
 * <p>The existing Registration Client stream parser is Content-Length based and
 * does not perform full MIME parsing. This reader intentionally follows that
 * wire format so the liveness path remains compatible with the existing SBI
 * provider while keeping the parser independent of JavaFX.</p>
 *
 * <p>Corrupt input is bounded: headers are scanned line by line (linear time) up
 * to {@link #MAX_HEADER_BYTES}, and a Content-Length above {@link #MAX_FRAME_BYTES}
 * is rejected before anything is allocated.</p>
 */
public final class MjpegFrameReader {

    /** Upper bound for one encoded frame. A 4K JPEG at maximum quality is well under this. */
    public static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

    private static final byte JPEG_START = (byte) 0xFF;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final String CONTENT_LENGTH = "content-length:";

    private MjpegFrameReader() {
    }

    /**
     * Reads the next JPEG payload.
     *
     * @param input stream returned by the SBI provider
     * @return complete JPEG bytes
     * @throws EOFException if the stream ends before a complete frame
     * @throws IOException  if the framing is invalid
     */
    public static byte[] readNextFrame(InputStream input) throws IOException {
        if (input == null) {
            throw new IOException("SBI stream is null");
        }

        int contentLength = readContentLength(input);

        // Preserve the existing Registration Client behavior: after the
        // Content-Length line, advance to the first JPEG SOI byte.
        int value;
        do {
            value = input.read();
            if (value == -1) {
                throw new EOFException("SBI stream ended before JPEG payload");
            }
        } while (value != (JPEG_START & 0xFF));

        byte[] jpeg = new byte[contentLength];
        jpeg[0] = JPEG_START;
        readFully(input, jpeg, 1, contentLength - 1);
        return jpeg;
    }

    /** Scans header lines until one contains "Content-Length:" (case-insensitive, anywhere in the line). */
    private static int readContentLength(InputStream input) throws IOException {
        byte[] line = new byte[256];
        int lineLength = 0;
        int headerBytes = 0;

        while (headerBytes < MAX_HEADER_BYTES) {
            int value = input.read();
            if (value == -1) {
                throw new EOFException("SBI stream ended while reading frame headers");
            }
            headerBytes++;

            if (value == '\n' || value == '\r') {
                Integer length = parseContentLength(line, lineLength);
                if (length != null) {
                    return length;
                }
                lineLength = 0;
                continue;
            }
            if (lineLength == line.length) {
                // Over-long "line" (e.g. binary garbage): keep only the tail, enough to hold a header.
                System.arraycopy(line, line.length - 128, line, 0, 128);
                lineLength = 128;
            }
            line[lineLength++] = (byte) value;
        }
        throw new IOException("Missing or invalid Content-Length (no header within " + MAX_HEADER_BYTES + " bytes)");
    }

    private static Integer parseContentLength(byte[] line, int lineLength) throws IOException {
        String text = new String(line, 0, lineLength, StandardCharsets.ISO_8859_1);
        int marker = text.toLowerCase(Locale.ROOT).indexOf(CONTENT_LENGTH);
        if (marker < 0) {
            return null;
        }
        String lengthText = text.substring(marker + CONTENT_LENGTH.length()).trim();
        long length;
        try {
            length = Long.parseLong(lengthText);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid Content-Length: " + lengthText, e);
        }
        if (length <= 0) {
            throw new IOException("Missing or invalid Content-Length: " + length);
        }
        if (length > MAX_FRAME_BYTES) {
            throw new IOException("Content-Length " + length + " exceeds limit of " + MAX_FRAME_BYTES + " bytes");
        }
        return (int) length;
    }

    private static void readFully(InputStream input, byte[] target, int offset, int length) throws IOException {
        int remaining = length;
        int position = offset;
        while (remaining > 0) {
            int count = input.read(target, position, remaining);
            if (count == -1) {
                throw new EOFException("SBI stream ended in the middle of a JPEG payload");
            }
            if (count == 0) {
                int single = input.read();
                if (single == -1) {
                    throw new EOFException("SBI stream ended in the middle of a JPEG payload");
                }
                target[position++] = (byte) single;
                remaining--;
            } else {
                position += count;
                remaining -= count;
            }
        }
    }
}
