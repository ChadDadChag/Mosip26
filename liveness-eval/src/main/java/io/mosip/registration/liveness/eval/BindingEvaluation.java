package io.mosip.registration.liveness.eval;

import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzer;
import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory;
import io.mosip.registration.liveness.analyzer.match.FaceMatcher;
import io.mosip.registration.liveness.analyzer.match.SFaceEmbedder;
import io.mosip.registration.liveness.analyzer.quality.QualityReport;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Face binding (F1) evaluation (development only): how far apart are SFace similarities of the same person and of
 * different people, and where should the threshold be?
 * <pre>
 *   BindingEvaluation [--data eval-data] [--portraits models/tools/.cache/pitch-calibration]
 *                     [--list models/tools/pitch_calibration_urls.txt]
 *                     [--golden liveness-analyzer/src/test/resources/golden] [--out eval-data/reports/binding-yyyyMMdd]
 * </pre>
 * Faces:
 * <ul>
 *   <li>each recorded bona fide take: its best frame (one face, no quality issue; the sharpest frame (lapVar) with
 *       |yaw| and |pitch| at most 10 degrees, else the most frontal one);</li>
 *   <li>each screen-photo take: its best frame. "selfie" conditions show the recorded person's own photo,
 *       "nasa" conditions a public NASA portrait;</li>
 *   <li>the 49 public-domain calibration portraits (pitch_calibration_urls.txt), each with an identity label: several
 *       people appear in more than one photo, and some files are crops of the same photo;</li>
 *   <li>the four faces of the golden crew photo.</li>
 * </ul>
 * Sets: genuine (the person's takes with each other; the same NASA person in different photos), same person via a
 * photo (bona fide vs the screen photos of the person's own selfie: binding is not PAD, a photo of the same person
 * matches), and impostors (the person vs the portraits and vs the NASA screen photos; NASA vs NASA, different
 * people; crew vs crew and vs the portraits). Crops of the same photo are left out.
 *
 * <p>The report (aggregates only: similarity distributions, FNMR and FMR per threshold, timings) goes to the
 * gitignored eval-data/ folder. No image, feature or per-take value is written.
 */
public final class BindingEvaluation {

    static final double[] THRESHOLDS = {0.25, 0.30, 0.35, 0.363, 0.40, 0.45, 0.50, 0.55, 0.60};
    static final double FRONTAL_DEG = 10;

    private BindingEvaluation() { }

    /** A named set of similarities. */
    static final class Scores {
        final String name;
        final boolean genuine;
        final List<Double> values = new ArrayList<Double>();

        Scores(String name, boolean genuine) {
            this.name = name;
            this.genuine = genuine;
        }

        double[] sorted() {
            double[] a = new double[values.size()];
            for (int i = 0; i < a.length; i++) a[i] = values.get(i);
            Arrays.sort(a);
            return a;
        }

        /** Genuine: share below the threshold (false non-match). Impostor: share at or above it (false match). */
        double errorRate(double threshold) {
            if (values.isEmpty()) return Double.NaN;
            int n = 0;
            for (double v : values) if (genuine ? v < threshold : v >= threshold) n++;
            return (double) n / values.size();
        }

        int errors(double threshold) {
            int n = 0;
            for (double v : values) if (genuine ? v < threshold : v >= threshold) n++;
            return n;
        }
    }

    /** One public portrait: its identity and its photograph (crops of one photo share the source). */
    static final class Portrait {
        final String page, identity, source;
        final float[] feature;

        Portrait(String page, float[] feature) {
            this.page = page;
            this.identity = identity(page);
            this.source = source(page);
            this.feature = feature;
        }
    }

    // People who appear in more than one calibration photo (pitch_calibration_urls.txt, checked by hand).
    private static final String[][] SAME_PERSON = {
            {"(?i)aaron_cohen", "Aaron Cohen"}, {"(?i)hoshide", "Akihiko Hoshide"}, {"(?i)worden", "Al Worden"},
            {"(?i)shepard", "Alan Shepard"}, {"(?i)borman", "Frank Borman"}, {"(?i)cooper", "Gordon Cooper"},
            {"WHITE", "Edward White"}, {"(?i)andrew(_m\\.)?_allen", "Andrew Allen"}, {"(?i)menon", "Anil Menon"},
            {"(?i)fisher", "Anna Fisher"}};
    private static final Pattern CROP = Pattern.compile("_\\((cropped|\\dx\\d_cropped)\\)");

    static String identity(String page) {
        for (String[] p : SAME_PERSON) {
            if (Pattern.compile(p[0]).matcher(page).find()) return p[1];
        }
        return page;
    }

    /** The photograph a file comes from: "X_(cropped).jpg" and "X_(3x4_cropped).jpg" are crops of "X.jpg". */
    static String source(String page) {
        return CROP.matcher(page).replaceAll("");
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            ModelBundleTool.Args a = ModelBundleTool.Args.parse(args, 0, java.util.Collections.<String>emptyList());
            File data = new File(a.has("--data") ? a.single("--data") : "eval-data");
            File portraits = new File(a.has("--portraits") ? a.single("--portraits") : "models/tools/.cache/pitch-calibration");
            File list = new File(a.has("--list") ? a.single("--list") : "models/tools/pitch_calibration_urls.txt");
            File golden = new File(a.has("--golden") ? a.single("--golden") : "liveness-analyzer/src/test/resources/golden");
            String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
            File outDir = new File(a.has("--out") ? a.single("--out") : "eval-data/reports/binding-" + date);

            long t0 = System.nanoTime();
            OnnxFaceAnalyzerFactory factory = new OnnxFaceAnalyzerFactory();
            factory.preload();
            double preloadMs = (System.nanoTime() - t0) / 1e6;
            Map<String, Scores> sets = new LinkedHashMap<String, Scores>();
            Map<String, String> notes = new LinkedHashMap<String, String>();
            double embedMs;
            String version = factory.modelVersion();
            try (FaceMatcher matcher = factory.createMatcher()) {
                // Recorded takes: best frame per take.
                List<float[]> bonaFide = new ArrayList<float[]>(), selfiePhotos = new ArrayList<float[]>(),
                        nasaPhotos = new ArrayList<float[]>();
                int noFace = 0;
                for (EvalDataset.TakeRef t : EvalDataset.scan(data)) {
                    boolean bf = EvalDataset.BONA_FIDE.equals(t.species);
                    boolean screenPhoto = t.species.startsWith("screen_photo");
                    if (!bf && !screenPhoto) continue;
                    Frame best = bestFrame(factory, t);
                    float[] f = best == null ? null : matcher.featureOfSingleFace(best.rgb(), best.width(), best.height());
                    if (f == null) {
                        noFace++;
                        continue;
                    }
                    if (bf) bonaFide.add(f);
                    else if (t.condition.startsWith("selfie")) selfiePhotos.add(f);
                    else if (t.condition.startsWith("nasa")) nasaPhotos.add(f);
                    out.println("embedded best frame of " + t);
                }
                notes.put("recorded takes", bonaFide.size() + " bona fide, " + selfiePhotos.size()
                        + " screen photos of the person's own selfie, " + nasaPhotos.size()
                        + " screen photos of a NASA portrait; " + noFace + " without a usable single-face frame");

                // Public portraits.
                List<Portrait> people = new ArrayList<Portrait>();
                int skipped = 0;
                for (String line : Files.readAllLines(list.toPath(), StandardCharsets.UTF_8)) {
                    if (line.trim().isEmpty() || line.startsWith("#")) continue;
                    String[] cols = line.split("\t");
                    File img = new File(portraits, cols[1].substring(0, 16) + ".jpg");
                    String page = cols[2].substring(cols[2].lastIndexOf('/') + 1);
                    Frame fr = PadEvaluation.load(img, 0, 0, "portrait");
                    float[] f = matcher.featureOfSingleFace(fr.rgb(), fr.width(), fr.height());
                    if (f == null) skipped++;
                    else people.add(new Portrait(java.net.URLDecoder.decode(page, "UTF-8"), f));
                }
                notes.put("public portraits", people.size() + " of 49 with exactly one face (" + skipped + " skipped), "
                        + countIdentities(people) + " different people");

                Frame crewFrame = PadEvaluation.load(new File(golden, "crew_multi.png"), 0, 0, "golden");
                List<float[]> crew = matcher.featuresOfAllFaces(crewFrame.rgb(), crewFrame.width(), crewFrame.height());
                notes.put("crew photo", crew.size() + " faces");

                Scores g1 = set(sets, "genuine: the person's bona fide takes, pairwise", true);
                pairs(bonaFide, g1);
                Scores g2 = set(sets, "genuine: the same NASA person in different photos", true);
                Scores i2 = set(sets, "impostor: NASA vs NASA, different people", false);
                for (int i = 0; i < people.size(); i++) {
                    for (int j = i + 1; j < people.size(); j++) {
                        Portrait p = people.get(i), q = people.get(j);
                        if (p.source.equals(q.source)) continue; // crops of one photograph
                        double s = SFaceEmbedder.cosine(p.feature, q.feature);
                        (p.identity.equals(q.identity) ? g2 : i2).values.add(s);
                    }
                }
                Scores photo = set(sets, "same person via a photo: bona fide vs screen photos of the person's selfie", true);
                cross(bonaFide, selfiePhotos, photo);
                Scores i1 = set(sets, "impostor: the person vs the public portraits", false);
                for (float[] b : bonaFide) for (Portrait p : people) i1.values.add(SFaceEmbedder.cosine(b, p.feature));
                Scores i4 = set(sets, "impostor: the person vs screen photos of NASA portraits", false);
                cross(bonaFide, nasaPhotos, i4);
                Scores i3 = set(sets, "impostor: crew faces vs each other and vs the public portraits", false);
                pairs(crew, i3);
                for (float[] c : crew) for (Portrait p : people) i3.values.add(SFaceEmbedder.cosine(c, p.feature));

                // Speed: one face, detection + alignment + network, averaged after a warm-up.
                Frame portrait = PadEvaluation.load(new File(golden, "portrait_frontal.png"), 0, 0, "golden");
                for (int i = 0; i < 3; i++) matcher.featureOfSingleFace(portrait.rgb(), portrait.width(), portrait.height());
                int runs = 20;
                long s0 = System.nanoTime();
                for (int i = 0; i < runs; i++) matcher.featureOfSingleFace(portrait.rgb(), portrait.width(), portrait.height());
                embedMs = (System.nanoTime() - s0) / 1e6 / runs;
            } finally {
                factory.close();
            }
            String report = report(sets, notes, version, preloadMs, embedMs, date);
            out.print(report);
            if (!outDir.isDirectory() && !outDir.mkdirs()) throw new IOException("cannot create " + outDir);
            Files.write(new File(outDir, "report.md").toPath(), report.getBytes(StandardCharsets.UTF_8));
            out.println("wrote " + new File(outDir, "report.md"));
            return 0;
        } catch (ModelBundleTool.UsageException | IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            err.println("FAILED: " + e);
            e.printStackTrace(err);
            return 1;
        }
    }

    private static Scores set(Map<String, Scores> sets, String name, boolean genuine) {
        Scores s = new Scores(name, genuine);
        sets.put(name, s);
        return s;
    }

    private static void pairs(List<float[]> faces, Scores into) {
        for (int i = 0; i < faces.size(); i++) {
            for (int j = i + 1; j < faces.size(); j++) into.values.add(SFaceEmbedder.cosine(faces.get(i), faces.get(j)));
        }
    }

    private static void cross(List<float[]> a, List<float[]> b, Scores into) {
        for (float[] x : a) for (float[] y : b) into.values.add(SFaceEmbedder.cosine(x, y));
    }

    private static int countIdentities(List<Portrait> people) {
        java.util.Set<String> ids = new java.util.HashSet<String>();
        for (Portrait p : people) ids.add(p.identity);
        return ids.size();
    }

    /**
     * The take's best frame: one face with landmarks, no quality issue; among those with |yaw| and |pitch| at most
     * 10 degrees the sharpest (largest lapVar), else the most frontal (smallest |yaw| + |pitch|). A take without
     * such a frame (screen photos held close are often FACE_TOO_LARGE) falls back to its most frontal single-face
     * frame whatever its quality issues. Null when no frame has exactly one face with landmarks.
     */
    static Frame bestFrame(OnnxFaceAnalyzerFactory factory, EvalDataset.TakeRef t) throws Exception {
        OnnxFaceAnalyzer analyzer = (OnnxFaceAnalyzer) factory.create();
        try {
            Frame best = null, mostFrontal = null, anyQuality = null;
            double bestSharpness = -1, bestFrontalness = Double.MAX_VALUE, anyFrontalness = Double.MAX_VALUE;
            List<File> files = t.frames();
            for (int i = 0; i < files.size(); i++) {
                Frame f = PadEvaluation.load(files.get(i), Math.round(i * 1000.0 / t.fps), i, "eval:" + t);
                FrameAnalysis r = analyzer.analyze(f);
                QualityReport q = analyzer.lastQuality();
                if (r.faceCount != 1 || q == null || Float.isNaN(r.yawDeg)) continue;
                double off = Math.abs(r.yawDeg) + Math.abs(r.pitchDeg);
                if (off < anyFrontalness) {
                    anyFrontalness = off;
                    anyQuality = f;
                }
                if (!r.qualityIssues.isEmpty()) continue;
                if (off < bestFrontalness) {
                    bestFrontalness = off;
                    mostFrontal = f;
                }
                if (Math.abs(r.yawDeg) <= FRONTAL_DEG && Math.abs(r.pitchDeg) <= FRONTAL_DEG && q.lapVar > bestSharpness) {
                    bestSharpness = q.lapVar;
                    best = f;
                }
            }
            return best != null ? best : mostFrontal != null ? mostFrontal : anyQuality;
        } finally {
            analyzer.close();
        }
    }

    static String report(Map<String, Scores> sets, Map<String, String> notes, String version, double preloadMs,
                         double embedMs, String date) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Face binding (F1) evaluation: PROVISIONAL, SINGLE SUBJECT\n\n");
        sb.append("Generated ").append(date).append(" by `liveness-eval` `BindingEvaluation`, model bundle ").append(version)
                .append(", SFace cosine similarity. Aggregates only: no images, features or per-take values.\n\n");
        for (Map.Entry<String, String> e : notes.entrySet()) sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        sb.append("\n## Similarity distributions\n\n| set | pairs | min | p5 | median | p95 | max |\n|---|---|---|---|---|---|---|\n");
        for (Scores s : sets.values()) {
            double[] v = s.sorted();
            sb.append(String.format(Locale.ROOT, "| %s | %d | %s | %s | %s | %s | %s |%n", s.name, v.length, f(v, 0),
                    f(v, 5), f(v, 50), f(v, 95), f(v, 100)));
        }
        sb.append("\n## Error rates by threshold\n\nGenuine and same-person sets: FNMR, the share below the threshold. ")
                .append("Impostor sets: FMR, the share at or above it.\n\n| threshold |");
        for (Scores s : sets.values()) sb.append(' ').append(s.genuine ? "FNMR " : "FMR ").append(shortName(s.name)).append(" |");
        sb.append("\n|---|");
        for (int i = 0; i < sets.size(); i++) sb.append("---|");
        sb.append('\n');
        for (double t : THRESHOLDS) {
            sb.append(String.format(Locale.ROOT, "| %.3f |", t));
            for (Scores s : sets.values()) {
                sb.append(String.format(Locale.ROOT, " %d/%d (%.1f%%) |", s.errors(t), s.values.size(), 100 * s.errorRate(t)));
            }
            sb.append('\n');
        }
        sb.append(String.format(Locale.ROOT, "%n## Speed (this computer)%n%n- factory preload (verify the bundle, build 5 sessions): "
                + "%.0f ms%n- one face: detection + alignment + SFace: %.1f ms (mean of 20 after warm-up)%n", preloadMs, embedMs));
        return sb.toString();
    }

    /** Nearest-rank percentile of sorted values, 3 decimals; "-" when empty. */
    static String f(double[] sorted, double p) {
        if (sorted.length == 0) return "-";
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return String.format(Locale.ROOT, "%.3f", sorted[Math.min(sorted.length - 1, Math.max(0, rank - 1))]);
    }

    static String shortName(String name) {
        int colon = name.indexOf(':');
        String rest = colon >= 0 ? name.substring(colon + 1).trim() : name;
        return rest.length() > 28 ? rest.substring(0, 28) + "..." : rest;
    }
}
