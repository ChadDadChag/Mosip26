package io.mosip.registration.liveness.analyzer.integrity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

/** A bundle in a plain directory (staged for install, or installed in a {@link ModelStore}). */
public final class DirectoryBundleSource implements BundleSource {

    private final File dir;

    public DirectoryBundleSource(File dir) {
        if (dir == null) throw new IllegalArgumentException("dir is null");
        this.dir = dir;
    }

    @Override
    public byte[] read(String name) throws IOException {
        File f = new File(dir, name);
        if (!f.isFile()) throw new FileNotFoundException("missing " + f.getPath());
        if (f.length() > MAX_FILE_BYTES) throw new IOException(name + " is larger than " + MAX_FILE_BYTES + " bytes");
        return Reader.readCapped(new FileInputStream(f), name, MAX_FILE_BYTES);
    }

    @Override
    public String describe() {
        return dir.getPath();
    }

    public File dir() {
        return dir;
    }
}
