package io.mosip.registration.liveness.device.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

import org.junit.Test;

public class MjpegFrameReaderTest {

    @Test
    public void readsContentLengthDelimitedJpeg() throws Exception {
        byte[] jpeg = createJpeg(2, 1);
        byte[] stream = buildFrame(jpeg);

        byte[] result = MjpegFrameReader.readNextFrame(new ByteArrayInputStream(stream));

        assertArrayEquals(jpeg, result);
    }

    @Test
    public void readsTwoConsecutiveFrames() throws Exception {
        byte[] first = createJpeg(1, 1);
        byte[] second = createJpeg(2, 2);
        byte[] combined = concat(buildFrame(first), buildFrame(second));

        ByteArrayInputStream input = new ByteArrayInputStream(combined);

        assertArrayEquals(first, MjpegFrameReader.readNextFrame(input));
        assertArrayEquals(second, MjpegFrameReader.readNextFrame(input));
    }

    @Test
    public void rejectsMissingContentLength() throws Exception {
        try {
            MjpegFrameReader.readNextFrame(new ByteArrayInputStream(
                    "--BoundaryString\r\nContent-Type: image/jpeg\r\n\r\n".getBytes("UTF-8")));
            fail("Expected IOException");
        } catch (IOException expected) {
            assertEquals(true, expected.getMessage().contains("Content-Length")
                    || expected instanceof EOFException);
        }
    }

    @Test(expected = IOException.class)
    public void rejectsMalformedContentLengthValue() throws Exception {
        MjpegFrameReader.readNextFrame(new ByteArrayInputStream(
                "--BoundaryString\r\nContent-type: image/jpeg\r\nContent-Length: notanumber\r\n\r\n"
                        .getBytes("UTF-8")));
    }

    @Test(expected = EOFException.class)
    public void rejectsTruncatedPayload() throws Exception {
        byte[] jpeg = createJpeg(16, 16);
        byte[] full = buildFrame(jpeg);
        byte[] truncated = java.util.Arrays.copyOf(full, full.length - 5);
        MjpegFrameReader.readNextFrame(new ByteArrayInputStream(truncated));
    }

    @Test(expected = EOFException.class)
    public void rejectsStreamThatEndsDuringHeader() throws Exception {
        MjpegFrameReader.readNextFrame(new ByteArrayInputStream("--BoundaryStr".getBytes("UTF-8")));
    }

    @Test(expected = IOException.class)
    public void rejectsNullStream() throws Exception {
        MjpegFrameReader.readNextFrame(null);
    }

    @Test(expected = EOFException.class)
    public void rejectsEmptyStream() throws Exception {
        MjpegFrameReader.readNextFrame(new ByteArrayInputStream(new byte[0]));
    }

    @Test
    public void readsExactMockMdsWireFormat() throws Exception {
        // Byte-for-byte reproduction of mosip-mock-services-master's
        // org.biometric.provider.StreamRequest.doPost() framing:
        // "--BoundaryString\r\nContent-type: image/jpeg\r\nContent-Length: N\r\n\r\n<jpeg>\r\n\r\n"
        byte[] jpeg1 = createJpeg(4, 4);
        byte[] jpeg2 = createJpeg(4, 4);
        byte[] combined = concat(buildFrame(jpeg1), buildFrame(jpeg2));
        ByteArrayInputStream input = new ByteArrayInputStream(combined);

        assertArrayEquals(jpeg1, MjpegFrameReader.readNextFrame(input));
        assertArrayEquals(jpeg2, MjpegFrameReader.readNextFrame(input));
        try {
            MjpegFrameReader.readNextFrame(input);
            fail("expected EOFException after the last real frame");
        } catch (EOFException expected) {
            // correct
        }
    }

    @Test
    public void rejectsOversizedContentLengthWithoutAllocatingIt() throws Exception {
        try {
            MjpegFrameReader.readNextFrame(new ByteArrayInputStream(
                    "--BoundaryString\r\nContent-Length: 2000000000\r\n\r\n".getBytes("UTF-8")));
            fail("expected IOException for an oversized frame");
        } catch (EOFException wrong) {
            fail("must be rejected as invalid framing, not read until EOF");
        } catch (IOException expected) {
            assertEquals(true, expected.getMessage().contains("exceeds limit"));
        }
    }

    @Test
    public void headerGarbageWithoutContentLengthIsRejectedQuickly() throws Exception {
        byte[] garbage = new byte[200 * 1024];
        java.util.Arrays.fill(garbage, (byte) 'x');   // no line breaks at all
        long start = System.nanoTime();
        try {
            MjpegFrameReader.readNextFrame(new ByteArrayInputStream(garbage));
            fail("expected IOException");
        } catch (EOFException wrong) {
            fail("the header limit must trigger before the end of the garbage");
        } catch (IOException expected) {
            assertEquals(true, expected.getMessage().contains("Content-Length"));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertEquals("header scan must be linear, took " + elapsedMs + " ms", true, elapsedMs < 1000);
    }

    @Test
    public void contentLengthHeaderIsCaseInsensitive() throws Exception {
        byte[] jpeg = createJpeg(2, 2);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(("--b\r\ncontent-length:" + jpeg.length + "\r\n\r\n").getBytes("UTF-8"));
        output.write(jpeg);
        assertArrayEquals(jpeg, MjpegFrameReader.readNextFrame(new ByteArrayInputStream(output.toByteArray())));
    }

    private static byte[] createJpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpg", output)) {
            throw new IOException("No JPEG writer available");
        }
        return output.toByteArray();
    }

    private static byte[] buildFrame(byte[] jpeg) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("--BoundaryString\r\n".getBytes("UTF-8"));
        output.write("Content-type: image/jpeg\r\n".getBytes("UTF-8"));
        output.write(("Content-Length: " + jpeg.length + "\r\n\r\n").getBytes("UTF-8"));
        output.write(jpeg);
        output.write("\r\n\r\n".getBytes("UTF-8"));
        return output.toByteArray();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
