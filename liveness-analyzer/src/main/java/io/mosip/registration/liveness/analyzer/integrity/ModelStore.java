package io.mosip.registration.liveness.analyzer.integrity;

import io.mosip.registration.liveness.analyzer.runtime.ModelRole;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.LivenessEngineException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Offline model updates. Layout under {@code root}:
 * <pre>
 *   bundles/&lt;version&gt;/   manifest.json, manifest.sig and the four model files
 *   current               text file holding the active version
 * </pre>
 * Downloading is the host app's job; the store only accepts already-staged files, and only after verifying them.
 *
 * <p>{@link #install}: verify the staged bundle; refuse anything not STRICTLY newer than the current version
 * (anti-rollback); write the verified bytes (never a re-read of the staged files) to {@code bundles/<v>.tmp-<random>}
 * with fsync; rename to {@code bundles/<v>}; re-verify from disk; switch {@code current} via {@code current.tmp}
 * (fsync, delete, rename); keep only the current and the previous bundle.
 *
 * <p>{@link #loadCurrent} fails closed: the pointer, the directory name and the manifest version must all agree and
 * the bundle must verify, otherwise MODEL_INTEGRITY_FAILED (a tampered disk is treated as an attack).
 *
 * <p>{@link #rollback} is an explicit OPERATOR action: it points {@code current} at the previous installed bundle
 * (which must still verify) and deliberately bypasses anti-rollback.
 */
public final class ModelStore {

    static final String BUNDLES = "bundles";
    static final String CURRENT = "current";
    static final String CURRENT_TMP = "current.tmp";
    static final String TMP_MARK = ".tmp-";

    private final File root;
    private final File bundles;
    private final SecureRandom random = new SecureRandom();

    public ModelStore(File root) {
        if (root == null) throw new IllegalArgumentException("root is null");
        this.root = root;
        this.bundles = new File(root, BUNDLES);
    }

    public File root() {
        return root;
    }

    /** The active bundle, verified; null if nothing has been installed. */
    public synchronized VerifiedBundle loadCurrent(TrustedKeys keys) throws LivenessEngineException {
        recoverPointer(keys);
        String v = readPointer();
        if (v == null) return null;
        return loadInstalled(v, keys);
    }

    /** The version in {@code current} (after pointer recovery), or null. Does not verify the bundle. */
    public synchronized String currentVersion() throws LivenessEngineException {
        return readPointer();
    }

    /** Installed bundle versions, oldest first (temporary directories excluded). */
    public synchronized List<String> installedVersions() {
        List<String> out = new ArrayList<String>();
        File[] dirs = bundles.listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (d.isDirectory() && VerifiedBundle.isValidVersion(d.getName())) out.add(d.getName());
            }
        }
        Collections.sort(out, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                return VerifiedBundle.compareVersions(a, b);
            }
        });
        return out;
    }

    /**
     * Verifies and installs a staged bundle and makes it current.
     *
     * @return the installed version
     * @throws LivenessEngineException  MODEL_INTEGRITY_FAILED if the staged bundle (or the written copy) does not verify
     * @throws IllegalArgumentException if the bundle is not newer than the current one ("not newer than ...")
     * @throws IOException              on a disk error (the current bundle stays active)
     */
    public synchronized String install(BundleSource staged, TrustedKeys keys) throws LivenessEngineException, IOException {
        VerifiedBundle bundle = VerifiedBundle.load(staged, keys);
        recoverPointer(keys);
        String previous = readPointer();
        String v = bundle.version();
        if (previous != null && VerifiedBundle.compareVersions(v, previous) <= 0) {
            throw new IllegalArgumentException("not newer than " + previous);
        }
        if (!bundles.isDirectory() && !bundles.mkdirs()) throw new IOException("cannot create " + bundles);

        File tmp = new File(bundles, v + TMP_MARK + Hex.encode(randomBytes()));
        if (!tmp.mkdir()) throw new IOException("cannot create " + tmp);
        try {
            writeSynced(new File(tmp, VerifiedBundle.MANIFEST), bundle.manifestBytes());
            writeSynced(new File(tmp, VerifiedBundle.SIGNATURE), bundle.signatureBytes());
            for (ModelRole role : ModelRole.values()) {
                writeSynced(new File(tmp, bundle.fileName(role)), bundle.modelBytes().get(role));
            }
            File dir = new File(bundles, v);
            if (dir.exists()) deleteRecursively(dir); // stale copy of a version that never became current
            if (!tmp.renameTo(dir)) throw new IOException("cannot rename " + tmp + " to " + dir);
            tmp = null;
            try {
                loadInstalled(v, keys);
            } catch (LivenessEngineException e) {
                deleteRecursively(dir);
                throw e;
            }
        } finally {
            if (tmp != null) deleteRecursively(tmp);
        }
        writePointer(v);
        prune(v, previous);
        return v;
    }

    /**
     * OPERATOR ACTION: makes the previous installed bundle current again, bypassing anti-rollback by design.
     *
     * @return the version that is now current
     * @throws IllegalStateException   if there is no current or no older installed bundle
     * @throws LivenessEngineException MODEL_INTEGRITY_FAILED if the previous bundle does not verify
     */
    public synchronized String rollback(TrustedKeys keys) throws LivenessEngineException, IOException {
        recoverPointer(keys);
        String current = readPointer();
        if (current == null) throw new IllegalStateException("nothing installed");
        String previous = null;
        for (String v : installedVersions()) {
            if (VerifiedBundle.compareVersions(v, current) < 0) previous = v;
        }
        if (previous == null) throw new IllegalStateException("no bundle older than " + current);
        loadInstalled(previous, keys);
        writePointer(previous);
        return previous;
    }

    private VerifiedBundle loadInstalled(String v, TrustedKeys keys) throws LivenessEngineException {
        File dir = new File(bundles, v);
        if (!dir.isDirectory()) throw fail("installed bundle " + v + " is missing");
        VerifiedBundle b = VerifiedBundle.load(new DirectoryBundleSource(dir), keys);
        if (!b.version().equals(v)) throw fail("bundle in " + v + " declares version " + b.version());
        return b;
    }

    /** current missing but current.tmp present and pointing at a verifiable bundle: finish the interrupted switch. */
    private void recoverPointer(TrustedKeys keys) throws LivenessEngineException {
        File current = new File(root, CURRENT), tmp = new File(root, CURRENT_TMP);
        if (current.exists() || !tmp.isFile()) return;
        String v = readVersionFile(tmp);
        boolean ok = false;
        if (VerifiedBundle.isValidVersion(v)) {
            try {
                loadInstalled(v, keys);
                ok = true;
            } catch (LivenessEngineException e) {
                ok = false;
            }
        }
        // An unusable half-written pointer is ignored (nothing was switched); the next install overwrites it.
        if (ok && !tmp.renameTo(current)) throw fail("cannot finish the interrupted switch to " + v);
    }

    private String readPointer() throws LivenessEngineException {
        File current = new File(root, CURRENT);
        if (!current.exists()) return null;
        String v = readVersionFile(current);
        if (!VerifiedBundle.isValidVersion(v)) throw fail("the current-version pointer is corrupt");
        return v;
    }

    private static String readVersionFile(File f) {
        try {
            byte[] b = BundleSource.Reader.readCapped(new FileInputStream(f), f.getName(), 256);
            return new String(b, StandardCharsets.US_ASCII).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private void writePointer(String v) throws IOException {
        File current = new File(root, CURRENT), tmp = new File(root, CURRENT_TMP);
        writeSynced(tmp, (v + "\n").getBytes(StandardCharsets.US_ASCII));
        if (current.exists() && !current.delete()) throw new IOException("cannot replace " + current);
        if (!tmp.renameTo(current)) throw new IOException("cannot rename " + tmp + " to " + current);
    }

    /** Keeps the current and the previous bundle; removes older bundles and leftover temporary directories. */
    private void prune(String current, String previous) {
        File[] dirs = bundles.listFiles();
        if (dirs == null) return;
        for (File d : dirs) {
            String n = d.getName();
            boolean keep = n.equals(current) || n.equals(previous);
            if (!keep && (n.contains(TMP_MARK) || VerifiedBundle.isValidVersion(n))) deleteRecursively(d);
        }
    }

    private byte[] randomBytes() {
        byte[] b = new byte[6];
        random.nextBytes(b);
        return b;
    }

    static void writeSynced(File f, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
    }

    static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        f.delete();
    }

    private static LivenessEngineException fail(String message) {
        return new LivenessEngineException(ErrorCode.MODEL_INTEGRITY_FAILED, message);
    }
}
