package io.mosip.registration.liveness.analyzer.integrity;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.mosip.registration.liveness.analyzer.AnalyzerConfig;
import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory;
import io.mosip.registration.liveness.analyzer.integrity.ModelUpdater.Reason;
import io.mosip.registration.liveness.analyzer.integrity.ModelUpdater.Status;
import io.mosip.registration.liveness.analyzer.integrity.ModelUpdater.UpdateResult;
import io.mosip.registration.liveness.analyzer.runtime.ModelRole;
import io.mosip.registration.liveness.analyzer.testing.TestBundles;
import io.mosip.registration.liveness.analyzer.testing.TestBundles.MapSource;
import io.mosip.registration.liveness.analyzer.testing.TestModels;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** ModelUpdater against a local HTTP server (127.0.0.1, allowInsecureLocalhost). */
class ModelUpdaterTest {

    private static final String OLD = "pad-2026.10.1", NEW = "pad-2026.11.1";
    private static KeyPair kp;
    private static TrustedKeys trust;

    private HttpServer server;
    private ExecutorService executor;
    private final Map<String, byte[]> routes = new ConcurrentHashMap<String, byte[]>();
    private final Map<String, HttpHandler> special = new ConcurrentHashMap<String, HttpHandler>();
    private final List<String> hits = new CopyOnWriteArrayList<String>();

