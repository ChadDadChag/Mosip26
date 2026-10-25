package io.mosip.registration.liveness.analyzer.integrity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Where a model bundle's files come from (manifest.json, manifest.sig and the model files). Implementations:
 * {@link ClasspathBundleSource} (bundled in the jar), {@link DirectoryBundleSource} (a staged or installed
 * directory); Android adds an asset source. Nothing read from a source is trusted until {@link VerifiedBundle}
 * has checked it.
 */
public interface BundleSource {

    /** No single bundle file may be larger than this (64 MiB). */
    int MAX_FILE_BYTES = 64 * 1024 * 1024;

    /** The whole file, at most {@link #MAX_FILE_BYTES}. Missing, unreadable or too-large files throw. */
    byte[] read(String name) throws IOException;

    /** Where the files come from, for messages and audit (no secrets). */
    String describe();

    /** Shared capped reader for implementations. */
    final class Reader {
        private Reader() { }

        /** Reads the stream to the end, failing if it holds more than {@code maxBytes}; closes it. */
        public static byte[] readCapped(InputStream in, String name, int maxBytes) throws IOException {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16];
                long total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > maxBytes) throw new IOException(name + " is larger than " + maxBytes + " bytes");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            } finally {
                in.close();
            }
        }
    }
}
