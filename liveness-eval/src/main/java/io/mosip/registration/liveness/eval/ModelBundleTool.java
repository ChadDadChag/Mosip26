package io.mosip.registration.liveness.eval;

import io.mosip.registration.liveness.analyzer.integrity.DirectoryBundleSource;
import io.mosip.registration.liveness.analyzer.integrity.Hex;
import io.mosip.registration.liveness.analyzer.integrity.MiniJson;
import io.mosip.registration.liveness.analyzer.integrity.ModelStore;
import io.mosip.registration.liveness.analyzer.integrity.TrustedKeys;
import io.mosip.registration.liveness.analyzer.integrity.VerifiedBundle;
import io.mosip.registration.liveness.analyzer.runtime.ModelRole;
import io.mosip.registration.liveness.api.LivenessEngineException;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Model bundle tooling (development machines only; never ships).
 * <pre>
 *   keygen   --key-id &lt;id&gt; [--out &lt;p8&gt;] [--force]
 *   manifest --dir &lt;models&gt; --version &lt;pad-YYYY.MM.N&gt; --key-id &lt;id&gt; [--param k=v ...] [--created &lt;ISO-UTC&gt;]
 *   sign     --dir &lt;models&gt; --key &lt;p8&gt; --key-id &lt;id&gt;
 *   verify   --dir &lt;dir&gt;                      [--trust &lt;keyId&gt;=&lt;public hex&gt;]
 *   install  --store &lt;dir&gt; --from &lt;dir&gt;     [--trust &lt;keyId&gt;=&lt;public hex&gt;]
 *   rollback --store &lt;dir&gt;                    [--trust &lt;keyId&gt;=&lt;public hex&gt;]
 * </pre>
 * The private key is written only outside any git working tree (default
 * {@code ~/.mosip-liveness/model-signing-key.p8}). verify/install/rollback use the production loader with the keys
 * compiled into {@link TrustedKeys}; {@code --trust} replaces them (tests and CI only).
 * Exit codes: 0 success, 1 the operation or verification failed, 2 usage error.
 */
public final class ModelBundleTool {

    static final String DEFAULT_KEY = ".mosip-liveness" + File.separator + "model-signing-key.p8";
    private static final Pattern KEY_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Pattern PARAM_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");

    private final PrintStream out, err;

    private ModelBundleTool(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Runs one command; returns the exit code. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        ModelBundleTool tool = new ModelBundleTool(out, err);
        if (args.length == 0) return tool.usage("no command");
        Args a;
        try {
            a = Args.parse(args, 1);
        } catch (IllegalArgumentException e) {
            return tool.usage(e.getMessage());
        }
        try {
            switch (args[0]) {
                case "keygen": return tool.keygen(a);
                case "manifest": return tool.manifest(a);
                case "sign": return tool.sign(a);
                case "verify": return tool.verify(a);
                case "install": return tool.install(a);
                case "rollback": return tool.rollback(a);
                default: return tool.usage("unknown command '" + args[0] + "'");
            }
        } catch (UsageException e) {
            return tool.usage(e.getMessage());
        } catch (LivenessEngineException e) {
            err.println("FAILED (" + e.code() + "): " + e.getMessage());
            return 1;
        } catch (Exception e) {
            err.println("FAILED: " + e);
            return 1;
        }
    }

    private int usage(String problem) {
        err.println("error: " + problem);
        err.println("usage: ModelBundleTool <command> [options]");
        err.println("  keygen   --key-id <id> [--out <p8>] [--force]");
        err.println("  manifest --dir <models> --version <pad-YYYY.MM.N> --key-id <id> [--param k=v ...] [--created <ISO-UTC>]");
        err.println("  sign     --dir <models> --key <p8> --key-id <id>");
        err.println("  verify   --dir <dir> [--trust <keyId>=<public hex>]");
        err.println("  install  --store <dir> --from <dir> [--trust <keyId>=<public hex>]");
        err.println("  rollback --store <dir> [--trust <keyId>=<public hex>]");
        return 2;
    }

    // ---- keygen ----