    @BeforeAll
    static void keys() throws Exception {
        kp = TestBundles.keyPair();
        trust = TestBundles.trust(TestBundles.KEY_ID, kp.getPublic());
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange x) throws IOException {
                String path = x.getRequestURI().getPath();
                hits.add(path);
                HttpHandler h = special.get(path);
                if (h != null) {
                    h.handle(x);
                    return;
                }
                byte[] body = routes.get(path);
                if (body == null) {
                    x.sendResponseHeaders(404, -1);
                    x.close();
                    return;
                }
                send(x, body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static void send(HttpExchange x, byte[] body) throws IOException {
        x.sendResponseHeaders(200, body.length);
        OutputStream o = x.getResponseBody();
        o.write(body);
        o.close();
    }

    private URL url(String path) throws IOException {
        return new URL("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private static ModelUpdater local() {
        return ModelUpdater.builder().allowInsecureLocalhost(true).connectTimeoutMs(2000).readTimeoutMs(2000).build();
    }

    private static Map<String, byte[]> files(String version, Map<ModelRole, byte[]> models) throws Exception {
        return TestBundles.bundle(version, kp, models, "{}");
    }

    private static MapSource bundled() throws Exception {
        return new MapSource(files(OLD, TestBundles.fakeModels(1)));
    }

    /** A zip as pack writes it. */
    private static byte[] zip(String version) throws Exception {
        return zip(files(version, TestBundles.fakeModels(version.hashCode())));
    }

    private static byte[] zip(Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ModelUpdater.writeZip(VerifiedBundle.load(new MapSource(files), trust), out);
        return out.toByteArray();
    }

    /** A zip with exactly these entries, in this order (for malformed layouts). */
    private static byte[] rawZip(Object... nameThenBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(out);
        for (int i = 0; i < nameThenBytes.length; i += 2) {
            z.putNextEntry(new ZipEntry((String) nameThenBytes[i]));
            z.write((byte[]) nameThenBytes[i + 1]);
            z.closeEntry();
        }
        z.close();
        return out.toByteArray();
    }

    /** Replaces every occurrence of an ASCII string with another of the same length. */
    private static byte[] replaceAll(byte[] data, String from, String to) {
        byte[] f = from.getBytes(StandardCharsets.US_ASCII), t = to.getBytes(StandardCharsets.US_ASCII);
        assertThat(t).hasSameSizeAs(f);
        byte[] out = data.clone();
        for (int i = 0; i + f.length <= out.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(out, i, i + f.length), f)) System.arraycopy(t, 0, out, i, t.length);
        }
        return out;
    }

    /** Serves the zip at /m/<version>.zip and its index at /m/latest.json (zipUrl relative to the index). */
    private void serve(String version, byte[] zip) throws Exception {
        routes.put("/m/" + version + ".zip", zip);
        routes.put("/m/latest.json", ModelUpdater.indexJson(version, version + ".zip", TestBundles.sha256(zip), zip.length)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void assertUntouched(ModelStore store) throws Exception {
        assertThat(store.currentVersion()).isNull();
        assertThat(store.installedVersions()).isEmpty();
        assertNoLeftovers(store);
    }

    private static void assertNoLeftovers(ModelStore store) {
        String[] names = store.root().list();
        for (String n : names == null ? new String[0] : names) {
            assertThat(n).doesNotStartWith(ModelUpdater.TMP_PREFIX);
        }
    }

    private static void assertFailed(UpdateResult r, Reason reason) {
        assertThat(r.status()).as(r.toString()).isEqualTo(Status.FAILED);
        assertThat(r.reason()).as(r.toString()).isEqualTo(reason);
    }

    @Test
    void aNewerBundleIsInstalledAndTheNextFactoryLoadUsesIt(@TempDir File root) throws Exception {
        Map<ModelRole, byte[]> real = TestModels.load();
        MapSource bundled = new MapSource(files(OLD, real));
        serve(NEW, zip(files(NEW, real)));
        ModelStore store = new ModelStore(root);

        UpdateResult r = local().check(url("/m/latest.json"), store, bundled, trust);

        assertThat(r.status()).as(r.toString()).isEqualTo(Status.INSTALLED);
        assertThat(r.version()).isEqualTo(NEW);
        assertThat(r.reason()).isNull();
        assertThat(r.detail()).isEmpty();
        assertThat(store.currentVersion()).isEqualTo(NEW);
        assertThat(store.installedVersions()).containsExactly(OLD, NEW); // the app's bundle became the rollback base
        assertNoLeftovers(store);
        try (OnnxFaceAnalyzerFactory f = new OnnxFaceAnalyzerFactory(bundled, store, trust, AnalyzerConfig.defaults())) {
            assertThat(f.modelVersion()).isEqualTo(NEW);
        }
    }

    @Test
    void theSameOrAnOlderVersionIsUpToDateWithoutDownloading(@TempDir File root) throws Exception {
        ModelStore store = new ModelStore(root);
        serve(OLD, zip(OLD));
        UpdateResult same = local().check(url("/m/latest.json"), store, bundled(), trust);
        assertThat(same.status()).isEqualTo(Status.UP_TO_DATE);
        assertThat(same.version()).isEqualTo(OLD);
        serve("pad-2026.09.3", zip("pad-2026.09.3"));
        assertThat(local().check(url("/m/latest.json"), store, bundled(), trust).status()).isEqualTo(Status.UP_TO_DATE);
        assertThat(hits).containsOnly("/m/latest.json");
        assertUntouched(store);

        // Once NEW is installed, offering NEW again is up to date (the store's version counts, not only the app's).
        serve(NEW, zip(NEW));
        assertThat(local().check(url("/m/latest.json"), store, bundled(), trust).status()).isEqualTo(Status.INSTALLED);
        UpdateResult again = local().check(url("/m/latest.json"), store, bundled(), trust);
        assertThat(again.status()).isEqualTo(Status.UP_TO_DATE);
        assertThat(again.version()).isEqualTo(NEW);
        assertThat(again.toString()).isEqualTo("UP_TO_DATE " + NEW);
    }

    @Test
    void aWrongZipShaFailsAndLeavesTheStoreUnchanged(@TempDir File root) throws Exception {
        ModelStore store = new ModelStore(root);
        byte[] zip = zip(NEW);
        routes.put("/m/" + NEW + ".zip", zip);
        routes.put("/m/latest.json", ModelUpdater.indexJson(NEW, NEW + ".zip", TestBundles.sha256(new byte[] {1}), zip.length)
                .getBytes(StandardCharsets.UTF_8));
        UpdateResult r = local().check(url("/m/latest.json"), store, bundled(), trust);
        assertFailed(r, Reason.ZIP_HASH_MISMATCH);
        assertThat(r.version()).isEqualTo(NEW);
        assertUntouched(store);
    }

    @Test
    void aZipSlipEntryIsRefusedAndNothingIsWrittenOutside(@TempDir File tmp) throws Exception {
        File root = new File(tmp, "store");
        ModelStore store = new ModelStore(root);
        Map<String, byte[]> f = files(NEW, TestBundles.fakeModels(5));
        serve(NEW, rawZip("manifest.json", f.get("manifest.json"), "../evil.onnx", new byte[] {6}));
        assertFailed(local().check(url("/m/latest.json"), store, bundled(), trust), Reason.ZIP_ENTRY_REFUSED);
        assertThat(new File(root, "evil.onnx")).doesNotExist();
        assertThat(new File(tmp, "evil.onnx")).doesNotExist();
        serve(NEW, rawZip("manifest.json", f.get("manifest.json"), "sub/manifest.sig", f.get("manifest.sig")));
        assertFailed(local().check(url("/m/latest.json"), store, bundled(), trust), Reason.ZIP_ENTRY_REFUSED);
        serve(NEW, rawZip("manifest.json", f.get("manifest.json"), "dir/", new byte[0]));
        assertFailed(local().check(url("/m/latest.json"), store, bundled(), trust), Reason.ZIP_ENTRY_REFUSED);
        assertUntouched(store);
    }

    @Test
    void anExtraADuplicateOrAnOversizeEntryIsRefused(@TempDir File root) throws Exception {
        ModelStore store = new ModelStore(root);
        Map<String, byte[]> f = files(NEW, TestBundles.fakeModels(7));
        String model = ModelRole.DETECTOR.fileName();
        serve(NEW, rawZip("manifest.json", f.get("manifest.json"), "README.txt", new byte[] {1}));
        assertFailed(local().check(url("/m/latest.json"), store, bundled(), trust), Reason.ZIP_ENTRY_REFUSED);
        // ZipOutputStream refuses duplicate names: write a same-length name, then patch it in the headers.
        String twin = model.substring(0, model.length() - 1) + "X";
        serve(NEW, replaceAll(rawZip("manifest.json", f.get("manifest.json"), model, f.get(model), twin, f.get(model)),
                twin, model));
        assertFailed(local().check(url("/m/latest.json"), store, bundled(), trust), Reason.ZIP_ENTRY_REFUSED);
        byte[] bigger = Arrays.copyOf(f.get(model), f.get(model).length + 1);
        serve(NEW, rawZip("manifest.json", f.get("manifest.json"), model, bigger));
        assertFailed(local().check(url("/m/latest.json"), store, bundled(), trust), Reason.ZIP_ENTRY_REFUSED);
        assertUntouched(store);
    }

    @Test
    void aZipWithoutTheManifestFirstOrWithMissingEntriesOrAnotherVersionIsInvalid(@TempDir File root) throws Exception {
        ModelStore store = new ModelStore(root);
        Map<String, byte[]> f = files(NEW, TestBundles.fakeModels(8));
        serve(NEW, rawZip("manifest.sig", f.get("manifest.sig"), "manifest.json", f.get("manifest.json")));
        assertFailed(local().check(url("/m/latest.json"), store, bundled(), trust), Reason.ZIP_INVALID);
        serve(NEW, rawZip("manifest.json", f.get("manifest.json"), "manifest.sig", f.get("manifest.sig")));
        assertFailed(local().check(url("/m/latest.json"), store, bundled(), trust), Reason.ZIP_INVALID);
        serve(NEW, "not a zip".getBytes(StandardCharsets.US_ASCII));
        assertFailed(local().check(url("/m/latest.json"), store, bundled(), trust), Reason.ZIP_INVALID);
        // The index offers 2026.12.1 but the zip holds NEW.
        byte[] zip = zip(NEW);
        routes.put("/m/x.zip", zip);
        routes.put("/m/latest.json", ModelUpdater.indexJson("pad-2026.12.1", "x.zip", TestBundles.sha256(zip), zip.length)
                .getBytes(StandardCharsets.UTF_8));
        assertFailed(local().check(url("/m/latest.json"), store, bundled(), trust), Reason.ZIP_INVALID);
        assertUntouched(store);
    }

    @Test
    void aValidZipWithABadSignatureFailsInTheInstall(@TempDir File root) throws Exception {
        ModelStore store = new ModelStore(root);
        KeyPair other = TestBundles.keyPair();
        Map<String, byte[]> f = files(NEW, TestBundles.fakeModels(9));
        byte[] forged = TestBundles.sigLine(TestBundles.KEY_ID, f.get("manifest.json"), other.getPrivate());
        Map<String, byte[]> entries = new java.util.LinkedHashMap<String, byte[]>();
        entries.put("manifest.json", f.get("manifest.json"));
        entries.put("manifest.sig", forged);
        for (ModelRole role : ModelRole.values()) entries.put(role.fileName(), f.get(role.fileName()));
        Object[] layout = new Object[entries.size() * 2];
        int i = 0;
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            layout[i++] = e.getKey();
            layout[i++] = e.getValue();
        }
        serve(NEW, rawZip(layout));
        UpdateResult r = local().check(url("/m/latest.json"), store, bundled(), trust);
        assertFailed(r, Reason.BUNDLE_INVALID);
        assertThat(r.detail()).contains("bad manifest signature");
        assertUntouched(store);
    }

    @Test
    void anOversizeIndexOrZipFails(@TempDir File root) throws Exception {
        ModelStore store = new ModelStore(root);
        char[] pad = new char[ModelUpdater.MAX_INDEX_BYTES];
        Arrays.fill(pad, ' ');
        byte[] zip = zip(NEW);
        String index = ModelUpdater.indexJson(NEW, NEW + ".zip", TestBundles.sha256(zip), zip.length);
        routes.put("/m/" + NEW + ".zip", zip);
        routes.put("/m/latest.json", (index + new String(pad)).getBytes(StandardCharsets.UTF_8));
        assertFailed(local().check(url("/m/latest.json"), store, bundled(), trust), Reason.INDEX_INVALID);

        // Chunked (no Content-Length): still capped while reading.
        final byte[] big = (index + new String(pad)).getBytes(StandardCharsets.UTF_8);
        special.put("/m/chunked.json", new HttpHandler() {
            @Override
            public void handle(HttpExchange x) throws IOException {
                x.sendResponseHeaders(200, 0);
                OutputStream o = x.getResponseBody();
                o.write(big);
                o.close();
            }
        });
        assertFailed(local().check(url("/m/chunked.json"), store, bundled(), trust), Reason.INDEX_INVALID);

        routes.put("/m/latest.json", index.replace("\"zipSize\": " + zip.length, "\"zipSize\": " + (ModelUpdater.MAX_ZIP_BYTES + 1L))
                .getBytes(StandardCharsets.UTF_8));
        assertFailed(local().check(url("/m/latest.json"), store, bundled(), trust), Reason.ZIP_SIZE);
        assertThat(hits).doesNotContain("/m/" + NEW + ".zip");

        // More bytes than zipSize: with a Content-Length, and chunked.
        routes.put("/m/latest.json", ModelUpdater.indexJson(NEW, NEW + ".zip", TestBundles.sha256(zip), zip.length - 10)
                .getBytes(StandardCharsets.UTF_8));
        assertFailed(local().check(url("/m/latest.json"), store, bundled(), trust), Reason.ZIP_SIZE);
        final byte[] served = zip;
        special.put("/m/" + NEW + ".zip", new HttpHandler() {
            @Override
            public void handle(HttpExchange x) throws IOException {
                x.sendResponseHeaders(200, 0);
                OutputStream o = x.getResponseBody();
                o.write(served);
                o.close();
            }
        });
        assertFailed(local().check(url("/m/latest.json"), store, bundled(), trust), Reason.ZIP_SIZE);
        // Fewer bytes than zipSize (chunked, so only the count tells).
        routes.put("/m/latest.json", ModelUpdater.indexJson(NEW, NEW + ".zip", TestBundles.sha256(zip), zip.length + 10)
                .getBytes(StandardCharsets.UTF_8));
        assertFailed(local().check(url("/m/latest.json"), store, bundled(), trust), Reason.ZIP_SIZE);
        assertUntouched(store);
    }

    @Test
    void connectionRefusedATimeoutOrAnHttpErrorFails(@TempDir File root) throws Exception {
        ModelStore store = new ModelStore(root);
        int closed;
        ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        closed = s.getLocalPort();
        s.close();
        assertFailed(local().check(new URL("http://127.0.0.1:" + closed + "/latest.json"), store, bundled(), trust),
                Reason.NETWORK);

        special.put("/slow.json", new HttpHandler() {
            @Override
            public void handle(HttpExchange x) throws IOException {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                x.close();
            }
        });
        ModelUpdater impatient = ModelUpdater.builder().allowInsecureLocalhost(true).readTimeoutMs(300).build();
        assertFailed(impatient.check(url("/slow.json"), store, bundled(), trust), Reason.NETWORK);

        assertFailed(local().check(url("/missing.json"), store, bundled(), trust), Reason.HTTP_STATUS);
        routes.put("/bad.json", "{\"schema\": 1}".getBytes(StandardCharsets.UTF_8));
        assertFailed(local().check(url("/bad.json"), store, bundled(), trust), Reason.INDEX_INVALID);
        routes.put("/bad.json", "{\"schema\": 1, \"extra\": 2}".getBytes(StandardCharsets.UTF_8));
        assertFailed(local().check(url("/bad.json"), store, bundled(), trust), Reason.INDEX_INVALID);
        assertUntouched(store);
    }

    @Test
    void plainHttpOnlyToLocalhostAndOnlyWithTheFlag(@TempDir File root) throws Exception {
        ModelStore store = new ModelStore(root);
        serve(NEW, zip(NEW));
        ModelUpdater strict = ModelUpdater.builder().build();
        assertFailed(strict.check(url("/m/latest.json"), store, bundled(), trust), Reason.INSECURE_TRANSPORT);
        assertThat(hits).isEmpty();
        assertFailed(local().check(new URL("http://example.invalid/latest.json"), store, bundled(), trust),
                Reason.INSECURE_TRANSPORT);
        assertFailed(local().check(new URL("ftp://127.0.0.1/latest.json"), store, bundled(), trust),
                Reason.INSECURE_TRANSPORT);

        // A localhost answer may not send the client to plain HTTP elsewhere: not by redirect, not by zipUrl.
        special.put("/hop.json", redirect("http://example.invalid/m/latest.json"));
        assertFailed(local().check(url("/hop.json"), store, bundled(), trust), Reason.INSECURE_TRANSPORT);
        byte[] zip = zip(NEW);
        routes.put("/m/away.json", ModelUpdater.indexJson(NEW, "http://example.invalid/x.zip", TestBundles.sha256(zip),
                zip.length).getBytes(StandardCharsets.UTF_8));
        assertFailed(local().check(url("/m/away.json"), store, bundled(), trust), Reason.INSECURE_TRANSPORT);
        assertUntouched(store);
    }

    @Test
    void redirectsAreFollowedUnderTheSameRuleUpToALimit(@TempDir File root) throws Exception {
        ModelStore store = new ModelStore(root);
        serve(NEW, zip(NEW));
        special.put("/a.json", redirect("/b.json"));
        special.put("/b.json", redirect(url("/m/latest.json").toString()));
        UpdateResult r = local().check(url("/a.json"), store, bundled(), trust);
        assertThat(r.status()).as(r.toString()).isEqualTo(Status.INSTALLED);

        special.put("/loop.json", redirect("/loop.json"));
        assertFailed(local().check(url("/loop.json"), new ModelStore(new File(root, "other")), bundled(), trust),
                Reason.HTTP_STATUS);
        special.put("/nowhere.json", new HttpHandler() {
            @Override
            public void handle(HttpExchange x) throws IOException {
                x.sendResponseHeaders(302, -1);
                x.close();
            }
        });
        assertFailed(local().check(url("/nowhere.json"), new ModelStore(new File(root, "other")), bundled(), trust),
                Reason.HTTP_STATUS);
    }

    @Test
    void localProblemsAreStorageFailures(@TempDir File root) throws Exception {
        serve(NEW, zip(NEW));
        ModelStore store = new ModelStore(root);
        java.nio.file.Files.write(new File(root, "current").toPath(), "garbage".getBytes(StandardCharsets.US_ASCII));
        assertFailed(local().check(url("/m/latest.json"), store, bundled(), trust), Reason.STORAGE);
        MapSource noManifest = new MapSource(Collections.<String, byte[]>emptyMap());
        assertFailed(local().check(url("/m/latest.json"), new ModelStore(new File(root, "b")), noManifest, trust),
                Reason.STORAGE);
    }

    @Test
    void leftoversOfACrashedDownloadAreRemoved(@TempDir File root) throws Exception {
        ModelStore store = new ModelStore(root);
        File stale = new File(root, ModelUpdater.TMP_PREFIX + "deadbeef");
        assertThat(new File(stale, "bundle").mkdirs()).isTrue();
        serve(NEW, zip(NEW));
        assertThat(local().check(url("/m/latest.json"), store, bundled(), trust).status()).isEqualTo(Status.INSTALLED);
        assertThat(stale).doesNotExist();
    }

    // ---- installZip (F17: offline install from local or removable media) ----

    private static File write(File dir, String name, byte[] data) throws IOException {
        File f = new File(dir, name);
        java.nio.file.Files.write(f.toPath(), data);
        return f;
    }

    private static ModelUpdater offline() {
        return ModelUpdater.builder().build();
    }

    @Test
    void anOfflineZipIsInstalledAndItsTemporaryDirectoryIsRemoved(@TempDir File tmp) throws Exception {
        Map<ModelRole, byte[]> real = TestModels.load();
        MapSource bundled = new MapSource(files(OLD, real));
        ModelStore store = new ModelStore(new File(tmp, "store"));
        File zip = write(tmp, "bundle.zip", zip(files(NEW, real)));

        assertThat(ModelUpdater.activeVersion(store, bundled)).isEqualTo(OLD);
        UpdateResult r = offline().installZip(zip, store, bundled, trust);

        assertThat(ModelUpdater.activeVersion(store, bundled)).isEqualTo(NEW);
        assertThat(r.status()).as(r.toString()).isEqualTo(Status.INSTALLED);
        assertThat(r.version()).isEqualTo(NEW);
        assertThat(store.installedVersions()).containsExactly(OLD, NEW);
        assertNoLeftovers(store);
        assertThat(zip).exists(); // the operator's file is left alone
        try (OnnxFaceAnalyzerFactory f = new OnnxFaceAnalyzerFactory(bundled, store, trust, AnalyzerConfig.defaults())) {
            assertThat(f.modelVersion()).isEqualTo(NEW);
        }
        // The same zip again is refused; a rollback is the store's explicit operator action.
        UpdateResult again = offline().installZip(zip, store, bundled, trust);
        assertThat(again.status()).isEqualTo(Status.UP_TO_DATE);
        assertThat(again.version()).isEqualTo(NEW);
        assertThat(store.rollback(trust)).isEqualTo(OLD);
        assertNoLeftovers(store);
    }

    @Test
    void anOfflineZipThatIsNotNewerIsRefusedAndTheStoreIsUnchanged(@TempDir File tmp) throws Exception {
        ModelStore store = new ModelStore(new File(tmp, "store"));
        UpdateResult same = offline().installZip(write(tmp, "same.zip", zip(OLD)), store, bundled(), trust);
        assertThat(same.status()).isEqualTo(Status.UP_TO_DATE);
        assertThat(same.version()).isEqualTo(OLD);
        UpdateResult older = offline().installZip(write(tmp, "older.zip", zip("pad-2026.09.3")), store, bundled(), trust);
        assertThat(older.status()).isEqualTo(Status.UP_TO_DATE);
        assertUntouched(store);
    }

    @Test
    void aTamperedOrUnsignedOfflineZipFailsClosed(@TempDir File tmp) throws Exception {
        ModelStore store = new ModelStore(new File(tmp, "store"));
        Map<String, byte[]> f = files(NEW, TestBundles.fakeModels(11));
        String model = ModelRole.DETECTOR.fileName();
        byte[] flipped = f.get(model).clone();
        flipped[flipped.length / 2] ^= 1; // one byte of a model, the manifest and signature unchanged
        UpdateResult tampered = offline().installZip(write(tmp, "tampered.zip",
                rawZip(packLayout(f, f.get("manifest.sig"), model, flipped))), store, bundled(), trust);
        assertFailed(tampered, Reason.BUNDLE_INVALID);
        assertThat(tampered.version()).isEqualTo(NEW);
        // Without manifest.sig the zip lacks a named entry; with a garbage signature the install refuses it.
        assertFailed(offline().installZip(write(tmp, "unsigned.zip", rawZip("manifest.json", f.get("manifest.json"))),
                store, bundled(), trust), Reason.ZIP_INVALID);
        byte[] garbage = (TestBundles.KEY_ID + ":00").getBytes(StandardCharsets.US_ASCII);
        assertFailed(offline().installZip(write(tmp, "badsig.zip", rawZip(packLayout(f, garbage, model, f.get(model)))),
                store, bundled(), trust), Reason.BUNDLE_INVALID);
        assertUntouched(store);
    }

    @Test
    void aTamperedOfflineZipIsInvalidEvenWhenItsVersionIsNotNewer(@TempDir File tmp) throws Exception {
        ModelStore store = new ModelStore(new File(tmp, "store"));
        String model = ModelRole.DETECTOR.fileName();
        byte[] garbage = (TestBundles.KEY_ID + ":00").getBytes(StandardCharsets.US_ASCII);
        for (String version : new String[] {"pad-2026.09.3", OLD}) { // older than, and the same as, the app's own
            Map<String, byte[]> f = files(version, TestBundles.fakeModels(14));
            byte[] flipped = f.get(model).clone();
            flipped[flipped.length / 2] ^= 1;
            UpdateResult tampered = offline().installZip(write(tmp, version + "-tampered.zip",
                    rawZip(packLayout(f, f.get("manifest.sig"), model, flipped))), store, bundled(), trust);
            assertFailed(tampered, Reason.BUNDLE_INVALID); // verify first: never reported as "not newer"
            assertFailed(offline().installZip(write(tmp, version + "-badsig.zip",
                    rawZip(packLayout(f, garbage, model, f.get(model)))), store, bundled(), trust), Reason.BUNDLE_INVALID);
            // The same files untouched: verified, then refused as not newer.
            UpdateResult valid = offline().installZip(write(tmp, version + ".zip", zip(f)), store, bundled(), trust);
            assertThat(valid.status()).as(valid.toString()).isEqualTo(Status.UP_TO_DATE);
            assertThat(valid.version()).isEqualTo(OLD);
        }
        assertUntouched(store);
    }

    /** The pack layout of a bundle's files, with this signature and one model's bytes replaced. */
    private static Object[] packLayout(Map<String, byte[]> f, byte[] sig, String model, byte[] modelBytes) {
        Object[] layout = new Object[(2 + ModelRole.values().length) * 2];
        int i = 0;
        layout[i++] = "manifest.json";
        layout[i++] = f.get("manifest.json");
        layout[i++] = "manifest.sig";
        layout[i++] = sig;
        for (ModelRole role : ModelRole.values()) {
            layout[i++] = role.fileName();
            layout[i++] = role.fileName().equals(model) ? modelBytes : f.get(role.fileName());
        }
        return layout;
    }

    @Test
    void anOfflineZipSlipOversizeOrOverlongZipIsRefused(@TempDir File tmp) throws Exception {
        File root = new File(tmp, "store");
        ModelStore store = new ModelStore(root);
        Map<String, byte[]> f = files(NEW, TestBundles.fakeModels(12));
        assertFailed(offline().installZip(write(tmp, "slip.zip", rawZip("manifest.json", f.get("manifest.json"),
                "../evil.onnx", new byte[] {6})), store, bundled(), trust), Reason.ZIP_ENTRY_REFUSED);
        assertThat(new File(tmp, "evil.onnx")).doesNotExist();
        assertThat(new File(root, "evil.onnx")).doesNotExist();
        assertFailed(offline().installZip(write(tmp, "abs.zip", rawZip("manifest.json", f.get("manifest.json"),
                "/abs.onnx", new byte[] {6})), store, bundled(), trust), Reason.ZIP_ENTRY_REFUSED);
        // A manifest naming more files than a bundle can have.
        StringBuilder many = new StringBuilder("{\"bundleVersion\": \"" + NEW + "\", \"files\": [");
        for (int k = 0; k <= ModelUpdater.MAX_MANIFEST_FILES; k++) {
            many.append(k > 0 ? ", " : "").append("{\"name\": \"f").append(k).append(".onnx\", \"size\": 1}");
        }
        many.append("]}");
        UpdateResult overlong = offline().installZip(write(tmp, "many.zip",
                rawZip("manifest.json", many.toString().getBytes(StandardCharsets.UTF_8))), store, bundled(), trust);
        assertFailed(overlong, Reason.ZIP_INVALID);
        assertThat(overlong.detail()).contains("more than " + ModelUpdater.MAX_MANIFEST_FILES);
        // Larger than 64 MiB: refused before it is opened (a sparse file, no 64 MiB write).
        File big = new File(tmp, "big.zip");
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(big, "rw")) {
            raf.setLength(ModelUpdater.MAX_ZIP_BYTES + 1L);
        }
        assertFailed(offline().installZip(big, store, bundled(), trust), Reason.ZIP_SIZE);
        assertFailed(offline().installZip(new File(tmp, "missing.zip"), store, bundled(), trust), Reason.STORAGE);
        assertFailed(offline().installZip(write(tmp, "text.zip", "not a zip".getBytes(StandardCharsets.US_ASCII)), store,
                bundled(), trust), Reason.ZIP_INVALID);
        assertUntouched(store);
    }

    @Test
    void packHelpersRejectBadInput() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ModelUpdater.indexJson("v1", "x.zip", "00", 1))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ModelUpdater.builder().readTimeoutMs(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ModelUpdater.indexJson(NEW, "a\"b\\c\u0001.zip", sha('a'), 5))
                .contains("\"zipUrl\": \"a\\\"b\\\\c\\u0001.zip\"");
        assertThat(ModelUpdater.printable("ok\u0000é")).isEqualTo("ok??");
        assertThat(ModelUpdater.isLocalhost("LOCALHOST")).isTrue();
        assertThat(ModelUpdater.isLocalhost("127.0.0.1.example")).isFalse();
    }

    private static String sha(char c) {
        char[] h = new char[64];
        Arrays.fill(h, c);
        return new String(h);
    }

    private static HttpHandler redirect(final String location) {
        return new HttpHandler() {
            @Override
            public void handle(HttpExchange x) throws IOException {
                x.getResponseHeaders().set("Location", location);
                x.sendResponseHeaders(302, -1);
                x.close();
            }
        };
    }
}
