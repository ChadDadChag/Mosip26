package io.mosip.registration.liveness.device.demo;

import io.mosip.registration.liveness.device.util.JpegToRgb888Converter;
import io.mosip.registration.liveness.device.util.MjpegFrameReader;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Checks an MDS face stream without the Registration Client: sends the same request the client does
 * ({@code STREAM /stream} with {@code {"serialNo","deviceSubId","timeout"}}, as in
 * {@code MosipDeviceSpecification_SBI_1_0_ProviderImpl}), reads frames with the same {@link MjpegFrameReader} the
 * liveness path uses, decodes them, and reports count, resolution and frame rate. Optionally saves the stream as a
 * recording that {@code MockFrameSource.ofVideoFile} and the patched MockMDS video mode can replay.
 *
 * <pre>
 * SbiStreamProbe &lt;port&gt; &lt;serialNo&gt; [frames=60] [--subId 0] [--host 127.0.0.1] [--save out.mjpeg]
 * </pre>
 * Exit code 0 when at least one frame was received and decoded.
 */
public final class SbiStreamProbe {

    /** What the probe saw. */
    public static final class Result {
        public final String status;
        public final boolean multipart;
        public final int frames;
        public final int width, height;
        public final double fps;
        public final String error;

        Result(String status, boolean multipart, int frames, int width, int height, double fps, String error) {
            this.status = status; this.multipart = multipart; this.frames = frames; this.width = width;
            this.height = height; this.fps = fps; this.error = error;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s | %s | frames=%d | %dx%d | %.1f fps%s", status,
                    multipart ? "multipart stream" : "NOT a multipart stream", frames, width, height, fps,
                    error == null ? "" : " | " + error);
        }
    }

    private SbiStreamProbe() {
    }

    /** The JSON body the Registration Client 1.2.0.x sends to /stream. */
    static String requestBody(String serialNo, String deviceSubId, long timeoutMs) {
        return "{\"serialNo\":\"" + serialNo + "\",\"deviceSubId\":\"" + deviceSubId + "\",\"timeout\":\""
                + timeoutMs + "\",\"dimensions\":null}";
    }

    public static Result probe(String host, int port, String serialNo, String deviceSubId, int maxFrames,
                               long timeoutMs, OutputStream saveTo) throws IOException {
        String body = requestBody(serialNo, deviceSubId, timeoutMs);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout((int) Math.max(1000, timeoutMs));
            OutputStream out = socket.getOutputStream();
            out.write(("STREAM /stream HTTP/1.1\r\nHost: " + host + ":" + port + "\r\n"
                    + "Content-Type: application/json\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                    + "\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = new BufferedInputStream(socket.getInputStream());
            String head = readHead(in);
            String status = head.contains("\r\n") ? head.substring(0, head.indexOf("\r\n")) : head;
            boolean multipart = head.toLowerCase(Locale.ROOT).contains("multipart/x-mixed-replace");
            int frames = 0, width = 0, height = 0;
            String error = null;
            long t0 = System.nanoTime(), t1 = t0;
            while (frames < maxFrames) {
                byte[] jpeg;
                try {
                    jpeg = MjpegFrameReader.readNextFrame(in);
                } catch (EOFException e) {
                    error = frames == 0 ? "stream ended before the first frame (device error response?)" : null;
                    break;
                } catch (IOException e) {
                    error = e.getClass().getSimpleName() + ": " + e.getMessage();
                    break;
                }
                JpegToRgb888Converter.DecodedImage img = JpegToRgb888Converter.convert(jpeg);
                width = img.getWidth();
                height = img.getHeight();
                if (saveTo != null) {
                    saveTo.write(("--BoundaryString\r\nContent-type: image/jpeg\r\nContent-Length: " + jpeg.length
                            + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    saveTo.write(jpeg);
                    saveTo.write("\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                }
                frames++;
                t1 = System.nanoTime();
            }
            double secs = (t1 - t0) / 1e9;
            return new Result(status, multipart, frames, width, height, frames > 1 && secs > 0 ? (frames - 1) / secs : 0,
                    error);
        }
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            b.append((char) c);
            if (b.length() >= 4 && b.substring(b.length() - 4).equals("\r\n\r\n")) break;
            if (b.length() > 16 * 1024) break;
        }
        return b.toString();
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: SbiStreamProbe <port> <serialNo> [frames=60] [--subId 0] [--host 127.0.0.1] [--save out.mjpeg]");
            System.exit(64);
        }
        int port = Integer.parseInt(args[0]);
        String serial = args[1];
        int frames = 60;
        String subId = "0", host = "127.0.0.1", save = null;
        for (int i = 2; i < args.length; i++) {
            if ("--subId".equals(args[i]) && i + 1 < args.length) subId = args[++i];
            else if ("--host".equals(args[i]) && i + 1 < args.length) host = args[++i];
            else if ("--save".equals(args[i]) && i + 1 < args.length) save = args[++i];
            else frames = Integer.parseInt(args[i]);
        }
        OutputStream out = save == null ? null : new FileOutputStream(save);
        try {
            Result r = probe(host, port, serial, subId, frames, 10000, out);
            System.out.println(r);
            System.exit(r.frames > 0 ? 0 : 1);
        } finally {
            if (out != null) out.close();
        }
    }
}
