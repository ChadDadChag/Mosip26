package io.mosip.registration.liveness.analyzer.testing;

import io.mosip.registration.liveness.analyzer.runtime.ModelRole;
import io.mosip.registration.liveness.analyzer.runtime.OnnxModels;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

/**
 * TEST ONLY: reads the four bundled models from {@code liveness/models/} on the classpath WITHOUT integrity
 * verification. Production code gets model bytes only from the verified loader (Phase 4).
 */
public final class TestModels {

    private static OnnxModels shared;

    private TestModels() { }

    public static Map<ModelRole, byte[]> load() throws IOException {
        Map<ModelRole, byte[]> out = new EnumMap<ModelRole, byte[]>(ModelRole.class);
        for (ModelRole role : ModelRole.values()) {
            out.put(role, read("liveness/models/" + role.fileName()));
        }
        return out;
    }

    /** One lazily created set of sessions for the whole test run (left for the JVM to release). */
    public static synchronized OnnxModels shared() throws Exception {
        if (shared == null) shared = OnnxModels.create(load(), 2);
        return shared;
    }

    static byte[] read(String resource) throws IOException {
        InputStream in = TestModels.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) throw new IOException("missing test resource " + resource);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
