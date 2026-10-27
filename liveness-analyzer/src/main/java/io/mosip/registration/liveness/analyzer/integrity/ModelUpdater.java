package io.mosip.registration.liveness.analyzer.integrity;

import io.mosip.registration.liveness.analyzer.integrity.MiniJson.JsonException;
import io.mosip.registration.liveness.analyzer.runtime.ModelRole;
import io.mosip.registration.liveness.api.LivenessEngineException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Online model updates, shared by the Desktop and the Android client. {@link #check} fetches a small index,
 * {@code latest.json}:
 * <pre>
 *   {"schema": 1, "bundleVersion": "pad-2026.11.1", "zipUrl": "pad-2026.11.1.zip",
 *    "zipSha256": "&lt;64 hex&gt;", "zipSize": 18536000}
 * </pre>
 * When that version is newer than both the store's current bundle and the app's own, it downloads the zip
 * ({@code zipUrl} may be relative to the index), checks its size and SHA-256, unpacks it into a fresh directory and
 * installs it with {@link ModelStore#install(BundleSource, BundleSource, TrustedKeys)}. That verifies the signature
 * and every file exactly as for a bundle staged by hand. The signature is the trust anchor; HTTPS is defence in
 * depth: a server or network that serves other bytes gets FAILED, never an installed bundle.
 *
 * <p>Fails closed and leaves nothing behind: every failure returns FAILED with a {@link Reason}, the store is
 * unchanged (the install is atomic) and the download directory ({@code <store root>/update.tmp-<random>}) is deleted.
 *
 * <p>Limits:
 * <ul>
 *   <li>the index at most 16 KiB, with exactly the five fields above;</li>
 *   <li>the zip at most 64 MiB and exactly {@code zipSize} bytes;</li>
 *   <li>zip entries flat: {@code manifest.json} first, then {@code manifest.sig} and the files the manifest names,
 *       each once and at most its declared size, nothing else ({@link #writeZip} writes this layout);</li>
 *   <li>HTTPS only. Plain HTTP only to 127.0.0.1 or localhost, and only with
 *       {@link Builder#allowInsecureLocalhost} (tests and debug builds). Redirects (at most 3) are followed here,
 *       under the same rule.</li>
 * </ul>
 * One check runs at a time per process.
 *
 * <p>{@link #installZip} installs the same zip from local or removable media (F17, offline install), with the same
 * unpacking rules and the same fail-closed install; the version comes from the zip's own manifest.
 */
public final class ModelUpdater {

    public static final int INDEX_SCHEMA = 1;
    public static final int MAX_INDEX_BYTES = 16 * 1024;
    public static final int MAX_ZIP_BYTES = 64 * 1024 * 1024;
    /** The files a manifest declares may add up to at most this (the zip is compressed). */
    static final long MAX_UNPACKED_BYTES = 2L * MAX_ZIP_BYTES;
    /** A manifest may name at most this many files (a bundle has one per model role), so a zip has at most 2 more. */
    static final int MAX_MANIFEST_FILES = 16;
    static final int MAX_REDIRECTS = 3;
    static final String TMP_PREFIX = "update.tmp-";

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> INDEX_KEYS = new HashSet<String>(Arrays.asList(
            "schema", "bundleVersion", "zipUrl", "zipSha256", "zipSize"));
    private static final Object CHECK_LOCK = new Object();

    /** The outcome of a {@link #check}. */
    public enum Status { INSTALLED, UP_TO_DATE, FAILED }

    /** Why a check FAILED. Codes only: safe for audit records (no URL, host or file name). */
    public enum Reason {
        /** Not HTTPS, and not an allowed HTTP to localhost (also for a redirect). */
        INSECURE_TRANSPORT,
        /** Connection refused, timeout or another I/O error while talking to the server. */
        NETWORK,
        /** An answer other than 200, or a redirect without a target or after too many hops. */
        HTTP_STATUS,
        /** The index is larger than 16 KiB, malformed, has unknown fields or invalid values. */
        INDEX_INVALID,
        /** zipSize is over 64 MiB, or the server sent a different number of bytes. */
        ZIP_SIZE,
        /** The zip's SHA-256 differs from the index. */
        ZIP_HASH_MISMATCH,
        /** Not a readable zip, no manifest first, a different version than the index, or missing entries. */
        ZIP_INVALID,
        /** A directory, a path, an entry the manifest does not name, a duplicate, or an entry over its size. */
        ZIP_ENTRY_REFUSED,
        /** The install's verification failed: signature, manifest or file hashes (MODEL_INTEGRITY_FAILED). */
        BUNDLE_INVALID,
        /** A local problem: the store's pointer, the app's own manifest, or the disk. */
        STORAGE
    }

    /** The result of one check. */
    public static final class UpdateResult {
        private final Status status;
        private final String version;
        private final Reason reason;
        private final String detail;

        private UpdateResult(Status status, String version, Reason reason, String detail) {
            this.status = status;
            this.version = version;
            this.reason = reason;
            this.detail = detail;
        }

        public Status status() { return status; }

        /** INSTALLED: the installed version. UP_TO_DATE: the newest local version. FAILED: the offered one, or null. */
        public String version() { return version; }

        /** FAILED only; null otherwise. */
        public Reason reason() { return reason; }

        /** For logs (not audit): what failed, without URLs. Empty unless FAILED. */
        public String detail() { return detail; }

        @Override
        public String toString() {
            return status + (version == null ? "" : " " + version) + (reason == null ? "" : " " + reason)
                    + (detail.isEmpty() ? "" : " (" + detail + ")");
        }
    }

    /** Options; the defaults suit production. */
    public static final class Builder {
        private int connectTimeoutMs = 15000;
        private int readTimeoutMs = 30000;
        private boolean allowInsecureLocalhost;

        public Builder connectTimeoutMs(int ms) {
            if (ms <= 0) throw new IllegalArgumentException("connect timeout must be positive");
            connectTimeoutMs = ms;
            return this;
        }

        public Builder readTimeoutMs(int ms) {
            if (ms <= 0) throw new IllegalArgumentException("read timeout must be positive");
            readTimeoutMs = ms;
            return this;
        }

        /** Allows plain HTTP to 127.0.0.1 and localhost. Tests and debug builds only; never in production. */
        public Builder allowInsecureLocalhost(boolean allow) {
            allowInsecureLocalhost = allow;
            return this;
        }

        public ModelUpdater build() {
            return new ModelUpdater(this);
        }
    }

    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final boolean allowInsecureLocalhost;
    private final SecureRandom random = new SecureRandom();

    private ModelUpdater(Builder b) {
        this.connectTimeoutMs = b.connectTimeoutMs;
        this.readTimeoutMs = b.readTimeoutMs;
        this.allowInsecureLocalhost = b.allowInsecureLocalhost;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Checks {@code indexUrl} and installs a newer bundle. Never throws for network, server or bundle problems: those
     * are FAILED results, and the store stays as it was.
     *
     * @param bundled the app's own bundle: the version to beat, and the rollback base of the install
     */
    public UpdateResult check(URL indexUrl, ModelStore store, BundleSource bundled, TrustedKeys keys) {
        if (indexUrl == null || store == null || bundled == null || keys == null) {
            throw new IllegalArgumentException("indexUrl, store, bundled and keys are required");
        }
        synchronized (CHECK_LOCK) {
            String offered = null;
            File work = null;
            try {
                Fetched fetched = fetch(indexUrl, MAX_INDEX_BYTES);
                Index index = Index.parse(fetched.body);
                offered = index.version;
                String local = newestLocal(store, bundled);
                if (VerifiedBundle.compareVersions(index.version, local) <= 0) {
                    return new UpdateResult(Status.UP_TO_DATE, local, null, "");
                }
                URL zipUrl = resolve(fetched.url, index.zipUrl); // relative to where the index finally came from
                work = newWorkDir(store);
                File zip = new File(work, "bundle.zip");
                download(zipUrl, index.zipSize, index.zipSha256, zip);
                File staged = new File(work, "bundle");
                if (!staged.mkdir()) throw new Failure(Reason.STORAGE, "cannot create the staging directory");
                unpack(zip, staged, index.version);
                try {
                    String v = store.install(new DirectoryBundleSource(staged), bundled, keys);
                    return new UpdateResult(Status.INSTALLED, v, null, "");
                } catch (IllegalArgumentException notNewer) {
                    // Another check installed it (or a newer one) meanwhile.
                    return new UpdateResult(Status.UP_TO_DATE, newestLocal(store, bundled), null, "");
                }
            } catch (Failure f) {
                return new UpdateResult(Status.FAILED, offered, f.reason, f.getMessage());
            } catch (LivenessEngineException e) {
                return new UpdateResult(Status.FAILED, offered, Reason.BUNDLE_INVALID, e.getMessage());
            } catch (IOException e) {
                return new UpdateResult(Status.FAILED, offered, Reason.STORAGE, e.getClass().getSimpleName());
            } finally {
                if (work != null) ModelStore.deleteRecursively(work);
            }
        }
    }

    /**
     * Installs a bundle zip from local or removable media (F17), the zip {@link #check} downloads. The zip is not
     * trusted: it must be at most 64 MiB, it is unpacked with the same rules into a fresh {@code update.tmp-}
     * directory (deleted afterwards), and it is installed only when its signature verifies and its version, read from
     * its own manifest, is newer than both the store's current bundle and the app's own. The signature, sizes and
     * hashes are checked before the version ({@link ModelStore#install}), so a tampered zip is BUNDLE_INVALID even
     * when the version it claims is not newer. Never throws for a bad zip or bundle.
     *
     * @return INSTALLED with the new version; UP_TO_DATE with the local version when a verified zip is not newer
     *         (refused, the store unchanged); FAILED with a {@link Reason} (BUNDLE_INVALID = MODEL_INTEGRITY_FAILED),
     *         the store unchanged
     */
    public UpdateResult installZip(File zip, ModelStore store, BundleSource bundled, TrustedKeys keys) {
        if (zip == null || store == null || bundled == null || keys == null) {
            throw new IllegalArgumentException("zip, store, bundled and keys are required");
        }
        synchronized (CHECK_LOCK) {
            String offered = null;
            File work = null;
            try {
                if (!zip.isFile()) throw new Failure(Reason.STORAGE, "the zip is not a readable file");
                if (zip.length() > MAX_ZIP_BYTES) throw new Failure(Reason.ZIP_SIZE, "the zip is larger than 64 MiB");
                work = newWorkDir(store);
                File staged = new File(work, "bundle");
                if (!staged.mkdir()) throw new Failure(Reason.STORAGE, "cannot create the staging directory");
                offered = unpack(zip, staged, null);
                // No version pre-check: the manifest is not verified yet. ModelStore.install verifies the signature,
                // sizes and hashes first and only then refuses a version that is not newer, so a tampered zip is
                // always BUNDLE_INVALID, whatever version it claims (verify, then trust).
                try {
                    String v = store.install(new DirectoryBundleSource(staged), bundled, keys);
                    return new UpdateResult(Status.INSTALLED, v, null, "");
                } catch (IllegalArgumentException notNewer) {
                    return new UpdateResult(Status.UP_TO_DATE, newestLocal(store, bundled), null, "");
                }
            } catch (Failure f) {
                return new UpdateResult(Status.FAILED, offered, f.reason, f.getMessage());
            } catch (LivenessEngineException e) {
                return new UpdateResult(Status.FAILED, offered, Reason.BUNDLE_INVALID, e.getMessage());
            } catch (IOException e) {
                return new UpdateResult(Status.FAILED, offered, Reason.STORAGE, e.getClass().getSimpleName());
            } finally {
                if (work != null) ModelStore.deleteRecursively(work);
            }
        }
    }

    /**
     * The version new sessions load: the newer of the store's current bundle and the app's own (read, not verified:
     * the factory verifies), or null when either cannot be read. For status displays and audits.
     */
    public static String activeVersion(ModelStore store, BundleSource bundled) {
        try {
            return newestLocal(store, bundled);
        } catch (Failure f) {
            return null;
        }
    }

    // ---- packing (ModelBundleTool pack and tests) ----

    /** The index for a packed bundle, as {@link #check} reads it. */
    public static String indexJson(String version, String zipUrl, String zipSha256, long zipSize) {
        if (!VerifiedBundle.isValidVersion(version)) throw new IllegalArgumentException("invalid version " + version);
        if (!SHA256.matcher(zipSha256).matches()) throw new IllegalArgumentException("zipSha256 must be 64 hex digits");
        if (zipSize <= 0 || zipSize > MAX_ZIP_BYTES) throw new IllegalArgumentException("zipSize out of range");
        return "{\n"
                + "  \"schema\": " + INDEX_SCHEMA + ",\n"
                + "  \"bundleVersion\": \"" + version + "\",\n"
                + "  \"zipUrl\": " + jsonString(zipUrl) + ",\n"
                + "  \"zipSha256\": \"" + zipSha256 + "\",\n"
                + "  \"zipSize\": " + zipSize + "\n"
                + "}\n";
    }

    /** Writes a verified bundle as the zip {@link #check} accepts: manifest.json first, then manifest.sig and the files. */
    public static void writeZip(VerifiedBundle bundle, OutputStream out) throws IOException {
        ZipOutputStream z = new ZipOutputStream(out);
        putEntry(z, VerifiedBundle.MANIFEST, bundle.manifestBytes());
        putEntry(z, VerifiedBundle.SIGNATURE, bundle.signatureBytes());
        for (ModelRole role : ModelRole.values()) putEntry(z, bundle.fileName(role), bundle.modelBytes().get(role));
        z.finish();
    }

    private static void putEntry(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    // ---- steps ----

    /** The newer of the store's current version and the app's own bundle version (read, not verified: install verifies). */
    private static String newestLocal(ModelStore store, BundleSource bundled) throws Failure {
        String own;
        try {
            Map<String, Object> m = MiniJson.parseObject(
                    new String(bundled.read(VerifiedBundle.MANIFEST), StandardCharsets.UTF_8));
            own = MiniJson.getString(m, "bundleVersion");
        } catch (IOException e) {
            throw new Failure(Reason.STORAGE, "the app's own manifest is unreadable");
        } catch (JsonException e) {
            throw new Failure(Reason.STORAGE, "the app's own manifest is invalid");
        }
        if (!VerifiedBundle.isValidVersion(own)) throw new Failure(Reason.STORAGE, "the app's own version is invalid");
        String current;
        try {
            current = store.currentVersion();
        } catch (LivenessEngineException e) {
            throw new Failure(Reason.STORAGE, "the store's current-version pointer is corrupt");
        }
        return current != null && VerifiedBundle.compareVersions(current, own) > 0 ? current : own;
    }

    private File newWorkDir(ModelStore store) throws Failure {
        File root = store.root();
        if (!root.isDirectory() && !root.mkdirs()) throw new Failure(Reason.STORAGE, "cannot create the store directory");
        File[] stale = root.listFiles();
        if (stale != null) {
            for (File f : stale) {
                if (f.getName().startsWith(TMP_PREFIX)) ModelStore.deleteRecursively(f); // left by a crash
            }
        }
        byte[] b = new byte[6];
        random.nextBytes(b);
        File dir = new File(root, TMP_PREFIX + Hex.encode(b));
        if (!dir.mkdir()) throw new Failure(Reason.STORAGE, "cannot create the download directory");
        return dir;
    }

    /** A small body and the URL it came from after redirects. */
    private static final class Fetched {
        final byte[] body;
        final URL url;

        Fetched(byte[] body, URL url) {
            this.body = body;
            this.url = url;
        }
    }

    private Fetched fetch(URL url, int maxBytes) throws Failure {
        HttpURLConnection c = open(url);
        try {
            if (c.getContentLength() > maxBytes) throw new Failure(Reason.INDEX_INVALID, "index larger than " + maxBytes + " bytes");
            InputStream in = c.getInputStream();
            try {
                byte[] buf = new byte[4096];
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() + n > maxBytes) throw new Failure(Reason.INDEX_INVALID, "index larger than " + maxBytes + " bytes");
                    out.write(buf, 0, n);
                }
                return new Fetched(out.toByteArray(), c.getURL());
            } finally {
                in.close();
            }
        } catch (IOException e) {
            throw new Failure(Reason.NETWORK, "index: " + e.getClass().getSimpleName());
        } finally {
            c.disconnect();
        }
    }

    private void download(URL url, long size, String sha256, File target) throws Failure {
        HttpURLConnection c = open(url);
        try {
            int declared = c.getContentLength();
            if (declared >= 0 && declared != size) {
                throw new Failure(Reason.ZIP_SIZE, "Content-Length " + declared + " but zipSize " + size);
            }
            MessageDigest md = sha256();
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(target);
            long total = 0;
            try {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > size) throw new Failure(Reason.ZIP_SIZE, "more than zipSize (" + size + ") bytes");
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
                in.close();
            }
            if (total != size) throw new Failure(Reason.ZIP_SIZE, "got " + total + " of " + size + " bytes");
            if (!Hex.encode(md.digest()).equals(sha256)) throw new Failure(Reason.ZIP_HASH_MISMATCH, "zip SHA-256 differs from the index");
        } catch (IOException e) {
            throw new Failure(Reason.NETWORK, "zip: " + e.getClass().getSimpleName());
        } finally {
            c.disconnect();
        }
    }

    /**
     * Unpacks flat entries into {@code dir}: manifest.json first (it names the other entries and their sizes; it is
     * not trusted yet, the install verifies it), then exactly manifest.sig and the named files, each once.
     *
     * @param expectedVersion the version the manifest must declare, or null to accept any valid version
     * @return the manifest's bundle version
     */
    static String unpack(File zip, File dir, String expectedVersion) throws Failure {
        ZipInputStream z;
        try {
            z = new ZipInputStream(new FileInputStream(zip));
        } catch (IOException e) {
            throw new Failure(Reason.STORAGE, "cannot read the downloaded zip");
        }
        try {
            ZipEntry first = z.getNextEntry();
            if (first == null || first.isDirectory() || !VerifiedBundle.MANIFEST.equals(first.getName())) {
                throw new Failure(Reason.ZIP_INVALID, "the first entry is not " + VerifiedBundle.MANIFEST);
            }
            byte[] manifest = readEntry(z, VerifiedBundle.MAX_MANIFEST_BYTES, VerifiedBundle.MANIFEST);
            String[] version = new String[1];
            Map<String, Long> allowed = allowedEntries(manifest, expectedVersion, version);
            write(new File(dir, VerifiedBundle.MANIFEST), manifest);
            Set<String> seen = new HashSet<String>();
            seen.add(VerifiedBundle.MANIFEST);
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory() || !allowed.containsKey(name)) {
                    throw new Failure(Reason.ZIP_ENTRY_REFUSED, "entry not named by the manifest: " + printable(name));
                }
                if (!seen.add(name)) throw new Failure(Reason.ZIP_ENTRY_REFUSED, "duplicate entry " + printable(name));
                write(new File(dir, name), readEntry(z, allowed.get(name), name));
            }
            if (seen.size() != allowed.size()) {
                throw new Failure(Reason.ZIP_INVALID, "the zip lacks " + (allowed.size() - seen.size()) + " named entries");
            }
            return version[0];
        } catch (IOException e) {
            throw new Failure(Reason.ZIP_INVALID, "unreadable zip: " + e.getClass().getSimpleName());
        } finally {
            try {
                z.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    /**
     * manifest.json, manifest.sig and the files the manifest names, with their maximum sizes; the manifest's version
     * goes to {@code versionOut[0]}.
     */
    private static Map<String, Long> allowedEntries(byte[] manifest, String expectedVersion, String[] versionOut)
            throws Failure {
        Map<String, Long> allowed = new LinkedHashMap<String, Long>();
        allowed.put(VerifiedBundle.MANIFEST, (long) VerifiedBundle.MAX_MANIFEST_BYTES);
        allowed.put(VerifiedBundle.SIGNATURE, (long) VerifiedBundle.MAX_SIGNATURE_BYTES);
        long total = 0;
        try {
            Map<String, Object> m = MiniJson.parseObject(new String(manifest, StandardCharsets.UTF_8));
            String version = MiniJson.getString(m, "bundleVersion");
            if (expectedVersion != null ? !expectedVersion.equals(version) : !VerifiedBundle.isValidVersion(version)) {
                throw new Failure(Reason.ZIP_INVALID, expectedVersion != null ? "the bundle's version differs from the index"
                        : "the bundle's version is invalid");
            }
            versionOut[0] = version;
            List<Object> files = MiniJson.getArray(m, "files");
            if (files.size() > MAX_MANIFEST_FILES) {
                throw new Failure(Reason.ZIP_INVALID, "the manifest names more than " + MAX_MANIFEST_FILES + " files");
            }
            for (Object o : files) {
                if (!(o instanceof Map)) throw new Failure(Reason.ZIP_INVALID, "a manifest file entry is not an object");
                @SuppressWarnings("unchecked")
                Map<String, Object> f = (Map<String, Object>) o;
                String name = MiniJson.getString(f, "name");
                long size = MiniJson.getLong(f, "size");
                if (!NAME.matcher(name).matches() || name.contains("..") || allowed.containsKey(name)) {
                    throw new Failure(Reason.ZIP_INVALID, "invalid file name in the manifest: " + printable(name));
                }
                if (size <= 0 || size > BundleSource.MAX_FILE_BYTES) throw new Failure(Reason.ZIP_INVALID, "invalid size in the manifest");
                total += size;
                allowed.put(name, size);
            }
        } catch (JsonException e) {
            throw new Failure(Reason.ZIP_INVALID, "invalid manifest: " + e.getMessage());
        }
        if (total > MAX_UNPACKED_BYTES) throw new Failure(Reason.ZIP_INVALID, "the manifest's files add up to more than 128 MiB");
        return allowed;
    }

    private static byte[] readEntry(ZipInputStream z, long maxBytes, String name) throws IOException, Failure {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = z.read(buf)) != -1) {
            if (out.size() + (long) n > maxBytes) {
                throw new Failure(Reason.ZIP_ENTRY_REFUSED, printable(name) + " is larger than its declared size");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void write(File f, byte[] data) throws Failure {
        try {
            ModelStore.writeSynced(f, data);
        } catch (IOException e) {
            throw new Failure(Reason.STORAGE, "cannot write a staged file");
        }
    }

    // ---- transport ----

    /** Opens a 200 answer, following up to {@link #MAX_REDIRECTS} redirects, each under the transport rule. */
    private HttpURLConnection open(URL url) throws Failure {
        URL current = url;
        for (int hop = 0; ; hop++) {
            checkTransport(current);
            HttpURLConnection c;
            int code;
            try {
                c = (HttpURLConnection) current.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(connectTimeoutMs);
                c.setReadTimeout(readTimeoutMs);
                c.setUseCaches(false);
                c.setRequestProperty("Accept-Encoding", "identity"); // sizes and hashes are of the bytes as served
                code = c.getResponseCode();
            } catch (IOException e) {
                throw new Failure(Reason.NETWORK, e.getClass().getSimpleName());
            }
            if (code == HttpURLConnection.HTTP_OK) return c;
            String location = c.getHeaderField("Location");
            c.disconnect();
            boolean redirect = code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
            if (!redirect) throw new Failure(Reason.HTTP_STATUS, "HTTP " + code);
            if (location == null || hop >= MAX_REDIRECTS) {
                throw new Failure(Reason.HTTP_STATUS, "HTTP " + code + (location == null ? " without a Location" : " after too many redirects"));
            }
            current = resolve(current, location);
        }
    }

    void checkTransport(URL url) throws Failure {
        String protocol = url.getProtocol().toLowerCase(java.util.Locale.ROOT);
        if (protocol.equals("https")) return;
        if (protocol.equals("http") && allowInsecureLocalhost && isLocalhost(url.getHost())) return;
        throw new Failure(Reason.INSECURE_TRANSPORT, protocol.equals("http")
                ? "plain HTTP is allowed only to localhost, with allowInsecureLocalhost" : "unsupported protocol " + printable(protocol));
    }

    static boolean isLocalhost(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
    }

    private static URL resolve(URL base, String spec) throws Failure {
        try {
            return new URL(base, spec);
        } catch (IOException e) {
            throw new Failure(Reason.INDEX_INVALID, "an invalid URL");
        }
    }

    // ---- helpers ----

    /** The parsed index. */
    static final class Index {
        final String version;
        final String zipUrl;
        final String zipSha256;
        final long zipSize;

        private Index(String version, String zipUrl, String zipSha256, long zipSize) {
            this.version = version;
            this.zipUrl = zipUrl;
            this.zipSha256 = zipSha256;
            this.zipSize = zipSize;
        }

        static Index parse(byte[] json) throws Failure {
            try {
                Map<String, Object> o = MiniJson.parseObject(new String(json, StandardCharsets.UTF_8));
                for (String k : o.keySet()) {
                    if (!INDEX_KEYS.contains(k)) throw new Failure(Reason.INDEX_INVALID, "unknown index field " + printable(k));
                }
                if (MiniJson.getInt(o, "schema") != INDEX_SCHEMA) throw new Failure(Reason.INDEX_INVALID, "unsupported index schema");
                String version = MiniJson.getString(o, "bundleVersion");
                if (!VerifiedBundle.isValidVersion(version)) throw new Failure(Reason.INDEX_INVALID, "invalid bundleVersion");
                String zipUrl = MiniJson.getString(o, "zipUrl");
                if (zipUrl.isEmpty() || zipUrl.length() > 2048) throw new Failure(Reason.INDEX_INVALID, "invalid zipUrl");
                String sha = MiniJson.getString(o, "zipSha256");
                if (!SHA256.matcher(sha).matches()) throw new Failure(Reason.INDEX_INVALID, "invalid zipSha256");
                long size = MiniJson.getLong(o, "zipSize");
                if (size <= 0) throw new Failure(Reason.INDEX_INVALID, "invalid zipSize");
                if (size > MAX_ZIP_BYTES) throw new Failure(Reason.ZIP_SIZE, "zipSize over the 64 MiB limit");
                return new Index(version, zipUrl, sha, size);
            } catch (JsonException e) {
                throw new Failure(Reason.INDEX_INVALID, "invalid index: " + e.getMessage());
            }
        }
    }

    /** A failed step; becomes a FAILED result. */
    static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        final Reason reason;

        Failure(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Untrusted text for a log line: printable ASCII only, at most 64 characters. */
    static String printable(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 64; i++) {
            char c = s.charAt(i);
            b.append(c >= 0x20 && c < 0x7f ? c : '?');
        }
        return b.toString();
    }

    private static String jsonString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c < 0x20) {
                b.append(String.format("\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