    private int keygen(Args a) throws Exception {
        String keyId = keyId(a.required("--key-id"));
        File target = a.has("--out") ? new File(a.single("--out"))
                : new File(System.getProperty("user.home"), DEFAULT_KEY);
        target = target.getAbsoluteFile();
        File repo = enclosingGitWorkTree(target);
        if (repo != null) {
            err.println("REFUSED: " + target + " is inside the git working tree " + repo
                    + "; the private key must never enter a repository.");
            return 1;
        }
        if (target.exists() && !a.has("--force")) {
            err.println("REFUSED: " + target + " exists; pass --force to replace it.");
            return 1;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        Files.write(target.toPath(), kp.getPrivate().getEncoded(), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        ownerOnly(target);
        String pub = Hex.encode(kp.getPublic().getEncoded());
        out.println("keyId: " + keyId);
        out.println("public key (X.509 SubjectPublicKeyInfo, hex): " + pub);
        out.println("private key (PKCS#8 DER) written to: " + target);
        out.println("Add to TrustedKeys.BUILT_IN: {\"" + keyId + "\", \"" + pub + "\"}");
        return 0;
    }

    /** The nearest ancestor (or the path itself) that contains .git, or null. */
    static File enclosingGitWorkTree(File path) throws IOException {
        File f = path.getAbsoluteFile();
        try {
            f = f.getCanonicalFile();
        } catch (IOException e) {
            // keep the absolute path
        }
        for (File d = f; d != null; d = d.getParentFile()) {
            if (new File(d, ".git").exists()) return d;
        }
        return null;
    }

    private static void ownerOnly(File f) {
        f.setReadable(false, false);
        f.setWritable(false, false);
        f.setReadable(true, true);
        f.setWritable(true, true);
    }

    // ---- manifest ----

    private int manifest(Args a) throws Exception {
        File dir = existingDir(a.required("--dir"));
        String version = a.required("--version");
        if (!VerifiedBundle.isValidVersion(version)) throw new UsageException("--version must look like pad-YYYY.MM.N");
        String keyId = keyId(a.required("--key-id"));
        Map<String, Double> params = new LinkedHashMap<String, Double>();
        for (String p : a.all("--param")) {
            int eq = p.indexOf('=');
            if (eq <= 0) throw new UsageException("--param must be name=number: " + p);
            String name = p.substring(0, eq);
            if (!PARAM_NAME.matcher(name).matches()) throw new UsageException("invalid param name: " + name);
            double v;
            try {
                v = Double.parseDouble(p.substring(eq + 1));
            } catch (NumberFormatException e) {
                throw new UsageException("param " + name + " is not a number");
            }
            if (Double.isNaN(v) || Double.isInfinite(v)) throw new UsageException("param " + name + " is not finite");
            if (params.put(name, v) != null) throw new UsageException("param " + name + " given twice");
        }
        String created = a.has("--created") ? a.single("--created") : Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        Instant.parse(created); // must be a valid UTC instant

        List<String> entries = new ArrayList<String>();
        for (ModelRole role : ModelRole.values()) {
            File f = new File(dir, role.fileName());
            if (!f.isFile()) throw new IOException("missing model file " + f);
            byte[] b = Files.readAllBytes(f.toPath());
            entries.add("    {\"role\": \"" + role.name() + "\", \"name\": \"" + role.fileName() + "\", \"size\": " + b.length
                    + ", \"sha256\": \"" + Hex.encode(MessageDigest.getInstance("SHA-256").digest(b)) + "\"}");
        }
        String json = manifestJson(version, keyId, created, params, entries);
        File target = new File(dir, VerifiedBundle.MANIFEST);
        Files.write(target.toPath(), json.getBytes(StandardCharsets.UTF_8));
        out.println("wrote " + target + " (" + json.length() + " bytes); sign it next");
        return 0;
    }

    /** Deterministic schema-1 manifest: fixed key order, 2-space indent, LF line endings, trailing newline. */
    static String manifestJson(String version, String keyId, String created, Map<String, Double> params, List<String> fileEntries) {
        StringBuilder p = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Double> e : params.entrySet()) {
            p.append(first ? " " : ", ").append('"').append(e.getKey()).append("\": ").append(Double.toString(e.getValue()));
            first = false;
        }
        p.append(params.isEmpty() ? "}" : " }");
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schema\": 1,\n");
        sb.append("  \"bundleVersion\": \"").append(version).append("\",\n");
        sb.append("  \"keyId\": \"").append(keyId).append("\",\n");
        sb.append("  \"createdUtc\": \"").append(created).append("\",\n");
        sb.append("  \"params\": ").append(p).append(",\n");
        sb.append("  \"files\": [\n");
        for (int i = 0; i < fileEntries.size(); i++) {
            sb.append(fileEntries.get(i)).append(i + 1 < fileEntries.size() ? ",\n" : "\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    // ---- sign ----

    private int sign(Args a) throws Exception {
        File dir = existingDir(a.required("--dir"));
        String keyId = keyId(a.required("--key-id"));
        File keyFile = new File(a.required("--key"));
        if (!keyFile.isFile()) throw new UsageException("key file not found: " + keyFile);
        byte[] manifest = Files.readAllBytes(new File(dir, VerifiedBundle.MANIFEST).toPath());
        Map<String, Object> json = MiniJson.parseObject(new String(manifest, StandardCharsets.UTF_8));
        String declared = MiniJson.getString(json, "keyId");
        if (!declared.equals(keyId)) {
            err.println("REFUSED: the manifest declares keyId '" + declared + "' but --key-id is '" + keyId + "'.");
            return 1;
        }
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(key);
        s.update(manifest);
        String line = keyId + ":" + Hex.encode(s.sign()) + "\n";
        File target = new File(dir, VerifiedBundle.SIGNATURE);
        Files.write(target.toPath(), line.getBytes(StandardCharsets.US_ASCII));
        out.println("wrote " + target + " (keyId " + keyId + ")");
        return 0;
    }

    // ---- verify / install / rollback ----

    private int verify(Args a) throws Exception {
        File dir = existingDir(a.required("--dir"));
        VerifiedBundle b = VerifiedBundle.load(new DirectoryBundleSource(dir), trust(a));
        out.println("OK " + b.version() + " (keyId " + b.keyId() + ") from " + dir);
        out.println("params: " + b.params());
        for (ModelRole role : ModelRole.values()) {
            out.println(String.format("  %-9s %-28s %9d  sha256 %s", role, b.fileName(role),
                    b.modelBytes().get(role).length, b.sha256().get(role)));
        }
        return 0;
    }

    private int install(Args a) throws Exception {
        ModelStore store = new ModelStore(new File(a.required("--store")));
        File from = existingDir(a.required("--from"));
        try {
            String v = store.install(new DirectoryBundleSource(from), trust(a));
            out.println("installed " + v + "; installed versions " + store.installedVersions());
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("REFUSED: " + e.getMessage());
            return 1;
        }
    }

    private int rollback(Args a) throws Exception {
        ModelStore store = new ModelStore(new File(a.required("--store")));
        try {
            String v = store.rollback(trust(a));
            out.println("rolled back; current is now " + v);
            return 0;
        } catch (IllegalStateException e) {
            err.println("REFUSED: " + e.getMessage());
            return 1;
        }
    }

    private static TrustedKeys trust(Args a) {
        if (!a.has("--trust")) return TrustedKeys.builtIn();
        Map<String, java.security.PublicKey> keys = new LinkedHashMap<String, java.security.PublicKey>();
        for (String t : a.all("--trust")) {
            int eq = t.indexOf('=');
            if (eq <= 0) throw new UsageException("--trust must be keyId=publicHex");
            try {
                keys.put(t.substring(0, eq), TrustedKeys.parsePublicKey(t.substring(eq + 1)));
            } catch (IllegalArgumentException e) {
                throw new UsageException("--trust: not a valid EC public key for " + t.substring(0, eq));
            }
        }
        return new TrustedKeys(keys);
    }

    private static String keyId(String id) {
        if (!KEY_ID.matcher(id).matches()) throw new UsageException("--key-id must match [A-Za-z0-9._-]{1,64}");
        return id;
    }

    private static File existingDir(String path) {
        File d = new File(path);
        if (!d.isDirectory()) throw new UsageException("not a directory: " + path);
        return d;
    }

    /** A usage mistake (exit code 2). */
    static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    /** --name value options; repeatable options keep every value; flags (e.g. --force) take no value. */
    static final class Args {
        private final Map<String, List<String>> values = new LinkedHashMap<String, List<String>>();

        static Args parse(String[] args, int from) {
            return parse(args, from, Collections.singletonList("--force"));
        }

        static Args parse(String[] args, int from, List<String> flags) {
            Args a = new Args();
            for (int i = from; i < args.length; i++) {
                String k = args[i];
                if (!k.startsWith("--")) throw new IllegalArgumentException("unexpected argument '" + k + "'");
                String v = "";
                if (!flags.contains(k)) {
                    if (i + 1 >= args.length) throw new IllegalArgumentException("missing value for " + k);
                    v = args[++i];
                }
                List<String> list = a.values.get(k);
                if (list == null) a.values.put(k, list = new ArrayList<String>());
                list.add(v);
            }
            return a;
        }

        boolean has(String k) {
            return values.containsKey(k);
        }

        String required(String k) {
            if (!has(k)) throw new UsageException("missing " + k);
            return single(k);
        }

        String single(String k) {
            List<String> v = values.get(k);
            if (v.size() != 1) throw new UsageException(k + " given more than once");
            return v.get(0);
        }

        List<String> all(String k) {
            List<String> v = values.get(k);
            return v == null ? Collections.<String>emptyList() : v;
        }
    }
}
