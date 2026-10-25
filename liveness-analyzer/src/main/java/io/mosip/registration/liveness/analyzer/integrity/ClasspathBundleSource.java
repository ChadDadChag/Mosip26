package io.mosip.registration.liveness.analyzer.integrity;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/** The bundle packaged in the analyzer jar, under {@value #DEFAULT_PREFIX} by default. */
public final class ClasspathBundleSource implements BundleSource {

    public static final String DEFAULT_PREFIX = "liveness/models/";

    private final ClassLoader loader;
    private final String prefix;

    public ClasspathBundleSource() {
        this(ClasspathBundleSource.class.getClassLoader(), DEFAULT_PREFIX);
    }

    public ClasspathBundleSource(ClassLoader loader, String prefix) {
        if (loader == null || prefix == null) throw new IllegalArgumentException("null argument");
        this.loader = loader;
        this.prefix = prefix;
    }

    @Override
    public byte[] read(String name) throws IOException {
        InputStream in = loader.getResourceAsStream(prefix + name);
        if (in == null) throw new FileNotFoundException("missing " + describe() + name);
        return Reader.readCapped(in, name, MAX_FILE_BYTES);
    }

    @Override
    public String describe() {
        return "classpath:" + prefix;
    }
}
