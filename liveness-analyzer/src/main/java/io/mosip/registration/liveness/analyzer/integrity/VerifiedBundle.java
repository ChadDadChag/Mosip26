package io.mosip.registration.liveness.analyzer.integrity;

import io.mosip.registration.liveness.analyzer.integrity.MiniJson.JsonException;
import io.mosip.registration.liveness.analyzer.runtime.ModelRole;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.LivenessEngineException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A model bundle whose signature, manifest and files have all been verified. The ONLY way the analyzer obtains
 * model bytes: sessions are built from {@link #modelBytes()}, the same arrays that were hashed here, so nothing is
 * re-read between check and use.
 *
 * <p>Order (fail closed, every failure is {@link ErrorCode#MODEL_INTEGRITY_FAILED}):
 * <ol>
 *   <li>read manifest.json (at most 64 KiB) and manifest.sig (at most 1 KiB, one line {@code <keyId>:<hex DER>});</li>
 *   <li>look up keyId in {@link TrustedKeys}; verify SHA256withECDSA over the EXACT manifest bytes;</li>
 *   <li>only then parse the manifest (schema 1, strict) and check it: version {@code pad-YYYY.MM.N}, keyId equal to
 *       the signature's, exactly the required {@link ModelRole}s once each, safe file names, finite numeric params;</li>
 *   <li>for each file: read it (at most 64 MiB), check its size, then its SHA-256.</li>
 * </ol>
 * Messages name the problem and never contain key material.
 */
public final class VerifiedBundle {

    public static final String MANIFEST = "manifest.json";
    public static final String SIGNATURE = "manifest.sig";
    public static final int MAX_MANIFEST_BYTES = 64 * 1024;
    public static final int MAX_SIGNATURE_BYTES = 1024;

    private static final Pattern VERSION = Pattern.compile("^pad-(\\d{4})\\.(\\d{2})\\.(\\d+)$");
    // \z, not $: $ would also accept a second trailing newline.
    private static final Pattern SIG_LINE = Pattern.compile("^([A-Za-z0-9._-]{1,64}):([0-9a-f]{16,512})\n?\\z");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> TOP_KEYS = new HashSet<String>(Arrays.asList(
            "schema", "bundleVersion", "keyId", "createdUtc", "params", "files"));
    private static final Set<String> FILE_KEYS = new HashSet<String>(Arrays.asList("role", "name", "size", "sha256"));

    private final String version;
    private final String keyId;
    private final Map<ModelRole, byte[]> models;
    private final Map<ModelRole, String> names;
    private final Map<ModelRole, String> sha256;
    private final Map<String, Double> params;
    private final byte[] manifestBytes;
    private final byte[] signatureBytes;

    private VerifiedBundle(String version, String keyId, Map<ModelRole, byte[]> models, Map<ModelRole, String> names,
                           Map<ModelRole, String> sha256, Map<String, Double> params, byte[] manifestBytes,
                           byte[] signatureBytes) {
        this.version = version;
        this.keyId = keyId;
        this.models = Collections.unmodifiableMap(models);
        this.names = Collections.unmodifiableMap(names);
        this.sha256 = Collections.unmodifiableMap(sha256);
        this.params = Collections.unmodifiableMap(params);
        this.manifestBytes = manifestBytes;
        this.signatureBytes = signatureBytes;
    }

    public static VerifiedBundle load(BundleSource src, TrustedKeys keys) throws LivenessEngineException {
        if (src == null || keys == null) throw fail("no bundle source or trusted keys");
        String where = src.describe();

        // 1. Raw bytes.
        byte[] manifest = readFile(src, MANIFEST, where);
        if (manifest.length > MAX_MANIFEST_BYTES) throw fail(MANIFEST + " is larger than 64 KiB in " + where);
        byte[] sig = readFile(src, SIGNATURE, where);
        if (sig.length > MAX_SIGNATURE_BYTES) throw fail(SIGNATURE + " is larger than 1 KiB in " + where);

        // 2. Signature over the exact manifest bytes, before any parsing.
        Matcher m = SIG_LINE.matcher(new String(sig, StandardCharsets.US_ASCII));
        if (!m.matches()) throw fail(SIGNATURE + " is malformed in " + where);
        String sigKeyId = m.group(1);
        PublicKey key = keys.get(sigKeyId);
        if (key == null) throw fail("unknown signing keyId '" + sigKeyId + "' in " + where);
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(manifest);
            if (!verifier.verify(Hex.decode(m.group(2)))) throw fail("bad manifest signature in " + where);
        } catch (GeneralSecurityException e) {
            throw fail("bad manifest signature in " + where);
        } catch (IllegalArgumentException e) {
            throw fail("bad manifest signature in " + where);
        }

        // 3. Strict parse and checks.
        Map<String, Object> json;
        try {
            json = MiniJson.parseObject(new String(manifest, StandardCharsets.UTF_8));
        } catch (JsonException e) {
            throw fail("invalid " + MANIFEST + " in " + where + ": " + e.getMessage());
        }
        String version;
        String keyId;
        Map<String, Double> params = new LinkedHashMap<String, Double>();
        Map<ModelRole, String> names = new EnumMap<ModelRole, String>(ModelRole.class);
        Map<ModelRole, Long> sizes = new EnumMap<ModelRole, Long>(ModelRole.class);
        Map<ModelRole, String> hashes = new EnumMap<ModelRole, String>(ModelRole.class);
        try {
            for (String k : json.keySet()) {
                if (!TOP_KEYS.contains(k)) throw fail("unknown manifest field '" + k + "' in " + where);
            }
            if (MiniJson.getInt(json, "schema") != 1) throw fail("unsupported manifest schema in " + where);
            version = MiniJson.getString(json, "bundleVersion");
            if (!VERSION.matcher(version).matches()) throw fail("invalid bundleVersion '" + version + "' in " + where);
            keyId = MiniJson.getString(json, "keyId");
            if (!keyId.equals(sigKeyId)) throw fail("manifest keyId differs from the signature's keyId in " + where);
            MiniJson.getString(json, "createdUtc");
            Map<String, Object> p = MiniJson.getObject(json, "params");
            for (Map.Entry<String, Object> e : p.entrySet()) {
                if (!(e.getValue() instanceof Double)) throw fail("param '" + e.getKey() + "' is not a number in " + where);
                params.put(e.getKey(), (Double) e.getValue()); // MiniJson never yields NaN or infinity
            }
            List<Object> files = MiniJson.getArray(json, "files");
            if (files.size() != ModelRole.values().length) {
                throw fail("manifest files are not exactly the required roles (" + ModelRole.values().length
                        + ") in " + where);
            }
            Set<String> seenNames = new HashSet<String>();
            for (Object o : files) {
                if (!(o instanceof Map)) throw fail("manifest file entry is not an object in " + where);
                @SuppressWarnings("unchecked")
                Map<String, Object> f = (Map<String, Object>) o;
                for (String k : f.keySet()) {
                    if (!FILE_KEYS.contains(k)) throw fail("unknown file field '" + k + "' in " + where);
                }
                ModelRole role = role(MiniJson.getString(f, "role"), where);
                if (names.containsKey(role)) throw fail("role " + role + " listed twice in " + where);
                String name = MiniJson.getString(f, "name");
                if (!NAME.matcher(name).matches() || name.contains("..") || !seenNames.add(name)) {
                    throw fail("invalid or repeated file name '" + name + "' in " + where);
                }
                long size = MiniJson.getLong(f, "size");
                if (size <= 0 || size > BundleSource.MAX_FILE_BYTES) throw fail("invalid size for " + name + " in " + where);
                String hash = MiniJson.getString(f, "sha256");
                if (!SHA256.matcher(hash).matches()) throw fail("invalid sha256 for " + name + " in " + where);
                names.put(role, name);
                sizes.put(role, size);
                hashes.put(role, hash);
            }
        } catch (JsonException e) {
            throw fail("invalid " + MANIFEST + " in " + where + ": " + e.getMessage());
        }

        // 4. The files: size, then SHA-256.
        Map<ModelRole, byte[]> models = new EnumMap<ModelRole, byte[]>(ModelRole.class);
        for (ModelRole role : ModelRole.values()) {
            String name = names.get(role);
            byte[] bytes = readFile(src, name, where);
            if (bytes.length != sizes.get(role)) throw fail("size mismatch for " + name + " in " + where);
            if (!sha256Hex(bytes).equals(hashes.get(role))) throw fail("sha256 mismatch for " + name + " in " + where);
            models.put(role, bytes);
        }
        return new VerifiedBundle(version, keyId, models, names, hashes, params, manifest, sig);
    }

    /** Compares two bundle versions by (year, month, n). Throws IllegalArgumentException for an invalid version. */
    public static int compareVersions(String a, String b) {
        long[] x = parseVersion(a), y = parseVersion(b);
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) return x[i] < y[i] ? -1 : 1;
        }
        return 0;
    }

    public static boolean isValidVersion(String v) {
        return v != null && VERSION.matcher(v).matches();
    }

    private static long[] parseVersion(String v) {
        Matcher m = v == null ? null : VERSION.matcher(v);
        if (m == null || !m.matches()) throw new IllegalArgumentException("invalid bundle version: " + v);
        try {
            return new long[] {Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), Long.parseLong(m.group(3))};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid bundle version: " + v);
        }
    }

    private static ModelRole role(String s, String where) throws LivenessEngineException {
        for (ModelRole r : ModelRole.values()) {
            if (r.name().equals(s)) return r;
        }
        throw fail("unknown role '" + s + "' in " + where);
    }

    private static byte[] readFile(BundleSource src, String name, String where) throws LivenessEngineException {
        try {
            return src.read(name);
        } catch (IOException e) {
            throw fail("missing or unreadable " + name + " in " + where + " (" + e.getMessage() + ")");
        }
    }

    static String sha256Hex(byte[] data) {
        try {
            return Hex.encode(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static LivenessEngineException fail(String message) {
        return new LivenessEngineException(ErrorCode.MODEL_INTEGRITY_FAILED, message);
    }

    public String version() { return version; }

    public String keyId() { return keyId; }

    /** The verified model bytes by role. Shared, not copied: do not modify. */
    public Map<ModelRole, byte[]> modelBytes() { return models; }

    public String fileName(ModelRole role) { return names.get(role); }

    public Map<ModelRole, String> sha256() { return sha256; }

    /** Model-coupled calibration from the signed manifest, e.g. "pitchBiasDeg". */
    public Map<String, Double> params() { return params; }

    /** The exact verified manifest.json bytes. Shared, not copied: do not modify. */
    byte[] manifestBytes() { return manifestBytes; }

    /** The exact verified manifest.sig bytes. Shared, not copied: do not modify. */
    byte[] signatureBytes() { return signatureBytes; }
}
