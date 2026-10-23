package io.mosip.registration.liveness.device.demo;

import io.mosip.registration.liveness.device.LivenessTestSupport;
import io.mosip.registration.liveness.device.LivenessTestSupport.Scene;
import io.mosip.registration.liveness.device.util.MjpegFrameReader;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SbiStreamProbeTest {

    /** A one-request MDS: records the request and answers with {@code response}. */
    private static int serveOnce(final byte[] response, final AtomicReference<String> request) throws Exception {
        final ServerSocket server = new ServerSocket(0);
        Thread t = new Thread(() -> {
            try (Socket s = server.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder head = new StringBuilder();
                String line;
                int length = 0;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    head.append(line).append('\n');
                    if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                }
                char[] body = new char[length];
                int read = 0;
                while (read < length) read += in.read(body, read, length - read);
                request.set(head + new String(body));
                OutputStream out = s.getOutputStream();
                out.write(response);
                out.flush();
            } catch (Exception ignored) {
                // test server
            } finally {
                try {
                    server.close();
                } catch (Exception ignored) {
                    // closing
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    @Test
    public void sendsTheRegistrationClientRequestAndReadsTheStream() throws Exception {
        ByteArrayOutputStream resp = new ByteArrayOutputStream();
        resp.write(("HTTP/1.0 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=--BoundaryString\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        resp.write(LivenessTestSupport.recording(Scene.LIVE, 5));
        AtomicReference<String> request = new AtomicReference<>();
        int port = serveOnce(resp.toByteArray(), request);

        ByteArrayOutputStream saved = new ByteArrayOutputStream();
        SbiStreamProbe.Result r = SbiStreamProbe.probe("127.0.0.1", port, "2345678901", "0", 10, 5000, saved);

        assertTrue(r.multipart);
        assertEquals(5, r.frames);
        assertEquals(320, r.width);
        assertEquals(240, r.height);
        String req = request.get();
        assertNotNull(req);
        assertTrue("HTTP method STREAM like the client", req.startsWith("STREAM /stream HTTP/1.1"));
        assertTrue("identifies the device by serialNo like the client", req.contains("\"serialNo\":\"2345678901\""));
        assertTrue(req.contains("\"deviceSubId\":\"0\""));
        // The saved stream is a recording MockFrameSource and the MockMDS video mode can replay.
        ByteArrayInputStream in = new ByteArrayInputStream(saved.toByteArray());
        for (int i = 0; i < 5; i++) assertTrue(MjpegFrameReader.readNextFrame(in).length > 0);
    }

    @Test
    public void reportsAnErrorResponseInsteadOfAStream() throws Exception {
        byte[] resp = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n"
                + "{\"errorCode\":\"605\",\"errorInfo\":\"Device is not connected\"}").getBytes(StandardCharsets.US_ASCII);
        int port = serveOnce(resp, new AtomicReference<String>());
        SbiStreamProbe.Result r = SbiStreamProbe.probe("127.0.0.1", port, "2345678901", "0", 10, 5000, null);
        assertFalse(r.multipart);
        assertEquals(0, r.frames);
        assertNotNull(r.error);
        assertTrue(r.toString().contains("NOT a multipart stream"));
    }
}
